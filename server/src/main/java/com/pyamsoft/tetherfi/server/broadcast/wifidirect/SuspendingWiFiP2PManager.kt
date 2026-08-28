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

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
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
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerInternalApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A simple abstraction over callback based WiFiP2PManager functions
 *
 * BEWARE: A listener returning a success or failure callback DOES NOT ACTUALLY MEAN THE SUBSYSTEM
 * IS SETTLED. According to Android source code, after seeing the callback you should check that the
 * Group or Connection info is null (if removing) or non-null (if connecting) before assuming you
 * are "ready" to use a delivered data piece.
 */
@Singleton
internal class SuspendingWiFiP2PManager
@Inject
internal constructor(
    @param:ServerInternalApi private val config: WiDiConfig,
    private val appContext: Context,
    private val appEnvironment: AppDevEnvironment,
    private val enforcer: ThreadEnforcer,
) {

  private val mutex = Mutex()

  private val wifiP2PManager by lazy {
    appContext.getSystemService<WifiP2pManager>().requireNotNull()
  }

  private fun createGroupQ(
      channel: Channel,
      config: WifiP2pConfig,
      listener: WifiP2pManager.ActionListener,
  ) {
    if (ServerDefaults.canUseCustomConfig()) {
      @SuppressLint("MissingPermission")
      wifiP2PManager.createGroup(
          channel,
          config,
          listener,
      )
    } else {
      throw IllegalStateException("Called createGroupQ but not Q: ${Build.VERSION.SDK_INT}")
    }
  }

  /**
   * BE VERY CAREFUL
   *
   * According to Android source code, after getting the callback we are NOT actually disconnected
   * until a request for groupInfo returns null group
   */
  @CheckResult
  suspend fun removeGroup(channel: Channel): WiFiDirectError.Reason? {
    enforcer.assertOffMainThread()

    return mutex.withLock {
      return@withLock suspendCancellableCoroutine { cont ->
        wifiP2PManager.removeGroup(
          channel,
          object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
              Timber.d { "Wifi P2P Channel is removed" }
              cont.resume(null)
            }

            override fun onFailure(reason: Int) {
              val r = WiFiDirectError.Reason.parseReason(reason)
              Timber.w { "Failed to stop network: ${r.displayReason}" }
              cont.resume(r)
            }
          },
        )
      }
    }
  }

  /**
   * BE VERY CAREFUL
   *
   * If this function returns NULL, regardless of what our other WP2P callbacks say, we have no
   * group
   */
  @CheckResult
  suspend fun requestGroupInfo(channel: Channel): WifiP2pGroup? {
    enforcer.assertOffMainThread()

    return suspendCancellableCoroutine { cont ->
      try {
        @SuppressLint("MissingPermission")
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

  /**
   * BE VERY CAREFUL
   *
   * If this function returns NULL, regardless of what our other WP2P callbacks say, we have no
   * connection
   */
  @CheckResult
  suspend fun requestConnectionInfo(channel: Channel): WifiP2pInfo? {
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
  suspend fun createChannel(): Channel? {
    enforcer.assertOffMainThread()

    return mutex.withLock {
      Timber.d { "Creating WifiP2PManager Channel" }

      // This can return null if initialization fails
      return@withLock wifiP2PManager.initialize(
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

  }

  suspend fun createGroup(channel: Channel) {
    enforcer.assertOffMainThread()

    return mutex.withLock {
      Timber.d { "Creating new wifi p2p group" }
      val conf = config.getConfiguration()

      val fakeError = appEnvironment.isBroadcastFakeError
      val isFakeError = fakeError.first()

      return@withLock suspendCancellableCoroutine { cont ->
        val listener =
          object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
              Timber.d { "New network created. Group created (custom config = $conf)" }

              if (isFakeError) {
                Timber.w { "DEBUG forcing Fake Broadcast Error" }
                cont.resumeWithException(RuntimeException("DEBUG: Force Fake Broadcast Error"))
              } else {
                cont.resume(Unit)
              }
            }

            override fun onFailure(reason: Int) {
              val r = WiFiDirectError.Reason.parseReason(reason)
              val e = RuntimeException("Broadcast Error: ${r.displayReason}")
              Timber.e(e) { "Unable to create Wifi Direct Group" }
              cont.resumeWithException(e)
            }
          }

        if (conf != null) {
          createGroupQ(channel, conf, listener)
        } else {
          @SuppressLint("MissingPermission")
          wifiP2PManager.createGroup(
            channel,
            listener,
          )
        }
      }
    }
  }
}
