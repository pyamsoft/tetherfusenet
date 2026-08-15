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

package com.pyamsoft.tetherfi.server.lock

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import androidx.annotation.CheckResult
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WiFiLocker @Inject internal constructor(
  context: Context,
  private val dispatchers: AppDispatchers,
) : AbstractLocker() {

  private val wifiManager by lazy {
    context.applicationContext.getSystemService<WifiManager>().requireNotNull()
  }
  private val tag by lazy { createTag(context.packageName) }

  @CheckResult
  private fun createWiFiLock(): WifiLock {
    Timber.d { "Create new wake lock with PARTIAL_WAKE_LOCK" }
    return wifiManager.createLock(tag).apply { setReferenceCounted(false) }
  }

  override suspend fun createLock(): Locker.Lock =
    withContext(context = dispatchers.default) {
        val wifiLock = createWiFiLock()
      return@withContext Lock(wifiLock, dispatchers, tag)
      }

  internal class Lock(
    private val wifiLock: WifiLock,
    dispatchers: AppDispatchers,
    lockTag: String
  ) :
    AbstractLock(
      lockType = "Wi-Fi", dispatchers = dispatchers,
      lockTag = lockTag
    ) {

    override suspend fun isHeld(): Boolean {
      return wifiLock.isHeld
    }

    override suspend fun onAcquireLock() {
      wifiLock.acquire()
    }

    override suspend fun onReleaseLock() {
      wifiLock.release()
    }
  }

  companion object {

    @JvmStatic
    @CheckResult
    private fun createTag(name: String): String {
      return "${name}:PROXY_WIFI_LOCK"
    }

    @JvmStatic
    @CheckResult
    private fun WifiManager.createLock(tag: String): WifiLock {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        this.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, tag)
      } else {
        @Suppress("DEPRECATION") this.createWifiLock(tag)
      }
    }
  }
}
