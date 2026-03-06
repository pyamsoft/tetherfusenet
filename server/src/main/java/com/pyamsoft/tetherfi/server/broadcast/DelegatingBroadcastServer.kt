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

package com.pyamsoft.tetherfi.server.broadcast

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.BaseServer
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.broadcast.rndis.RNDISServer
import com.pyamsoft.tetherfi.server.broadcast.wifidirect.WifiDirectServer
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuard
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

typealias ServerDataType = Any

private val DEBUG_EMPTY_CLIENT_LIST: Collection<BroadcastNetworkStatus.GroupInfo.Connected.Device> =
    emptySet()

@Singleton
internal class DelegatingBroadcastServer
@Inject
internal constructor(
    private val proxy: SharedProxy,
    private val expertPreferences: ExpertPreferences,
    private val inAppRatingPreferences: InAppRatingPreferences,
    private val shutdownBus: EventBus<ServerShutdownEvent>,
    private val permissionGuard: PermissionGuard,
    private val appEnvironment: AppDevEnvironment,
    private val enforcer: ThreadEnforcer,
    private val clock: Clock,
    private val wifiDirectImplementation: WifiDirectServer,
    private val rndisImplementation: RNDISServer,
    status: BroadcastStatus,
) :
    BaseServer(status),
    BroadcastNetwork,
    BroadcastNetworkStatus,
    BroadcastNetworkUpdater,
    BroadcastServerImplementation<ServerDataType> {

  data class UpdateResult(val group: Boolean, val connection: Boolean)

  // On some devices, refreshing channel info too frequently leads to errors
  private val groupInfoChannel =
      MutableStateFlow<BroadcastNetworkStatus.GroupInfo>(BroadcastNetworkStatus.GroupInfo.Empty)
  private var lastGroupRefreshTime = LocalDateTime.MIN

  // On some devices, refreshing channel info too frequently leads to errors
  private val connectionInfoChannel =
      MutableStateFlow<BroadcastNetworkStatus.ConnectionInfo>(
          BroadcastNetworkStatus.ConnectionInfo.Empty
      )

  private var lastConnectionRefreshTime = LocalDateTime.MIN

  private val mutex = Mutex()
  private var proxyJob: Job? = null
  private var heldSource: ServerDataType? = null

  private suspend fun withLockInitializeNetwork(source: ServerDataType) =
      withContext(context = Dispatchers.Default) {
        enforcer.assertOffMainThread()

        // Make sure the network is up-to-date before starting the rest of the proxy
        // Need to delay slightly or else connection info does not resolve correctly
        delay(INITIALIZATION_DELAY)

        // Using the lock we already have, force check for updated info
        withLockUpdateNetworkInfo(
            source = source,
            // Force a check for the latest information to see if we can re-use the existing
            // connection
            strategy = NetworkUpdateStrategy.FORCE_CHECK_LATEST,
        )
      }

  private suspend fun startNetwork(lock: Locker.Lock) =
      withContext(context = Dispatchers.Default) {
        enforcer.assertOffMainThread()

        // Mark starting
        status.set(RunningStatus.Starting, clearError = true)

        // Kill the old proxy
        killProxyJob()

        Timber.d { "START NEW NETWORK" }

        if (!permissionGuard.canCreateNetwork()) {
          Timber.w { "Missing permissions for making network" }
          val e = RuntimeException("Missing required Permissions")
          shutdownForStatus(RunningStatus.HotspotError(e), clearErrorStatus = false)
          return@withContext
        }

        var launchProxy = false
        mutex.withLock {
          try {
            Timber.d { "Starting broadcast network" }
            val source = withLockStartBroadcast { source ->
              // We MAY be able to re-use the existing connection, but EVERYTHING MUST BE VALID
              withLockUpdateNetworkInfo(
                  source = source,
                  // We only reuse connection if everything has valid data
                  strategy = NetworkUpdateStrategy.UPDATE_ONLY_IF_ALL_CONNECTED,
              )
            }

            // Only store the channel if it successfully "finished" creating.
            Timber.d { "Network started, store data source: $source" }
            heldSource = source

            launchProxy = true

            withLockInitializeNetwork(source = source)
          } catch (e: Throwable) {
            e.ifNotCancellation {
              Timber.w { "Error during broadcast startup, stop network" }

              completeStop {
                Timber.w { "Stopping network after startup failed" }
                shutdownForStatus(RunningStatus.HotspotError(e), clearErrorStatus = false)
              }
            }
          }
        }

        // Run this code outside of the lock because we don't want the proxy loop to block the
        // rest of the lock

        // Do this outside of the lock, since this will run "forever"
        if (launchProxy) {
          val newProxyJob =
              launch(context = Dispatchers.IO) {
                onNetworkStarted(
                    scope = this,
                    lock = lock,
                    connectionStatus = connectionInfoChannel,
                )
              }
          Timber.d { "Track new proxy job!" }
          proxyJob = newProxyJob
        }
      }

  private inline fun completeStop(onStopped: () -> Unit) {
    enforcer.assertOffMainThread()

    Timber.d { "Reset last info refresh times" }
    lastGroupRefreshTime = LocalDateTime.MIN
    lastConnectionRefreshTime = LocalDateTime.MIN
    connectionInfoChannel.value = BroadcastNetworkStatus.ConnectionInfo.Empty
    groupInfoChannel.value = BroadcastNetworkStatus.GroupInfo.Empty

    onStopped()
  }

  private suspend fun shutdownWifiNetwork(source: ServerDataType) {
    enforcer.assertOffMainThread()

    Timber.d { "Stop broadcast server source" }
    withLockStopBroadcast(source)

    // Clear out so nobody else can use a dead channel
    heldSource = null
  }

  private suspend fun killProxyJob() {
    proxyJob?.also { p ->
      p.cancelAndJoin()
      Timber.d { "Stopped proxy job" }
    }
    proxyJob = null
  }

  private suspend fun stopNetwork(clearErrorStatus: Boolean) =
      withContext(context = Dispatchers.Default) {
        enforcer.assertOffMainThread()

        mutex.withLock {
          Timber.d { "STOP NETWORK" }

          // If we do have a channel, mark shutting down as we clean up
          Timber.d { "Shutting down network" }
          status.set(RunningStatus.Stopping)

          killProxyJob()

          // If we have no channel, we haven't started yet. Make sure we are clean, but this
          // is basically a no-op
          heldSource?.also { shutdownWifiNetwork(it) }

          completeStop {
            shutdownForStatus(RunningStatus.NotRunning, clearErrorStatus)
            Timber.d { "Network was stopped" }
          }
        }
      }

  @CheckResult
  private suspend fun handleGroupDebugEnvironment(): BroadcastNetworkStatus.GroupInfo? {
    enforcer.assertOffMainThread()

    val debugGroup = appEnvironment.fakeGroup
    if (debugGroup.isEmpty.first()) {
      Timber.w { "DEBUG forcing Empty group response" }
      return BroadcastNetworkStatus.GroupInfo.Empty
    }

    if (debugGroup.isError.first()) {
      Timber.w { "DEBUG forcing Error group response" }
      return BroadcastNetworkStatus.GroupInfo.Error(
          error = IllegalStateException("DEBUG FORCED ERROR RESPONSE")
      )
    }

    if (debugGroup.isConnected.first()) {
      Timber.w { "DEBUG forcing Connected group response" }
      return BroadcastNetworkStatus.GroupInfo.Connected(
          ssid = "DEBUG SSID",
          password = "DEBUG PASSWORD",
          clients = DEBUG_EMPTY_CLIENT_LIST,
      )
    }

    return null
  }

  @CheckResult
  private suspend fun withLockGetGroupInfo(
      source: ServerDataType?,
      allowDebugOverride: Boolean,
      force: Boolean,
  ): BroadcastNetworkStatus.GroupInfo {
    enforcer.assertOffMainThread()

    if (source == null) {
      Timber.w { "Cannot get group info without Wifi source" }
      return BroadcastNetworkStatus.GroupInfo.Empty
    }

    if (!permissionGuard.canCreateNetwork()) {
      Timber.w { "Missing permissions, cannot get Group Info" }
      return BroadcastNetworkStatus.GroupInfo.Empty
    }

    val now = LocalDateTime.now(clock)
    if (!force) {
      // If this is not a force request, we don't do any work unless we are outside of the refresh
      // window
      if (lastGroupRefreshTime.plusSeconds(REFRESH_DEBOUNCE) >= now) {
        return BroadcastNetworkStatus.GroupInfo.Unchanged
      }
    }

    // Regardless of what the system gives back, we "track" this time to avoid request spamming
    val result = resolveCurrentGroupInfo(source)
    lastGroupRefreshTime = now

    if (allowDebugOverride) {
      val forcedDebugResult = handleGroupDebugEnvironment()
      if (forcedDebugResult != null) {
        Timber.w { "Returning DEBUG result which overrides real: $result" }
        return forcedDebugResult
      }
    }

    return result
  }

  @CheckResult
  private suspend fun handleConnectionDebugEnvironment(): BroadcastNetworkStatus.ConnectionInfo? {
    enforcer.assertOffMainThread()

    val debugConnection = appEnvironment.fakeConnection
    if (debugConnection.isEmpty.first()) {
      Timber.w { "DEBUG forcing Empty connection response" }
      return BroadcastNetworkStatus.ConnectionInfo.Empty
    }

    if (debugConnection.isError.first()) {
      Timber.w { "DEBUG forcing Error connection response" }
      return BroadcastNetworkStatus.ConnectionInfo.Error(
          error = IllegalStateException("DEBUG FORCED ERROR RESPONSE")
      )
    }

    if (debugConnection.isConnected.first()) {
      Timber.w { "DEBUG forcing Connected connection response" }
      return BroadcastNetworkStatus.ConnectionInfo.Connected(hostName = "DEBUG HOSTNAME")
    }

    return null
  }

  @CheckResult
  private suspend fun withLockGetConnectionInfo(
      source: ServerDataType?,
      force: Boolean,
      allowDebugOverride: Boolean,
  ): BroadcastNetworkStatus.ConnectionInfo {
    enforcer.assertOffMainThread()

    if (source == null) {
      Timber.w { "Cannot get connection info without Wifi source" }
      return BroadcastNetworkStatus.ConnectionInfo.Empty
    }

    if (!permissionGuard.canCreateNetwork()) {
      Timber.w { "Missing permissions, cannot get Connection Info" }
      return BroadcastNetworkStatus.ConnectionInfo.Empty
    }

    val now = LocalDateTime.now(clock)
    if (!force) {
      // If this is not a force request, we don't do any work unless we are outside of the refresh
      // window
      if (lastConnectionRefreshTime.plusSeconds(REFRESH_DEBOUNCE) > now) {
        return BroadcastNetworkStatus.ConnectionInfo.Unchanged
      }
    }

    // Regardless of what the system gives back, we "track" this time to avoid request spamming
    val result = resolveCurrentConnectionInfo(source)
    lastConnectionRefreshTime = now

    if (allowDebugOverride) {
      val forcedDebugResult = handleConnectionDebugEnvironment()
      if (forcedDebugResult != null) {
        Timber.w { "Returning DEBUG result which overrides real: $result" }
        return forcedDebugResult
      }
    }

    return result
  }

  private suspend fun shutdownForStatus(newStatus: RunningStatus, clearErrorStatus: Boolean) {
    status.set(newStatus, clearErrorStatus)
    shutdownBus.emit(
        ServerShutdownEvent(
            throwable = if (newStatus is RunningStatus.Error) newStatus.throwable else null
        )
    )
  }

  private enum class NetworkUpdateStrategy {
    /** Artificially limit the frequency that the system can fire requests to WiFi direct */
    DEBOUNCE_FAST_REQUESTS,

    /** The response from the Wifi direct system will only be accepted if everything is CONNECTED */
    UPDATE_ONLY_IF_ALL_CONNECTED,

    /** Force a check for the latest information */
    FORCE_CHECK_LATEST,
  }

  /**
   * Attempt to update network info for Group and Connection
   *
   * At the point this function is run, we already claim the lock
   */
  @CheckResult
  private suspend fun withLockUpdateNetworkInfo(
      source: ServerDataType?,
      strategy: NetworkUpdateStrategy,
  ): UpdateResult =
      withContext(context = Dispatchers.Default) {
        enforcer.assertOffMainThread()

        // Always go to the system IF the strategy is one of our internal hooks
        val isForceCheckConnection = strategy != NetworkUpdateStrategy.DEBOUNCE_FAST_REQUESTS

        val onlyAcceptWhenAllConnected =
            strategy == NetworkUpdateStrategy.UPDATE_ONLY_IF_ALL_CONNECTED

        // Do not allow debug overriding if we are checking for an existing connection
        // we want to hit the actual backend
        val allowDebugOverride = strategy != NetworkUpdateStrategy.UPDATE_ONLY_IF_ALL_CONNECTED

        val groupInfo =
            withLockGetGroupInfo(
                source,
                force = isForceCheckConnection,
                allowDebugOverride = allowDebugOverride,
            )
        val connectionInfo =
            withLockGetConnectionInfo(
                source,
                force = isForceCheckConnection,
                allowDebugOverride = allowDebugOverride,
            )

        val acceptGroup: Boolean
        val acceptConnection: Boolean
        if (onlyAcceptWhenAllConnected) {
          acceptGroup = groupInfo is BroadcastNetworkStatus.GroupInfo.Connected
          acceptConnection = connectionInfo is BroadcastNetworkStatus.ConnectionInfo.Connected
        } else {
          acceptGroup = groupInfo != BroadcastNetworkStatus.GroupInfo.Unchanged
          acceptConnection = connectionInfo != BroadcastNetworkStatus.ConnectionInfo.Unchanged
        }

        if (onlyAcceptWhenAllConnected) {
          if (acceptGroup && acceptConnection) {
            Timber.d {
              "ALL_CONNECT Network info update accepted: GRP=$groupInfo CON=$connectionInfo"
            }
            groupInfoChannel.value = groupInfo
            connectionInfoChannel.value = connectionInfo
          } else {
            Timber.w {
              "ALL_CONNECT Network update not accepted: GRP=$groupInfo CON=$connectionInfo"
            }
          }
        } else {
          if (acceptGroup) {
            Timber.d { "Network update Group=$groupInfo" }
            groupInfoChannel.value = groupInfo
          } else {
            Timber.w { "Network update group not accepted: $groupInfo" }
          }

          if (acceptConnection) {
            Timber.d { "Network update Connection=$connectionInfo" }
            connectionInfoChannel.value = connectionInfo
          } else {
            Timber.w { "Network update connection not accepted: $groupInfo" }
          }
        }

        return@withContext UpdateResult(group = acceptGroup, connection = acceptConnection)
      }

  @CheckResult
  private suspend fun resolveImplementation(): BroadcastServerImplementation<Any> {
    val broadcastType = expertPreferences.listenForBroadcastType().first()
    val impl: ServerDataType =
        when (broadcastType) {
          BroadcastType.WIFI_DIRECT -> wifiDirectImplementation
          BroadcastType.RNDIS -> rndisImplementation
        }

    return impl.cast<BroadcastServerImplementation<Any>>().requireNotNull()
  }

  override suspend fun updateNetworkInfo() =
      withContext(context = Dispatchers.Default) {
        mutex.withLock {
          val source = heldSource

          Timber.d { "Attempt update network info with source $source" }

          withLockUpdateNetworkInfo(
              source = source,
              // Do not allow too many requests
              strategy = NetworkUpdateStrategy.DEBOUNCE_FAST_REQUESTS,
          )
        }

        // No return
        return@withContext
      }

  override suspend fun start(lock: Locker.Lock) =
      withContext(context = Dispatchers.Default) {
        enforcer.assertOffMainThread()

        if (status.get() is RunningStatus.Error) {
          Timber.w { "Reset network from error state" }
          stopNetwork(clearErrorStatus = true)
        }

        Timber.d { "Starting Wifi Network..." }
        try {
          // Launch a new scope so this function won't proceed to finally block until the scope is
          // completed/cancelled
          coroutineScope {
            // This will suspend until onNetworkStart proxy.start() completes,
            // which is suspended until the proxy server loop dies
            startNetwork(lock)
          }
        } catch (e: Throwable) {
          e.ifNotCancellation {
            Timber.e(e) { "Error starting Network" }
            shutdownForStatus(RunningStatus.HotspotError(e), clearErrorStatus = false)
          }
        } finally {
          withContext(context = NonCancellable) {
            Timber.d { "Stopping Wifi Network..." }
            stopNetwork(clearErrorStatus = false)
          }
        }
      }

  override fun onConnectionInfoChanged(): Flow<BroadcastNetworkStatus.ConnectionInfo> {
    return connectionInfoChannel
  }

  override fun onGroupInfoChanged(): Flow<BroadcastNetworkStatus.GroupInfo> {
    return groupInfoChannel
  }

  override suspend fun withLockStartBroadcast(
      updateNetworkInfo: suspend (Any) -> UpdateResult
  ): Any {
    return resolveImplementation().withLockStartBroadcast(updateNetworkInfo)
  }

  override suspend fun resolveCurrentConnectionInfo(
      source: Any
  ): BroadcastNetworkStatus.ConnectionInfo {
    return resolveImplementation().resolveCurrentConnectionInfo(source)
  }

  override suspend fun resolveCurrentGroupInfo(source: Any): BroadcastNetworkStatus.GroupInfo {
    return resolveImplementation().resolveCurrentGroupInfo(source)
  }

  override suspend fun withLockStopBroadcast(source: Any) {
    return resolveImplementation().withLockStopBroadcast(source)
  }

  override fun onNetworkStarted(
      scope: CoroutineScope,
      lock: Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  ) {
    // Need to mark the network as running so that the Proxy network can start
    Timber.d { "Broadcast server is fully set up!" }
    status.set(RunningStatus.Running)

    scope.launch(context = Dispatchers.Default) {
      resolveImplementation()
          .onNetworkStarted(scope = this, lock = lock, connectionStatus = connectionStatus)
    }
    scope.launch(context = Dispatchers.Default) { inAppRatingPreferences.markHotspotUsed() }
    scope.launch(context = Dispatchers.Default) {
      proxy.start(
          lock = lock,
          connectionStatus = connectionStatus,
      )
    }
  }

  companion object {

    /** Initialization needs to be delayed slightly or Connection info fails to resolve */
    private val INITIALIZATION_DELAY = 750.milliseconds

    /** Don't allow refresh unless forced within this window */
    private const val REFRESH_DEBOUNCE = 3L
  }
}
