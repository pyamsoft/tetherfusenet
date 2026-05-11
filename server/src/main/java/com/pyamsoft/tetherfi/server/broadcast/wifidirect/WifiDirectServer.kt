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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.server.broadcast.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import android.os.Looper
import androidx.annotation.CheckResult
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastServerImplementation
import com.pyamsoft.tetherfi.server.broadcast.DelegatingBroadcastServer
import com.pyamsoft.tetherfi.server.lock.Locker
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
internal class WifiDirectServer
@Inject
internal constructor(
    @param:ServerInternalApi private val config: WiDiConfig,
    @param:ServerInternalApi private val register: WifiDirectRegister,
    private val appContext: Context,
    private val appEnvironment: AppDevEnvironment,
    private val enforcer: ThreadEnforcer,
) : BroadcastServerImplementation<Channel> {

  private val wifiP2PManager by lazy {
    appContext.getSystemService<WifiP2pManager>().requireNotNull()
  }

  @SuppressLint("MissingPermission")
  private fun createGroupQ(
      channel: Channel,
      config: WifiP2pConfig,
      listener: WifiP2pManager.ActionListener,
  ) {
    if (ServerDefaults.canUseCustomConfig()) {
      wifiP2PManager.createGroup(
          channel,
          config,
          listener,
      )
    } else {
      throw IllegalStateException("Called createGroupQ but not Q: ${Build.VERSION.SDK_INT}")
    }
  }

  private fun doCleanupGroup(channel: Channel, onCleanupComplete: () -> Unit) {
    wifiP2PManager.cancelConnect(
        channel,
        object : WifiP2pManager.ActionListener {

          private fun doRemoveGroup() {
            wifiP2PManager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                  override fun onSuccess() {
                    Timber.d { "Wifi P2P Channel is removed" }
                    onCleanupComplete()
                  }

                  override fun onFailure(reason: Int) {
                    val r = WiFiDirectError.Reason.parseReason(reason)
                    Timber.w { "Failed to stop network: ${r.displayReason}" }
                    onCleanupComplete()
                  }
                },
            )
          }

          override fun onSuccess() {
            Timber.d { "Wifi P2P connection canceled" }
            doRemoveGroup()
          }

          override fun onFailure(reason: Int) {
            val r = WiFiDirectError.Reason.parseReason(reason)
            Timber.w { "Failed to cancel Wifi P2P connections ${r.displayReason}" }
            doRemoveGroup()
          }
        },
    )
  }

  @CheckResult
  private suspend fun removeGroup(channel: Channel) {
    enforcer.assertOffMainThread()

    Timber.d { "Stop existing WiFi Group" }
    return suspendCancellableCoroutine { cont -> doCleanupGroup(channel) { cont.resume(Unit) } }
  }

  @CheckResult
  @SuppressLint("MissingPermission")
  private suspend fun resolveCurrentGroup(channel: Channel): WifiP2pGroup? {
    enforcer.assertOffMainThread()

    return suspendCancellableCoroutine { cont ->
      try {
        wifiP2PManager.requestGroupInfo(channel) {
          // We are still on the Main Thread here, so don't unpack anything yet.
          cont.resume(it)
        }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "Error getting WiFi Direct Group Info" }
        cont.resumeWithException(e)
      }
    }
  }

  @CheckResult
  private suspend fun resolveConnectionInfo(channel: Channel): WifiP2pInfo? {
    enforcer.assertOffMainThread()

    return suspendCancellableCoroutine { cont ->
      try {
        wifiP2PManager.requestConnectionInfo(channel) {
          // We are still on the Main Thread here, so don't unpack anything yet.
          cont.resume(it)
        }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "Error getting WiFi Direct Connection Info" }
        cont.resumeWithException(e)
      }
    }
  }

  @CheckResult
  private fun createChannel(): Channel? {
    enforcer.assertOffMainThread()

    Timber.d { "Creating WifiP2PManager Channel" }

    // This can return null if initialization fails
    return wifiP2PManager.initialize(
        appContext,
        Looper.getMainLooper(),
    ) {
      // Before we used to kill the Network
      //
      // But now we do nothing - if you Swipe Away the app from recents,
      // the p2p manager will die, but when it comes back we want everything to
      // attempt to run again so we leave this around.
      //
      // Any other unexpected death like Airplane mode or Wifi off should be covered by the receiver
      // so we should never unintentionally leak the service
      Timber.d { "WifiP2PManager Channel died! Do nothing :D" }
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun tryConnectChannel(channel: Channel) {
    enforcer.assertOffMainThread()

    Timber.d { "Creating new wifi p2p group" }
    val conf = config.getConfiguration()

    val fakeError = appEnvironment.isBroadcastFakeError
    val isFakeError = fakeError.first()

    return suspendCancellableCoroutine { cont ->
      val listener =
          object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
              Timber.d { "New network created" }

              if (isFakeError) {
                Timber.w { "DEBUG forcing Fake Broadcast Error" }
                cont.resumeWithException(RuntimeException("DEBUG: Force Fake Broadcast Error"))
              } else {
                cont.resume(Unit)
              }
            }

            override fun onFailure(reason: Int) {
              val r = WiFiDirectError.Reason.parseReason(reason)
              val e =
                  if (r is WiFiDirectError.Reason.Busy) {
                    WifiP2PBusyTryAgainException()
                  } else {
                    RuntimeException("Broadcast Error: ${r.displayReason}")
                  }
              Timber.e(e) { "Unable to create Wifi Direct Group" }
              cont.resumeWithException(e)
            }
          }

      if (conf != null) {
        createGroupQ(channel, conf, listener)
      } else {
        wifiP2PManager.createGroup(
            channel,
            listener,
        )
      }
    }
  }

  private suspend fun connectChannel(channel: Channel) {
    var lastException: Throwable? = null

    // Try to connect the channel a few times
    //
    // If we fail because we are "busy" try again
    // otherwise, fail out with the error
    for (attempt in 1..MAX_BUSY_RETRIES) {
      try {
        return tryConnectChannel(channel)
      } catch (e: WifiP2PBusyTryAgainException) {
        lastException = e
        if (attempt < MAX_BUSY_RETRIES) {
          Timber.w(e) { "Wi-Fi Direct busy (attempt ${attempt}/${MAX_BUSY_RETRIES}), retrying" }
          delay(BUSY_RETRY_DELAY)
        }
      } catch (e: CancellationException) {
        // Create was canceled, clean up anything and rethrow
        try {
          removeGroup(channel)
        } finally {
          throw e
        }
      }
    }

    // Throw exception IF held, otherwise exception will be thrown after cleanup is done
    lastException?.also { throw it }
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
    val group = resolveCurrentGroup(channel)
    if (group != null && !config.matchesGroup(group.networkName, group.passphrase)) {
      Timber.w { "Existing group does not match current preferences, forcing recreation" }
      return false
    }

    return true
  }

  override suspend fun withLockStartBroadcast(
      updateNetworkInfo: suspend (Channel) -> DelegatingBroadcastServer.UpdateResult
  ): Channel {
    val channel = createChannel()
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
        removeGroup(channel)

        connectChannel(channel)
        Timber.d { "New Wi-Fi group connection created!" }
      }
    } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
      e.ifNotCancellation {
        Timber.e(e) { "Failed to connect Wi-Fi direct group" }
        throw e
      }
    }

    return channel
  }

  override suspend fun withLockStopBroadcast(source: Channel) {
    // This may fail if WiFi is off, but that's fine since if WiFi is off,
    // the system has already cleaned us up.
    removeGroup(source)

    // Close the wifi channel now that we are done with it
    Timber.d { "Close WiFiP2PManager channel" }
    closeSilent(source)
  }

  override suspend fun resolveCurrentConnectionInfo(
      source: Channel
  ): BroadcastNetworkStatus.ConnectionInfo {
    val info = resolveConnectionInfo(source)
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
    val group = resolveCurrentGroup(source)
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
    scope.launch(context = Dispatchers.Default) { register.register() }
  }

  class WifiDirectChannelCreationException :
      RuntimeException("Unable to create Wi-Fi Direct Channel")

  /** Continue trying to reconnect to Wi-Fi P2P if we receive a busy signal */
  private class WifiP2PBusyTryAgainException : RuntimeException("Wi-Fi Direct is Busy")

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
