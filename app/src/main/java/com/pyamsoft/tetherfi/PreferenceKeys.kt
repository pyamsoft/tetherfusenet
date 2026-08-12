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

package com.pyamsoft.tetherfi

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object PreferenceKeys {

  val SSID = stringPreferencesKey("key_ssid_1")
  val PASSWORD = stringPreferencesKey("key_password_1")
  val NETWORK_BAND = stringPreferencesKey("key_network_band_1")

  val IS_HTTP_ENABLED = booleanPreferencesKey("key_http_enabled_1")
  val PORT = intPreferencesKey("key_port_1")

  val IS_SOCKS_ENABLED = booleanPreferencesKey("key_socks_enabled_1")

  val IN_APP_HOTSPOT_USED = intPreferencesKey("key_in_app_hotspot_used_1")
  val IN_APP_DEVICES_CONNECTED = intPreferencesKey("key_in_app_devices_connected_1")
  val IN_APP_APP_OPENED = intPreferencesKey("key_in_app_app_opened_1")

  val IN_APP_RATING_SHOWN_VERSION = intPreferencesKey("key_in_app_rating_shown_version")

  val START_IGNORE_VPN = booleanPreferencesKey("key_start_ignore_vpn_1")
  val START_IGNORE_LOCATION = booleanPreferencesKey("key_start_ignore_location_1")

  val SHUTDOWN_NO_CLIENTS = booleanPreferencesKey("key_shutdown_no_clients_1")

  val HOLD_WAKELOCK = booleanPreferencesKey("key_hold_wakelock_1")

  val KEEP_SCREEN_ON = booleanPreferencesKey("key_keep_screen_on_1")

  val BROADCAST_TYPE = stringPreferencesKey("key_broadcast_type_1")

  val PREFERRED_NETWORK = stringPreferencesKey("key_preferred_network_1")

  val SOCKET_TIMEOUT = longPreferencesKey("key_socket_timeout_1")
}
