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

package com.pyamsoft.tetherfi.server.proxy.manager.factory

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.SocketCreator
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.event.ServerStopRequestEvent
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.proxy.manager.TcpProxyManager
import com.pyamsoft.tetherfi.server.proxy.manager.netty.NettyDelegatingProxyManager
import com.pyamsoft.tetherfi.server.proxy.session.ProxySession
import com.pyamsoft.tetherfi.server.proxy.session.tcp.TcpProxyData
import javax.inject.Inject
import javax.inject.Named
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class DefaultProxyManagerFactory
@Inject
internal constructor(
    @param:ServerInternalApi private val socketBinder: SocketBinder,
    @param:Named("debug") private val isDebug: Boolean,
    @param:Named("app_scope") private val appScope: CoroutineScope,
    @param:Named("http") private val httpSession: ProxySession<TcpProxyData>,
    @param:Named("socks") private val socksSession: ProxySession<TcpProxyData>,
    private val expertPreferences: ExpertPreferences,
    private val socketTagger: SocketTagger,
    private val enforcer: ThreadEnforcer,
    private val appEnvironment: AppDevEnvironment,
    private val serverStopConsumer: EventConsumer<ServerStopRequestEvent>,
    private val blockedClients: BlockedClients,
    private val clientResolver: ClientResolver,
    private val allowedClients: AllowedClients,
) : ProxyManager.Factory {

  @CheckResult
  private fun createTcp(
      proxyType: SharedProxy.Type,
      session: ProxySession<TcpProxyData>,
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      dispatcher: ServerDispatcher,
      port: Int,
  ): ProxyManager {
    enforcer.assertOffMainThread()

    return TcpProxyManager(
        appScope = appScope,
        socketTagger = socketTagger,
        appEnvironment = appEnvironment,
        yoloRepeatDelay = 3.seconds,
        enforcer = enforcer,
        serverStopConsumer = serverStopConsumer,
        socketBinder = socketBinder,
        expertPreferences = expertPreferences,
        proxyType = proxyType,
        session = session,
        hostConnection = info,
        port = port,
        serverDispatcher = dispatcher,
        socketCreator = socketCreator,
    )
  }

  @CheckResult
  private suspend fun createNetty(
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      isHttpEnabled: Boolean,
      isSocksEnabled: Boolean,
      httpPort: Int,
  ): ProxyManager {
    enforcer.assertOffMainThread()

    val socketTimeout = expertPreferences.listenForSocketTimeout().first()

    Timber.d { "Using new Netty server" }
    return NettyDelegatingProxyManager(
        isDebug = isDebug,
        blockedClients = blockedClients,
        allowedClients = allowedClients,
        clientResolver = clientResolver,
        socketBinder = socketBinder,
        socketTagger = socketTagger,
        isHttpEnabled = isHttpEnabled,
        isSocksEnabled = isSocksEnabled,
        serverSocketTimeout = socketTimeout,
        hostConnection = info,
        port = httpPort,
    )
  }

  @CheckResult
  private fun createHttp(
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      dispatcher: ServerDispatcher,
      httpPort: Int,
  ): ProxyManager {
    enforcer.assertOffMainThread()

    return createTcp(
        proxyType = SharedProxy.Type.HTTP,
        session = httpSession,
        info = info,
        socketCreator = socketCreator,
        dispatcher = dispatcher,
        port = httpPort,
    )
  }

  @CheckResult
  private fun createSocks(
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      dispatcher: ServerDispatcher,
      socksPort: Int,
  ): ProxyManager {
    enforcer.assertOffMainThread()

    return createTcp(
        proxyType = SharedProxy.Type.SOCKS,
        session = socksSession,
        info = info,
        socketCreator = socketCreator,
        dispatcher = dispatcher,
        port = socksPort,
    )
  }

  override suspend fun create(
      type: SharedProxy.Type,
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
      isHttpEnabled: Boolean,
      isSocksEnabled: Boolean,
      httpPort: Int,
      socksPort: Int,
  ): ProxyManager =
      withContext(context = Dispatchers.Default) {
        return@withContext when (type) {
          SharedProxy.Type.NETTY ->
              createNetty(
                  info = info,
                  isHttpEnabled = isHttpEnabled,
                  isSocksEnabled = isSocksEnabled,
                  httpPort = httpPort,
              )

          SharedProxy.Type.HTTP ->
              createHttp(
                  info = info,
                  socketCreator = socketCreator,
                  dispatcher = serverDispatcher,
                  httpPort = httpPort,
              )

          SharedProxy.Type.SOCKS ->
              createSocks(
                  info = info,
                  socketCreator = socketCreator,
                  dispatcher = serverDispatcher,
                  socksPort = socksPort,
              )
        }
      }
}
