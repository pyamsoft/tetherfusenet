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

package com.pyamsoft.tetherfi.server.broadcast.wifidirect

import android.net.wifi.p2p.WifiP2pConfig
import android.os.Build
import androidx.annotation.CheckResult
import androidx.annotation.RequiresApi
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.WifiPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class WiDiConfigImpl
@Inject
internal constructor(
    private val preferences: WifiPreferences,
    private val dispatchers: AppDispatchers,
) : WiDiConfig {

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.Q)
  private suspend fun getPreferredSsid(): String {
    return preferences.listenForSsidChanges().first()
  }

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.Q)
  private suspend fun getPreferredPassword(): String {
    return preferences.listenForPasswordChanges().first()
  }

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.Q)
  private fun getPreferredBandQ(band: ServerNetworkBand): Int =
      when (band) {
        ServerNetworkBand.LEGACY -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        // API Q does not have support for other bands, just use the newest we can
        else -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
      }

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun getPreferredBandBaklava(band: ServerNetworkBand): Int =
      when (band) {
        // Baklava is the first to support Wifi 6
        ServerNetworkBand.MODERN_6 -> WifiP2pConfig.GROUP_OWNER_BAND_6GHZ
        else -> getPreferredBandQ(band)
      }

  @CheckResult
  @RequiresApi(Build.VERSION_CODES.Q)
  private suspend fun getPreferredBand(): Int {
    val band = preferences.listenForNetworkBandChanges().first()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      return getPreferredBandBaklava(band)
    }

    return getPreferredBandQ(band)
  }

  override suspend fun matchesGroup(ssid: String, password: String): Boolean =
      withContext(context = dispatchers.default) {
        // If we can't use a custom config, then it always matches based on the system!
        if (!ServerDefaults.canUseCustomConfig()) {
          return@withContext true
        }

        val expectedSsid = ServerDefaults.asWifiSsid(getPreferredSsid())
        val expectedPassword = getPreferredPassword()
        return@withContext ssid == expectedSsid && password == expectedPassword
      }

  override suspend fun getConfiguration(): WifiP2pConfig? =
      withContext(context = dispatchers.default) {
        if (!ServerDefaults.canUseCustomConfig()) {
          return@withContext null
        }

        val ssid = ServerDefaults.asWifiSsid(getPreferredSsid())
        val passwd = getPreferredPassword()
        val band = getPreferredBand()

        return@withContext WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(passwd)
            .setGroupOperatingBand(band)
            .build()
      }
}
