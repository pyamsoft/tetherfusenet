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

import androidx.annotation.CheckResult
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.StatusPreferences
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.WifiPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
@LintIgnoreTooManyFunctions
internal class PreferencesImpl
@Inject
internal constructor(
    private val enforcer: ThreadEnforcer,
    private val dispatchers: AppDispatchers,
    private val appScope: AppCoroutineScope,
    dataStore: DataStore<Preferences>,
) :
    StatusPreferences,
    ProxyPreferences,
    InAppRatingPreferences,
    TweakPreferences,
    ExpertPreferences,
    WifiPreferences {

  private val preferences by lazy {
    onClearOldPreferences(dataStore)
    return@lazy dataStore
  }

  // Keep this lazy so that the fallback password is always the same
  private val fallbackPassword by lazy { PasswordGenerator.generate() }

  private fun onClearOldPreferences(store: DataStore<Preferences>) {
    appScope.launch(context = dispatchers.io) {
      store.edit { mutableStore ->
        for (key in OldKeys) {
          mutableStore.remove(key)
        }
      }
    }
  }

  private inline fun <T : Any> setPreference(
      key: Preferences.Key<T>,
      fallbackValue: T,
      crossinline value: suspend (Preferences) -> T,
  ) {
    appScope.launch(context = dispatchers.io) {
      try {
        preferences.edit { it[key] = value(it) }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        e.ifNotCancellation { preferences.edit { it[key] = fallbackValue } }
      }
    }
  }

  private fun <T : Any> getPreference(key: Preferences.Key<T>, value: T): Flow<T> =
      preferences.data
          .map { it[key] ?: value }
          // Otherwise any time ANY preference updates, ALL preferences will be
          // re-sent
          .distinctUntilChanged()
          .catch { err ->
            Timber.e(err) { "Error reading from dataStore: ${key.name}" }
            preferences.edit { it[key] = value }
            emit(value)
          }

  @CheckResult
  private fun Int.isInAppRatingAlreadyShown(): Boolean {
    enforcer.assertOffMainThread()

    val self = this
    return self > 0 && self == BuildConfig.VERSION_CODE
  }

  @CheckResult
  private fun getInAppRatingShownVersion(preferences: Preferences): Int =
      preferences[PreferenceKeys.IN_APP_RATING_SHOWN_VERSION] ?: 0

  override fun listenForSsidChanges(): Flow<String> =
      getPreference(key = PreferenceKeys.SSID, value = ServerDefaults.WIFI_SSID)
          .flowOn(context = dispatchers.io)

  override fun setSsid(ssid: String) =
      setPreference(
          key = PreferenceKeys.SSID,
          fallbackValue = ServerDefaults.WIFI_SSID,
          value = { ssid },
      )

  override fun listenForPasswordChanges(): Flow<String> =
      getPreference(key = PreferenceKeys.PASSWORD, value = fallbackPassword)
          .flowOn(context = dispatchers.io)

  override fun setPassword(password: String) =
      setPreference(
          key = PreferenceKeys.PASSWORD,
          fallbackValue = fallbackPassword,
          value = { password },
      )

  override fun listenForHttpEnabledChanges(): Flow<Boolean> =
      getPreference(key = PreferenceKeys.IS_HTTP_ENABLED, value = DEFAULT_IS_HTTP_ENABLED)
          .flowOn(context = dispatchers.io)

  override fun setHttpEnabled(enabled: Boolean) =
      setPreference(
          key = PreferenceKeys.IS_HTTP_ENABLED,
          fallbackValue = DEFAULT_IS_HTTP_ENABLED,
          value = { enabled },
      )

  override fun listenForPortChanges(): Flow<Int> =
      getPreference(key = PreferenceKeys.PORT, value = ServerDefaults.HTTP_PORT)
          .flowOn(context = dispatchers.io)

  override fun setPort(port: Int) =
      setPreference(
          key = PreferenceKeys.PORT,
          fallbackValue = ServerDefaults.HTTP_PORT,
          value = { port },
      )

  override fun listenForSocksEnabledChanges(): Flow<Boolean> =
      getPreference(key = PreferenceKeys.IS_SOCKS_ENABLED, value = DEFAULT_IS_SOCKS_ENABLED)
          .flowOn(context = dispatchers.io)

  override fun setSocksEnabled(enabled: Boolean) =
      setPreference(
          key = PreferenceKeys.IS_SOCKS_ENABLED,
          fallbackValue = DEFAULT_IS_SOCKS_ENABLED,
          value = { enabled },
      )

  override fun listenForNetworkBandChanges(): Flow<ServerNetworkBand> =
      getPreference(
              key = PreferenceKeys.NETWORK_BAND,
              value = ServerDefaults.WIFI_NETWORK_BAND.name,
          )
          .map { ServerNetworkBand.valueOf(it) }
          .flowOn(context = dispatchers.io)

  override fun setNetworkBand(band: ServerNetworkBand) =
      setPreference(
          key = PreferenceKeys.NETWORK_BAND,
          fallbackValue = ServerDefaults.WIFI_NETWORK_BAND.name,
          value = { band.name },
      )

  override fun listenForStartIgnoreVpn(): Flow<Boolean> =
      getPreference(key = PreferenceKeys.START_IGNORE_VPN, value = DEFAULT_START_IGNORE_VPN)
          .flowOn(context = dispatchers.io)

  override fun setStartIgnoreVpn(ignore: Boolean) =
      setPreference(
          key = PreferenceKeys.START_IGNORE_VPN,
          fallbackValue = DEFAULT_START_IGNORE_VPN,
          value = { ignore },
      )

  override fun listenForStartIgnoreLocation(): Flow<Boolean> =
      getPreference(
              key = PreferenceKeys.START_IGNORE_LOCATION,
              value = DEFAULT_START_IGNORE_LOCATION,
          )
          .flowOn(context = dispatchers.io)

  override fun setStartIgnoreLocation(ignore: Boolean) =
      setPreference(
          key = PreferenceKeys.START_IGNORE_LOCATION,
          fallbackValue = DEFAULT_START_IGNORE_LOCATION,
          value = { ignore },
      )

  override fun listenForShutdownWithNoClients(): Flow<Boolean> =
      getPreference(
              key = PreferenceKeys.SHUTDOWN_NO_CLIENTS,
              value = DEFAULT_SHUTDOWN_NO_CLIENTS,
          )
          .flowOn(context = dispatchers.io)

  override fun setShutdownWithNoClients(shutdown: Boolean) =
      setPreference(
          key = PreferenceKeys.SHUTDOWN_NO_CLIENTS,
          fallbackValue = DEFAULT_SHUTDOWN_NO_CLIENTS,
          value = { shutdown },
      )

  override fun listenForWakeLock(): Flow<Boolean> =
      getPreference(key = PreferenceKeys.HOLD_WAKELOCK, value = DEFAULT_HOLD_WAKELOCK)
          .flowOn(context = dispatchers.io)

  override fun setWakeLock(wakelock: Boolean) =
      setPreference(
          key = PreferenceKeys.HOLD_WAKELOCK,
          fallbackValue = DEFAULT_HOLD_WAKELOCK,
          value = { wakelock },
      )

  override fun listenForKeepScreenOn(): Flow<Boolean> =
      getPreference(key = PreferenceKeys.KEEP_SCREEN_ON, value = DEFAULT_KEEP_SCREEN_ON)
          .flowOn(context = dispatchers.io)

  override fun setKeepScreenOn(keep: Boolean) =
      setPreference(
          key = PreferenceKeys.KEEP_SCREEN_ON,
          fallbackValue = DEFAULT_KEEP_SCREEN_ON,
          value = { keep },
      )

  override fun listenForBroadcastType(): Flow<BroadcastType> =
      getPreference(
              key = PreferenceKeys.BROADCAST_TYPE,
              value = BroadcastType.WIFI_DIRECT.name,
          )
          .map { BroadcastType.valueOf(it) }
          .flowOn(context = dispatchers.io)

  override fun setBroadcastType(type: BroadcastType) =
      setPreference(
          key = PreferenceKeys.BROADCAST_TYPE,
          fallbackValue = BroadcastType.WIFI_DIRECT.name,
          value = { type.name },
      )

  override fun listenForPreferredNetwork(): Flow<PreferredNetwork> =
      getPreference(key = PreferenceKeys.PREFERRED_NETWORK, value = PreferredNetwork.NONE.name)
          .map { PreferredNetwork.valueOf(it) }
          .flowOn(context = dispatchers.io)

  override fun setPreferredNetwork(network: PreferredNetwork) =
      setPreference(
          key = PreferenceKeys.PREFERRED_NETWORK,
          fallbackValue = PreferredNetwork.NONE.name,
          value = { network.name },
      )

  override fun listenShowInAppRating(): Flow<Boolean> =
      combineTransform(
              preferences.data.map { it[PreferenceKeys.IN_APP_HOTSPOT_USED] ?: 0 },
              preferences.data.map { it[PreferenceKeys.IN_APP_DEVICES_CONNECTED] ?: 0 },
              preferences.data.map { it[PreferenceKeys.IN_APP_APP_OPENED] ?: 0 },
              preferences.data.map { it[PreferenceKeys.IN_APP_RATING_SHOWN_VERSION] ?: 0 },
          ) { hotspotUsed, devicesConnected, appOpened, lastVersionShown ->
            enforcer.assertOffMainThread()

            Timber.d {
              "In app rating check: ${
                    mapOf(
                        "lastVersion" to lastVersionShown,
                        "isAlreadyShown" to lastVersionShown.isInAppRatingAlreadyShown(),
                        "hotspotUsed" to hotspotUsed,
                        "devicesConnected" to devicesConnected,
                        "appOpened" to appOpened,
                    )
                }"
            }

            if (lastVersionShown.isInAppRatingAlreadyShown()) {
              Timber.w { "Already shown in-app rating for version: $lastVersionShown" }
              emit(false)
            } else {
              val show = hotspotUsed >= 3 && devicesConnected >= 2 && appOpened >= 7
              emit(show)

              if (show) {
                // Commit this edit so that it fires immediately before we process again
                preferences.edit { settings ->
                  // Reset the previous flags
                  settings[PreferenceKeys.IN_APP_APP_OPENED] = 0
                  settings[PreferenceKeys.IN_APP_HOTSPOT_USED] = 0
                  settings[PreferenceKeys.IN_APP_DEVICES_CONNECTED] = 0

                  // And mark the latest version
                  settings[PreferenceKeys.IN_APP_RATING_SHOWN_VERSION] = BuildConfig.VERSION_CODE
                }
              }
            }
          }
          .catch { err ->
            Timber.e(err) { "Error listening for composite showAppRating" }
            preferences.edit { settings ->
              settings[PreferenceKeys.IN_APP_APP_OPENED] = 0
              settings[PreferenceKeys.IN_APP_HOTSPOT_USED] = 0
              settings[PreferenceKeys.IN_APP_DEVICES_CONNECTED] = 0
              settings[PreferenceKeys.IN_APP_RATING_SHOWN_VERSION] = 0
            }
            emit(false)
          }
          // Need this or we run on the main thread
          .flowOn(context = dispatchers.io)

  override fun markHotspotUsed() =
      setPreference(
          key = PreferenceKeys.IN_APP_HOTSPOT_USED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[PreferenceKeys.IN_APP_HOTSPOT_USED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun markAppOpened() =
      setPreference(
          key = PreferenceKeys.IN_APP_APP_OPENED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[PreferenceKeys.IN_APP_APP_OPENED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun markDeviceConnected() =
      setPreference(
          key = PreferenceKeys.IN_APP_DEVICES_CONNECTED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[PreferenceKeys.IN_APP_DEVICES_CONNECTED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun listenForSocketTimeout(): Flow<ServerSocketTimeout> =
      getPreference(
              key = PreferenceKeys.SOCKET_TIMEOUT,
              value = ServerSocketTimeout.Defaults.BALANCED.timeoutDuration.inWholeSeconds,
          )
          .map { ServerSocketTimeout.create(it) }

  override fun setSocketTimeout(limit: ServerSocketTimeout) =
      setPreference(
          key = PreferenceKeys.SOCKET_TIMEOUT,
          fallbackValue = ServerSocketTimeout.Defaults.BALANCED.timeoutDuration.inWholeSeconds,
          value = {
            if (limit.timeoutDuration.isInfinite()) -1 else limit.timeoutDuration.inWholeSeconds
          },
      )

  private object PasswordGenerator {

    private const val ALL_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    @JvmStatic
    @CheckResult
    fun generate(size: Int = 8): String {
      val chars = ALL_CHARS

      var pass = ""
      while (true) {
        pass += chars[Random.nextInt(0, chars.length)]

        // Stop once generated
        if (pass.length >= size) {
          break
        }
      }
      return pass
    }
  }

  companion object {

    private const val DEFAULT_IS_HTTP_ENABLED = true

    private const val DEFAULT_IS_SOCKS_ENABLED = false

    private const val DEFAULT_START_IGNORE_VPN = false

    private const val DEFAULT_START_IGNORE_LOCATION = false

    private const val DEFAULT_SHUTDOWN_NO_CLIENTS = false

    private const val DEFAULT_HOLD_WAKELOCK = false

    private const val DEFAULT_KEEP_SCREEN_ON = false

    private val OldKeys =
        listOf(
            // Server Limits
            intPreferencesKey("key_server_perf_limit_1"),
            // New Engine
            booleanPreferencesKey("key_new_engine_1"),
            // SOCKS specific port
            intPreferencesKey("key_socks_port_1"),
        )
  }
}
