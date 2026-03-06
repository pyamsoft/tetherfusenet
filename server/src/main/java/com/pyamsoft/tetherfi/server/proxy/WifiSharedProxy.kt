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

package com.pyamsoft.tetherfi.server.proxy

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.BaseServer
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.SocketCreator
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.ClientEraser
import com.pyamsoft.tetherfi.server.clients.StartedClients
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.status.RunningStatus
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
internal class WifiSharedProxy
@Inject
internal constructor(
    @param:Named("app_scope") private val appScope: CoroutineScope,
    @param:ServerInternalApi private val serverDispatcherFactory: ServerDispatcher.Factory,
    @param:ServerInternalApi private val factory: ProxyManager.Factory,
    private val enforcer: ThreadEnforcer,
    private val clientEraser: ClientEraser,
    private val startedClients: StartedClients,
    private val shutdownBus: EventBus<ServerShutdownEvent>,
    private val appEnvironment: AppDevEnvironment,
    private val preferences: ProxyPreferences,
    status: ProxyStatus,
) : BaseServer(status), SharedProxy {

  private val overallState =
      MutableStateFlow(
          ProxyState(
              http = false,
              socks = false,
              netty = false,
          )
      )

  private fun adjustState(type: SharedProxy.Type, ready: Boolean) {
    overallState.update { s ->
      when (type) {
        SharedProxy.Type.HTTP -> s.copy(http = ready)
        SharedProxy.Type.SOCKS -> s.copy(socks = ready)
        SharedProxy.Type.NETTY -> s.copy(netty = ready)
      }
    }
  }

  private fun readyState(type: SharedProxy.Type) {
    adjustState(type, ready = true)
  }

  private fun unreadyState(type: SharedProxy.Type) {
    adjustState(type, ready = false)
  }

  private fun resetState() {
    overallState.update { it.copy(http = false, socks = false, netty = false) }
  }

  private suspend fun shutdownProxyServerWithCause(e: Throwable) {
    reset()
    status.set(RunningStatus.ProxyError(e))
    shutdownBus.emit(ServerShutdownEvent(throwable = e))
  }

  private suspend fun handleServerLoopError(e: Throwable, type: SharedProxy.Type) {
    Timber.e(e) { "Error running server loop: ${type.name}" }
    shutdownProxyServerWithCause(e)
  }

  private suspend fun beginProxyLoop(
      type: SharedProxy.Type,
      lock: Locker.Lock,
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
  ) {
    enforcer.assertOffMainThread()

    try {
      Timber.d { "${type.name} Begin proxy server loop: $info" }
      factory
          .create(
              type = type,
              info = info,
              socketCreator = socketCreator,
              serverDispatcher = serverDispatcher,
          )
          .loop(
              lock = lock,
              onOpened = { readyState(type) },
              onClosing = {
                // Closing, we mark as stopping early
                status.set(RunningStatus.Stopping)
                unreadyState(type)
              },
              onClosed = {
                Timber.d { "Proxy Server is Done!" }
                broadcastProxyStop()
              },
              onError = { e -> e.ifNotCancellation { handleServerLoopError(e = e, type = type) } },
          )
    } catch (e: Throwable) {
      e.ifNotCancellation { handleServerLoopError(e = e, type = type) }
    }
  }

  private suspend fun proxyLoop(
      scope: CoroutineScope,
      lock: Locker.Lock,
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
  ) {
    val fakeError = appEnvironment.isProxyFakeError
    if (fakeError.first()) {
      Timber.w { "DEBUG forcing Fake Proxy Error" }
      status.set(RunningStatus.ProxyError(RuntimeException("DEBUG: Force Fake Proxy Error")))
      return
    }

    // TODO netty preference
    val isNettyEnabled = IS_NETTY_ENABLED
    val isHttpEnabled = preferences.listenForHttpEnabledChanges().first()
    val isSocksEnabled = preferences.listenForSocksEnabledChanges().first()

    if (isNettyEnabled) {
      scope.launch(context = Dispatchers.Default) {
        beginProxyLoop(
            type = SharedProxy.Type.NETTY,
            lock = lock,
            info = info,
            socketCreator = socketCreator,
            serverDispatcher = serverDispatcher,
        )
      }
    } else {
      if (isHttpEnabled) {
        scope.launch(context = Dispatchers.Default) {
          beginProxyLoop(
              type = SharedProxy.Type.HTTP,
              lock = lock,
              info = info,
              socketCreator = socketCreator,
              serverDispatcher = serverDispatcher,
          )
        }
      }

      if (isSocksEnabled) {
        scope.launch(context = Dispatchers.Default) {
          beginProxyLoop(
              type = SharedProxy.Type.SOCKS,
              lock = lock,
              info = info,
              socketCreator = socketCreator,
              serverDispatcher = serverDispatcher,
          )
        }
      }
    }

    if (!isHttpEnabled && !isSocksEnabled) {
      Timber.w { "Cannot run proxy. HTTP and SOCKS both disabled" }
      status.set(
          RunningStatus.ProxyError(RuntimeException("Must enable either HTTP or SOCKS server"))
      )
    }
  }

  private fun reset() {
    enforcer.assertOffMainThread()

    clientEraser.clear()
    resetState()
  }

  private fun broadcastProxyStop() {
    enforcer.assertOffMainThread()

    // Update status if we were running
    if (status.get() is RunningStatus.Running) {
      status.set(RunningStatus.Stopping)
    }

    reset()
    status.set(RunningStatus.NotRunning)
  }

  private fun CoroutineScope.watchServerReadyStatus() {
    // When all proxy bits declare they are ready, the proxy status is "ready"
    overallState
        .map { it.isReady(preferences) }
        .filter { it }
        .also { f ->
          launch(context = Dispatchers.Default) {
            f.collect { ready ->
              if (ready) {
                Timber.d { "Proxy has fully launched, update status!" }
                status.set(RunningStatus.Running)
              }
            }
          }
        }
  }

  private suspend fun startServer(
      lock: Locker.Lock,
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
  ) {
    try {
      // Launch a new scope so this function won't proceed to finally block until the scope is
      // completed/cancelled
      //
      // This will suspend until the proxy server loop dies
      coroutineScope {
        // Mark proxy launching
        Timber.d { "Starting proxy server ..." }
        status.set(RunningStatus.Starting, clearError = true)

        watchServerReadyStatus()

        // Notify the client connection watcher that we have started
        launch(context = Dispatchers.Default) { startedClients.started() }

        // Start the proxy server loop
        launch(context = Dispatchers.Default) {
          proxyLoop(
              scope = this,
              lock = lock,
              info = info,
              socketCreator = socketCreator,
              serverDispatcher = serverDispatcher,
          )
        }
      }
    } finally {
      Timber.d { "Stopped Proxy Server" }
    }
  }

  private suspend fun Job.stopProxyLoop() {
    status.set(RunningStatus.Stopping)
    cancelAndJoin()
  }

  override suspend fun start(
      lock: Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  ) =
      withContext(context = Dispatchers.IO) {
        // Scope local
        val mutex = Mutex()

        var proxyJob: Job? = null
        var killTimerJob: Job? = null

        // Create the server dispatcher here that future proxy bits will use
        val serverDispatcher = serverDispatcherFactory.create()

        // Create the socket creator for socket connections
        val socketCreator =
            SocketCreator.create(
                appScope = appScope,
                appEnvironment = appEnvironment,
                dispatcher = serverDispatcher,
            )

        // Watch the connection status
        try {
          // Launch a new scope so this function won't proceed to finally block until the scope is
          // completed/cancelled
          //
          // This will suspend until the proxy server loop dies
          coroutineScope {

            // Watch the connection status for valid info
            connectionStatus.distinctUntilChanged().collect { info ->
              when (info) {
                is BroadcastNetworkStatus.ConnectionInfo.Connected -> {
                  // Connected is good, we can launch
                  // This will re-launch any time the connection info changes

                  mutex.withLock {
                    // Kill timer, we have something now
                    killTimerJob?.cancelAndJoin()
                    killTimerJob = null

                    proxyJob?.stopProxyLoop()
                    proxyJob = null
                  }

                  // Reset old
                  reset()

                  mutex.withLock {

                    // Hold onto the job here so we can cancel it if we need to
                    proxyJob =
                        launch(context = Dispatchers.Default) {
                          startServer(
                              lock = lock,
                              info = info,
                              socketCreator = socketCreator,
                              serverDispatcher = serverDispatcher,
                          )
                        }
                  }
                }

                is BroadcastNetworkStatus.ConnectionInfo.Empty -> {
                  Timber.w { "Connection EMPTY, shut down Proxy" }

                  // Empty is missing the channel, bad
                  mutex.withLock {
                    proxyJob?.stopProxyLoop()
                    proxyJob = null
                  }

                  broadcastProxyStop()

                  // Mark us as starting
                  // don't clear errors if they exist
                  status.set(RunningStatus.Starting)

                  mutex.withLock {
                    // Assign kill timer when we first see EMPTY
                    if (killTimerJob == null) {
                      killTimerJob =
                          launch(context = Dispatchers.Default) {
                            delay(5.seconds)

                            Timber.w { "Connection has been EMPTY for too long!" }

                            // Stop the proxy again so the user can interact with UI
                            broadcastProxyStop()
                          }
                    }
                  }
                }

                is BroadcastNetworkStatus.ConnectionInfo.Error -> {
                  Timber.w { "Connection ERROR, shut down Proxy" }

                  // Error is bad, shut down the proxy
                  mutex.withLock {
                    // Kill timer, we are dead
                    killTimerJob?.cancelAndJoin()
                    killTimerJob = null

                    proxyJob?.stopProxyLoop()
                    proxyJob = null
                  }
                  broadcastProxyStop()
                }

                is BroadcastNetworkStatus.ConnectionInfo.Unchanged -> {
                  Timber.w { "UNCHANGED SHOULD NOT HAPPEN" }
                  shutdownProxyServerWithCause(UNCHANGED_SHOULD_NOT_HAPPEN_ERROR)
                }
              }
            }
          }
        } finally {
          withContext(context = NonCancellable) {
            Timber.d { "Shutting down proxy..." }

            // Kill proxy job
            mutex.withLock {
              killTimerJob?.cancelAndJoin()
              proxyJob?.stopProxyLoop()
            }

            // Stop dispatcher looper
            serverDispatcher.shutdown()

            // We will then await the onClosed() event
            // but it may never come if the proxy server is in the ERROR state,
            // so in that case fire it ourselves.
            if (overallState.value.isReady(preferences)) {
              Timber.d { "Awaiting proxy server close event..." }
            } else {
              Timber.d { "Manually firing Proxy CLOSE event!" }
              broadcastProxyStop()
            }
          }
        }
      }

  private data class ProxyState(val http: Boolean, val socks: Boolean, val netty: Boolean) {

    @CheckResult
    suspend fun isReady(preferences: ProxyPreferences): Boolean {
      // TODO netty preference
      val isNettyEnabled = IS_NETTY_ENABLED
      if (isNettyEnabled) {
        if (netty) {
          return true
        }
      }

      val isHttpEnabled = preferences.listenForHttpEnabledChanges().first()
      if (isHttpEnabled) {
        if (!http) {
          return false
        }
      }

      val isSocksEnabled = preferences.listenForSocksEnabledChanges().first()
      if (isSocksEnabled) {
        if (!socks) {
          return false
        }
      }

      return true
    }
  }

  companion object {

    private const val IS_NETTY_ENABLED = true

    private val UNCHANGED_SHOULD_NOT_HAPPEN_ERROR =
        AssertionError("ConnectionInfo.Unchanged should never escape the server-module internals.")
  }
}
