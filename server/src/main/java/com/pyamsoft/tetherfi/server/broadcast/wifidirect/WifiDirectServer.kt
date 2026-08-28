/*
 * Copyright 2026 pyamsoft
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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.server.broadcast.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreMaxLineLength
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.LintIgnoreThrowsCount
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastServerImplementation
import com.pyamsoft.tetherfi.server.broadcast.DelegatingBroadcastServer
import com.pyamsoft.tetherfi.server.lock.Locker
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
internal class WifiDirectServer
@Inject
internal constructor(
    @param:ServerInternalApi private val config: WiDiConfig,
    @param:ServerInternalApi private val register: WifiDirectRegister,
    private val wiFiP2PManager: SuspendingWiFiP2PManager,
    private val enforcer: ThreadEnforcer,
    private val dispatchers: AppDispatchers,
) : BroadcastServerImplementation<Channel> {

  private val mutex = Mutex()

  private data class WiFiDirectInternalTeardownResult(
      val errorReasons: List<WiFiDirectError.Reason>,
      val success: Boolean,
      val attempts: Int,
  )

  private data class WiFiDirectSubsystemTeardownResult(
      val success: Boolean,
      val internalAttempts: Int,
      val disconnectAttempts: Int,
  )

  private data class WiFiDirectOverallTeardownResult(
      val success: Boolean,
      val group: WiFiDirectSubsystemTeardownResult,
  )

  @CheckResult
  private suspend inline fun doInternalTeardown(
      channel: Channel,
      subsystemName: String,
      onTeardown: (Channel) -> WiFiDirectError.Reason?,
      onLog: (Boolean, Int, List<WiFiDirectError.Reason>) -> Unit,
  ): WiFiDirectInternalTeardownResult {
    enforcer.assertOffMainThread()
    var backoffTime = BUSY_RETRY_DELAY
    val reasons = mutableListOf<WiFiDirectError.Reason>()
    for (i in 1..MAX_BUSY_RETRIES) {
      Timber.d { "Attempt $i for internal teardown: $subsystemName" }
      val result = onTeardown(channel)
      if (result == null) {
        // We have canceled!
        onLog(true, i, reasons)
        return WiFiDirectInternalTeardownResult(
            errorReasons = reasons,
            attempts = i,
            success = true,
        )
      }

      // Otherwise we failed to cleanup
      reasons.add(result)

      // Wait before trying again
      delay(backoffTime)

      // Double backoff
      backoffTime *= 2
    }

    // Ultimately failed
    onLog(false, MAX_BUSY_RETRIES, reasons)
    return WiFiDirectInternalTeardownResult(
        errorReasons = reasons,
        attempts = MAX_BUSY_RETRIES,
        success = false,
    )
  }

  @CheckResult
  private suspend fun removeGroup(channel: Channel): WiFiDirectInternalTeardownResult =
      doInternalTeardown(
          channel = channel,
          subsystemName = "WiFiP2PManager.Group",
          onTeardown = { wiFiP2PManager.removeGroup(it) },
          onLog = { success, attempts, reasons ->
            if (success) {
              Timber.d {
                "Wi-Fi direct group was removed after $attempts attempts reasons=$reasons"
              }
            } else {
              Timber.w {
                "Wi-Fi direct group was not removed after $attempts attempts. reasons=$reasons"
              }
            }
          },
      )

  @CheckResult
  private suspend inline fun <reified T : Any> doSubsystemTeardown(
      channel: Channel,
      force: Boolean,
      onRemoveSubsystem: (Channel) -> WiFiDirectInternalTeardownResult,
      onResolveSubsystemCurrent: (Channel) -> T?,
  ): WiFiDirectSubsystemTeardownResult {
    enforcer.assertOffMainThread()

    val subsystemTag = "WiFi Direct ${T::class.java.simpleName}"

    // IF we are not forced to attempt a runthrough check
    // we can fast path this by just looking if we have an existing subsystem connection
    if (!force) {
      val existingSubsystem = onResolveSubsystemCurrent(channel)
      if (existingSubsystem == null) {
        // No existing connection, we never stood up
        Timber.d {
          "$subsystemTag did not have a live subsystem connection. Ignore teardown request."
        }
        return WiFiDirectSubsystemTeardownResult(
            success = true,
            internalAttempts = 0,
            disconnectAttempts = 0,
        )
      }
    }

    // First we remove the subsystem
    Timber.d { "$subsystemTag Attempt teardown" }
    val cleanupResult = onRemoveSubsystem(channel)

    // If we failed to actually remove the group, that's its own problem
    if (!cleanupResult.success) {
      Timber.w {
        "$subsystemTag failed to fully internally tear down. internal=${cleanupResult.attempts}"
      }
      return WiFiDirectSubsystemTeardownResult(
          success = false,
          internalAttempts = cleanupResult.attempts,
          // We never got to the disconnect check loop
          disconnectAttempts = 0,
      )
    }

    Timber.d { "$subsystemTag Teardown reports success, await full closure..." }

    // According to Android source code, the listener for group removal may have given back SUCCESS
    // but the subsystem isn't actually dead until the group info reports null
    var backoffTime = BUSY_RETRY_DELAY
    for (attempt in 1..MAX_BUSY_RETRIES) {
      val subsystem = onResolveSubsystemCurrent(channel)
      if (subsystem == null) {
        // Subsystem is gone, we are, according to the subsystem, fully torn down
        Timber.d {
          "$subsystemTag is fully torn down. internal=${cleanupResult.attempts} overall=$attempt"
        }
        return WiFiDirectSubsystemTeardownResult(
            success = true,
            internalAttempts = cleanupResult.attempts,
            disconnectAttempts = attempt,
        )
      }

      // Wait before trying again
      delay(backoffTime)

      // Double backoff
      backoffTime *= 2
    }

    // Failed to fully teardown after so many attempts
    Timber.w {
      "$subsystemTag failed to fully tear down. internal=${cleanupResult.attempts} overall=$MAX_BUSY_RETRIES"
    }
    return WiFiDirectSubsystemTeardownResult(
        success = false,
        internalAttempts = cleanupResult.attempts,
        disconnectAttempts = MAX_BUSY_RETRIES,
    )
  }

  @CheckResult
  private suspend fun doGroupSubsystemTeardown(
      channel: Channel,
      force: Boolean,
  ): WiFiDirectSubsystemTeardownResult =
      doSubsystemTeardown(
          channel = channel,
          force = force,
          onRemoveSubsystem = { removeGroup(it) },
          onResolveSubsystemCurrent = { wiFiP2PManager.requestGroupInfo(it) },
      )

  @CheckResult
  private suspend fun doFullWifiP2PTeardown(
      channel: Channel,
      force: Boolean,
  ): WiFiDirectOverallTeardownResult {
    val group = doGroupSubsystemTeardown(channel, force)

    return WiFiDirectOverallTeardownResult(
        success = group.success,
        group = group,
    )
  }

  private suspend fun createGroup(channel: Channel) {
    // Try to connect the channel a few times
    //
    // If we fail because we are "busy" try again
    // otherwise, fail out with the error
    var backoffTime = BUSY_RETRY_DELAY
    val reasons = mutableListOf<Exception>()
    for (attempt in 1..MAX_BUSY_RETRIES) {
      try {
        return wiFiP2PManager.createGroup(channel)
      } catch (e: CancellationException) {
        // Create was canceled, clean up anything and rethrow
        // Force attempt a subsystem teardown
        val result = doFullWifiP2PTeardown(channel, force = true)
        if (!result.success) {
          Timber.w {
            "Failed to fully teardown Wi-Fi direct upon connectChannel() coroutine cancel. result=$result"
          }
        }

        // Re-throw the cancellation exception
        throw e
      } catch (@LintIgnoreTooGenericExceptionCaught e: Exception) {
        // Anything else that could go wrong
        reasons.add(e)

        if (attempt < MAX_BUSY_RETRIES) {
          Timber.w(e) { "Wi-Fi Direct error (attempt ${attempt}/${MAX_BUSY_RETRIES}), retrying" }

          // Wait before trying again
          delay(backoffTime)

          // Double backoff
          backoffTime *= 2
        }
      }
    }

    // Throw exception IF held
    // otherwise there is either NO exception
    // or a CancellationException which is already re-thrown above
    if (reasons.isNotEmpty()) {
      throw WifiP2PExceptionCollection(reasons)
    }
  }

  @CheckResult
  private suspend fun attemptReUseConnection(
      channel: Channel,
      updateNetworkInfo: suspend (Channel) -> DelegatingBroadcastServer.UpdateResult,
  ): Boolean {
    // Sometimes, if the system has not closed down the Wifi group (because an old version of the
    // app made a group and a new one was then installed before the group was shut down) we can
    // re-use the existing group info.
    //
    // This is generally a speed win and so we take it.
    val result = updateNetworkInfo(channel)

    if (!result.connection || !result.group) {
      Timber.w { "Existing network info missing connection OR group, force recreation" }
      return false
    }

    // Verify the existing group matches current user preferences (SSID/password).
    // If they differ, tear down the stale group so a new one is created.
    val group = wiFiP2PManager.requestGroupInfo(channel)
    if (group != null && !config.matchesGroup(group.networkName, group.passphrase)) {
      Timber.w { "Existing group does not match current preferences, forcing recreation" }
      return false
    }

    return true
  }

  @LintIgnoreThrowsCount
  override suspend fun withLockStartBroadcast(
      updateNetworkInfo: suspend (Channel) -> DelegatingBroadcastServer.UpdateResult
  ): Channel {
    enforcer.assertOffMainThread()

    // Claim an internal mutex so we don't field duplicate or parallel requests
    return mutex.withLock {
      val channel = wiFiP2PManager.createChannel()
      if (channel == null) {
        Timber.w { "Failed to create a Wi-Fi direct channel" }
        throw WifiDirectChannelCreationException()
      }

      try {
        Timber.d { "Attempt open connection with channel" }
        if (
            attemptReUseConnection(
                channel = channel,
                updateNetworkInfo = updateNetworkInfo,
            )
        ) {
          Timber.d { "Existing Wi-Fi group connection was re-used!" }
        } else {
          Timber.d { "Cannot re-use Wi-Fi group connection, make new one" }

          // Kill old channel
          // If no old channel exists, we can ignore this teardown attempt
          val fullTeardownResult = doFullWifiP2PTeardown(channel, force = false)
          if (!fullTeardownResult.success) {
            Timber.w {
              @LintIgnoreMaxLineLength
              "Failed to fully teardown old Wi-Fi direct connection, YOLO result=$fullTeardownResult"
            }

            // Do not throw here since it seems like if there is no previous group actually hanging
            // around
            // then this is "expected" that the subsystem keeps returning Busy
            //
            // in the event this is a real subsystem error, the createGroup line would fail anyway
          }

          createGroup(channel)
          Timber.d { "New Wi-Fi group connection created!" }
        }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        e.ifNotCancellation {
          Timber.e(e) { "Failed to connect Wi-Fi direct group" }

          // The channel was never returned to the caller, so close it here or it leaks.
          closeSilent(channel)

          throw e
        }
      }

      return@withLock channel
    }
  }

  override suspend fun withLockStopBroadcast(source: Channel) {
    enforcer.assertOffMainThread()

    return mutex.withLock {
      // This may fail if WiFi is off, but that's fine since if WiFi is off,
      // the system has already cleaned us up.
      //
      // We must attempt a subsystem teardown
      val fullTeardownResult = doFullWifiP2PTeardown(channel = source, force = true)
      if (!fullTeardownResult.success) {
        Timber.w {
          "Failed to fully teardown Wi-Fi direct connection when stopping broadcast. result=$fullTeardownResult"
        }
      }

      // Close the wifi channel now that we are done with it
      Timber.d { "Close WiFiP2PManager channel" }
      closeSilent(source)
    }
  }

  override suspend fun resolveCurrentConnectionInfo(
      source: Channel
  ): BroadcastNetworkStatus.ConnectionInfo {
    enforcer.assertOffMainThread()

    val info = wiFiP2PManager.requestConnectionInfo(source)
    val host = info?.groupOwnerAddress

    return if (host == null) {
      BroadcastNetworkStatus.ConnectionInfo.Error(
          error = IllegalStateException("WiFi Direct did not return Connection Info"),
      )
    } else {
      BroadcastNetworkStatus.ConnectionInfo.Connected(
          hostName = host.hostAddress.orEmpty(),
      )
    }
  }

  /** This is only available in Android 35+ */
  @CheckResult
  private fun resolveP2PDeviceIpAddress(device: WifiP2pDevice): InetAddress? {
    return if (Build.VERSION.SDK_INT >= WIFI_P2P_DEVICE_IP_AVAILABLE_API) {
      device.ipAddress
    } else {
      Timber.d {
        "P2P device IP address unavailable on API < ${WIFI_P2P_DEVICE_IP_AVAILABLE_API}; skipping ${device.deviceName}"
      }
      null
    }
  }

  override suspend fun resolveCurrentGroupInfo(source: Channel): BroadcastNetworkStatus.GroupInfo {
    enforcer.assertOffMainThread()
    val group = wiFiP2PManager.requestGroupInfo(channel = source)

    return if (group == null) {
      BroadcastNetworkStatus.GroupInfo.Error(
          error = IllegalStateException("WiFi Direct did not return Group Info"),
      )
    } else {
      BroadcastNetworkStatus.GroupInfo.Connected(
          ssid = group.networkName,
          password = group.passphrase,
          clients =
              group.clientList.orEmpty().mapNotNull { client ->
                val ipAddressInStringFormat =
                    resolveP2PDeviceIpAddress(client)?.hostAddress ?: return@mapNotNull null

                BroadcastNetworkStatus.GroupInfo.Connected.Device(
                    name = client.deviceName,
                    ipAddress = ipAddressInStringFormat,
                )
              },
      )
    }
  }

  override fun onNetworkStarted(
      scope: CoroutineScope,
      lock: Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  ) {
    scope.launch(context = dispatchers.default) { register.register() }
  }

  class WifiDirectChannelCreationException :
      RuntimeException("Unable to create Wi-Fi Direct Channel")

  class WifiP2PExceptionCollection(reasons: Collection<Exception>) :
      RuntimeException("Unable to start Wi-Fi Direct reasons=$reasons")

  companion object {

    private const val WIFI_P2P_DEVICE_IP_AVAILABLE_API = Build.VERSION_CODES.VANILLA_ICE_CREAM

    private const val CHANNEL_CLOSE_SUPPORTED_API = Build.VERSION_CODES.O_MR1

    // Try up to a few times just in case (can have weird behavior on vendor skins like MIUI)
    private const val MAX_BUSY_RETRIES = 3

    // Wait just a little bit between tries for the Wi-Fi Direct to settl
    private val BUSY_RETRY_DELAY = 500.milliseconds

    @JvmStatic
    private fun closeSilent(s: Channel) {
      if (Build.VERSION.SDK_INT >= CHANNEL_CLOSE_SUPPORTED_API) {
        try {
          s.close()
        } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
          Timber.e(e) { "Failed to close WifiP2P Channel" }
        }
      } else {
        Timber.w {
          "Cannot close WifiP2P Channel on API < ${CHANNEL_CLOSE_SUPPORTED_API}; skipping"
        }
      }
    }
  }
}
