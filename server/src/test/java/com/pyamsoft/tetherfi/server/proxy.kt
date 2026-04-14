/*
 * Copyright 2025 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pyamsoft.tetherfi.server

import com.pyamsoft.pydroid.bus.internal.DefaultEventBus
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.notification.NotificationErrorLauncher
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.lock.NoopLock
import com.pyamsoft.tetherfi.server.network.PassthroughSocketBinder
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.manager.TcpProxyManager
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.HttpProxySession
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.HttpTransport
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.UrlRequestParser
import io.ktor.network.sockets.SocketBuilder
import java.io.IOException
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import timber.log.Timber

@OptIn(DelicateCoroutinesApi::class)
internal suspend fun setupProxy(
    scope: CoroutineScope,
    proxyTypes: Collection<SharedProxy.Type>,
    proxyPort: Int,
    nThreads: Int = 1,
    isLoggingEnabled: Boolean = false,
    expectServerFail: Boolean = false,
    testSocketCrash: Boolean = false,
    appEnv: AppDevEnvironment.() -> Unit = {},
    withServer: suspend CoroutineScope.(Int, CoroutineDispatcher) -> Unit,
) {
  assert(proxyPort >= 0 && proxyPort <= 65000)
  assert(nThreads >= 1)
  assert(scope.isActive)
  assert(proxyTypes.isNotEmpty())

  val dispatcher =
      object : ServerDispatcher {
        override val primary = newFixedThreadPoolContext(nThreads = nThreads, "TEST")
        override val sideEffect = primary

        override fun shutdown() {}
      }

  val enforcer =
      object : ThreadEnforcer {
        override fun assertOffMainThread() {}

        override fun assertOnMainThread() {}
      }

  val blocked =
      object : BlockedClients {
        override fun listenForBlocked(): Flow<Collection<TetherClient>> {
          return flowOf(emptyList())
        }

        override fun isBlocked(client: TetherClient): Boolean {
          return false
        }
      }

  val allowed =
      object : AllowedClients {
        override fun listenForClients(): Flow<List<TetherClient>> {
          return flowOf(emptyList())
        }

        override fun seen(client: TetherClient) {}

        override fun reportTransfer(client: TetherClient, report: ByteTransferReport) {}
      }

  val resolver =
      object : ClientResolver {

        private val clients = mutableMapOf<String, TetherClient>()

        override fun ensure(hostNameOrIp: String): TetherClient {
          return clients.getOrPut(hostNameOrIp) {
            TetherClient.create(
                hostNameOrIp,
                clock = Clock.systemDefaultZone(),
            )
          }
        }
      }

  val socketTagger = SocketTagger {}

  var tree: Timber.Tree? = null
  if (isLoggingEnabled) {
    val t =
        object : Timber.Tree() {
          override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            t?.printStackTrace()
            println(message)
          }
        }
    tree = t
    Timber.plant(t)
  }

  val transport =
      HttpTransport(
          requestParser =
              UrlRequestParser(
                  urlFixers = mutableSetOf(),
              ),
          enforcer = enforcer,
      )

  val expertPreferences =
      object : ExpertPreferences {
        override fun listenForPerformanceLimits(): Flow<ServerPerformanceLimit> {
          return flowOf(ServerPerformanceLimit.Defaults.BOUND_3N_CPU)
        }

        override fun setServerPerformanceLimit(limit: ServerPerformanceLimit) {}

        override fun listenForSocketTimeout(): Flow<ServerSocketTimeout> {
          return flowOf(ServerSocketTimeout.Defaults.BALANCED)
        }

        override fun setSocketTimeout(limit: ServerSocketTimeout) {}

        override fun listenForBroadcastType(): Flow<BroadcastType> {
          return flowOf(BroadcastType.WIFI_DIRECT)
        }

        override fun setBroadcastType(type: BroadcastType) {}

        override fun listenForPreferredNetwork(): Flow<PreferredNetwork> {
          return flowOf(PreferredNetwork.NONE)
        }

        override fun setPreferredNetwork(network: PreferredNetwork) {}
      }

  val appEnvironment =
      AppDevEnvironment(
              inAppDebug = flowOf(true),
          )
          .apply(appEnv)

  val socketCreator =
      if (testSocketCrash)
          object : SocketCreator {
            override suspend fun <T> create(
                type: SocketCreator.Type,
                onError: (Throwable) -> Unit,
                onBuild: suspend (SocketBuilder) -> T,
            ): T {
              throw IllegalStateException("Expected CRASH: Too many files")
            }
          }
      else SocketCreator.create(scope, appEnvironment, dispatcher)

  val notificationErrorLauncher =
      object : NotificationErrorLauncher {
        override suspend fun showError(throwable: Throwable) {}

        override suspend fun hideError() {}
      }

  var port = proxyPort
  val jobs = mutableListOf<Job>()
  for (proxyType in proxyTypes) {
    val job =
        scope.launch {
          val manager =
              TcpProxyManager(
                  appScope = scope,
                  proxyType = proxyType,
                  appEnvironment = appEnvironment,
                  session =
                      HttpProxySession(
                          appScope = scope,
                          transport = transport,
                          blockedClients = blocked,
                          allowedClients = allowed,
                          enforcer = enforcer,
                          socketTagger = socketTagger,
                          clientResolver = resolver,
                          notificationErrorLauncher = notificationErrorLauncher,
                      ),
                  hostConnection =
                      BroadcastNetworkStatus.ConnectionInfo.Connected(
                          hostName = HOSTNAME,
                      ),
                  port = port,
                  serverDispatcher = dispatcher,
                  socketTagger = socketTagger,
                  yoloRepeatDelay = 0.seconds,
                  enforcer = enforcer,
                  serverStopConsumer = DefaultEventBus(),
                  socketBinder = PassthroughSocketBinder(),
                  expertPreferences = expertPreferences,
                  socketCreator = socketCreator,
              )

          val expectedErrorCode =
              if (expectServerFail) "server" else if (testSocketCrash) "socket" else "none"
          var errorCode = "none"

          val server =
              scope.async {
                val block = suspend {
                  manager.loop(
                      lock = NoopLock,
                      onOpened = {},
                      onClosing = {},
                      onClosed = {},
                      onError = { e ->
                        if (expectServerFail) {
                          errorCode = "server"
                          assertEquals(
                              IOException::class.java,
                              e::class.java,
                          )
                        } else if (testSocketCrash) {
                          errorCode = "socket"
                          assertEquals(
                              IllegalStateException::class.java,
                              e::class.java,
                          )
                        } else {
                          fail("Got exception but was not expecting one!", e)
                        }
                      },
                  )
                }

                block()
              }

          println("Start proxy $HOSTNAME $port")
          delay(3.seconds)

          println("Run with proxy")
          scope.withServer(port++, dispatcher.primary)

          println("Done proxy")
          server.cancel()

          // Make sure we got errors when expected
          assertEquals(expectedErrorCode, errorCode)
        }
    jobs.add(job)
  }

  // Wait for all jobs to be done
  jobs.joinAll()

  tree?.also { Timber.uproot(it) }
}
