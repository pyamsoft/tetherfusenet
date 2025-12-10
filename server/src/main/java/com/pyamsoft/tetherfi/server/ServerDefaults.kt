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

import android.os.Build
import androidx.annotation.CheckResult
import androidx.annotation.ChecksSdkIntAtLeast

object ServerDefaults {

  /**
   * This SSID must be kept as the default "TetherFi" which is the old app name
   *
   * Otherwise it will cause upgrading users to have to re-setup the network, which we do
   * not want them to do
   */
  const val WIFI_SSID = "TetherFi"

  /** Default port for HTTP server */
  const val HTTP_PORT = 8228

  /** Default port for SOCKS server */
  const val SOCKS_PORT = 8229

  val WIFI_NETWORK_BAND = ServerNetworkBand.LEGACY

  @JvmStatic
  @CheckResult
  fun getWifiSsidPrefix(): String {
    return "DIRECT-TF-"
  }

  @JvmStatic
  @CheckResult
  fun asWifiSsid(ssid: String): String {
    return "${getWifiSsidPrefix()}${ssid}"
  }

  @CheckResult
  @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
  fun canUseCustomConfig(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
  }
}