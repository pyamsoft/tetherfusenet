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

package com.pyamsoft.tetherfi

import android.content.Context
import androidx.annotation.CheckResult
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.preference.PreferenceManager
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.ServerPerformanceLimit
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
internal class PreferencesImpl
@Inject
internal constructor(private val enforcer: ThreadEnforcer, context: Context) :
    StatusPreferences,
    ProxyPreferences,
    InAppRatingPreferences,
    TweakPreferences,
    ExpertPreferences,
    WifiPreferences {

  private val Context.dataStore by
      preferencesDataStore(
          name = "tetherfi_preferences",
          corruptionHandler =
              ReplaceFileCorruptionHandler { err ->
                Timber.e(err) { "File corruption detected, start with empty Preferences" }
                return@ReplaceFileCorruptionHandler emptyPreferences()
              },
          produceMigrations = {
            listOf(
                // NOTE(Peter): Since our shared preferences was the DEFAULT process one, loading up
                //              a migration without specifying all keys will also migrate
                //              PYDROID SPECIFIC PREFERENCES which is what we do NOT want to do.
                //              We instead maintain ONLY a list of the known app preference keys
                SharedPreferencesMigration(
                    keysToMigrate =
                        setOf(
                            SSID.name,
                            PASSWORD.name,
                            HTTP_PORT.name,
                            SOCKS_PORT.name,
                            NETWORK_BAND.name,
                            IN_APP_HOTSPOT_USED.name,
                            IN_APP_DEVICES_CONNECTED.name,
                            IN_APP_APP_OPENED.name,
                            IN_APP_RATING_SHOWN_VERSION.name,
                            START_IGNORE_VPN.name,
                            START_IGNORE_LOCATION.name,
                            SHUTDOWN_NO_CLIENTS.name,
                            SERVER_LIMITS.name,
                            KEEP_SCREEN_ON.name,
                            BROADCAST_TYPE.name,
                            PREFERRED_NETWORK.name,
                            SOCKET_TIMEOUT.name,
                        ),
                    produceSharedPreferences = {
                      PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                    },
                )
            )
          },
      )

  private val preferences by lazy { context.applicationContext.dataStore }

  // Keep this lazy so that the fallback password is always the same
  private val fallbackPassword by lazy { PasswordGenerator.generate() }

  private val scope by lazy {
    CoroutineScope(
        context = SupervisorJob() + Dispatchers.IO + CoroutineName(this::class.java.name)
    )
  }

  private inline fun <T : Any> setPreference(
      key: Preferences.Key<T>,
      fallbackValue: T,
      crossinline value: suspend (Preferences) -> T,
  ) {
    scope.launch(context = Dispatchers.IO) {
      try {
        preferences.edit { it[key] = value(it) }
      } catch (e: Throwable) {
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
      preferences[IN_APP_RATING_SHOWN_VERSION] ?: 0

  override fun listenForSsidChanges(): Flow<String> =
      getPreference(key = SSID, value = ServerDefaults.WIFI_SSID).flowOn(context = Dispatchers.IO)

  override fun setSsid(ssid: String) =
      setPreference(
          key = SSID,
          fallbackValue = ServerDefaults.WIFI_SSID,
          value = { ssid },
      )

  override fun listenForPasswordChanges(): Flow<String> =
      getPreference(key = PASSWORD, value = fallbackPassword).flowOn(context = Dispatchers.IO)

  override fun setPassword(password: String) =
      setPreference(
          key = PASSWORD,
          fallbackValue = fallbackPassword,
          value = { password },
      )

  override fun listenForHttpEnabledChanges(): Flow<Boolean> =
      getPreference(key = IS_HTTP_ENABLED, value = DEFAULT_IS_HTTP_ENABLED)
          .flowOn(context = Dispatchers.IO)

  override fun setHttpEnabled(enabled: Boolean) =
      setPreference(
          key = IS_HTTP_ENABLED,
          fallbackValue = DEFAULT_IS_HTTP_ENABLED,
          value = { enabled },
      )

  override fun listenForHttpPortChanges(): Flow<Int> =
      getPreference(key = HTTP_PORT, value = ServerDefaults.HTTP_PORT)
          .flowOn(context = Dispatchers.IO)

  override fun setHttpPort(port: Int) =
      setPreference(
          key = HTTP_PORT,
          fallbackValue = ServerDefaults.HTTP_PORT,
          value = { port },
      )

  override fun listenForSocksEnabledChanges(): Flow<Boolean> =
      getPreference(key = IS_SOCKS_ENABLED, value = DEFAULT_IS_SOCKS_ENABLED)
          .flowOn(context = Dispatchers.IO)

  override fun setSocksEnabled(enabled: Boolean) =
      setPreference(
          key = IS_SOCKS_ENABLED,
          fallbackValue = DEFAULT_IS_SOCKS_ENABLED,
          value = { enabled },
      )

  override fun listenForSocksPortChanges(): Flow<Int> =
      getPreference(key = SOCKS_PORT, value = ServerDefaults.SOCKS_PORT)
          .flowOn(context = Dispatchers.IO)

  override fun setSocksPort(port: Int) =
      setPreference(
          key = SOCKS_PORT,
          fallbackValue = ServerDefaults.SOCKS_PORT,
          value = { port },
      )

  override fun listenForNetworkBandChanges(): Flow<ServerNetworkBand> =
      getPreference(key = NETWORK_BAND, value = ServerDefaults.WIFI_NETWORK_BAND.name)
          .map { ServerNetworkBand.valueOf(it) }
          .flowOn(context = Dispatchers.IO)

  override fun setNetworkBand(band: ServerNetworkBand) =
      setPreference(
          key = NETWORK_BAND,
          fallbackValue = ServerDefaults.WIFI_NETWORK_BAND.name,
          value = { band.name },
      )

  override fun listenForStartIgnoreVpn(): Flow<Boolean> =
      getPreference(key = START_IGNORE_VPN, value = DEFAULT_START_IGNORE_VPN)
          .flowOn(context = Dispatchers.IO)

  override fun setStartIgnoreVpn(ignore: Boolean) =
      setPreference(
          key = START_IGNORE_VPN,
          fallbackValue = DEFAULT_START_IGNORE_VPN,
          value = { ignore },
      )

  override fun listenForStartIgnoreLocation(): Flow<Boolean> =
      getPreference(key = START_IGNORE_LOCATION, value = DEFAULT_START_IGNORE_LOCATION)
          .flowOn(context = Dispatchers.IO)

  override fun setStartIgnoreLocation(ignore: Boolean) =
      setPreference(
          key = START_IGNORE_LOCATION,
          fallbackValue = DEFAULT_START_IGNORE_LOCATION,
          value = { ignore },
      )

  override fun listenForShutdownWithNoClients(): Flow<Boolean> =
      getPreference(key = SHUTDOWN_NO_CLIENTS, value = DEFAULT_SHUTDOWN_NO_CLIENTS)
          .flowOn(context = Dispatchers.IO)

  override fun setShutdownWithNoClients(shutdown: Boolean) =
      setPreference(
          key = SHUTDOWN_NO_CLIENTS,
          fallbackValue = DEFAULT_SHUTDOWN_NO_CLIENTS,
          value = { shutdown },
      )

  override fun listenForWakeLock(): Flow<Boolean> =
      getPreference(key = HOLD_WAKELOCK, value = DEFAULT_HOLD_WAKELOCK)
          .flowOn(context = Dispatchers.IO)

  override fun setWakeLock(wakelock: Boolean) =
      setPreference(
          key = HOLD_WAKELOCK,
          fallbackValue = DEFAULT_HOLD_WAKELOCK,
          value = { wakelock },
      )

  override fun listenForKeepScreenOn(): Flow<Boolean> =
      getPreference(key = KEEP_SCREEN_ON, value = DEFAULT_KEEP_SCREEN_ON)
          .flowOn(context = Dispatchers.IO)

  override fun setKeepScreenOn(keep: Boolean) =
      setPreference(
          key = KEEP_SCREEN_ON,
          fallbackValue = DEFAULT_KEEP_SCREEN_ON,
          value = { keep },
      )

  override fun listenForBroadcastType(): Flow<BroadcastType> =
      getPreference(key = BROADCAST_TYPE, value = BroadcastType.WIFI_DIRECT.name)
          .map { BroadcastType.valueOf(it) }
          .flowOn(context = Dispatchers.IO)

  override fun setBroadcastType(type: BroadcastType) =
      setPreference(
          key = BROADCAST_TYPE,
          fallbackValue = BroadcastType.WIFI_DIRECT.name,
          value = { type.name },
      )

  override fun listenForPreferredNetwork(): Flow<PreferredNetwork> =
      getPreference(key = PREFERRED_NETWORK, value = PreferredNetwork.NONE.name)
          .map { PreferredNetwork.valueOf(it) }
          .flowOn(context = Dispatchers.IO)

  override fun setPreferredNetwork(network: PreferredNetwork) =
      setPreference(
          key = PREFERRED_NETWORK,
          fallbackValue = PreferredNetwork.NONE.name,
          value = { network.name },
      )

  override fun listenShowInAppRating(): Flow<Boolean> =
      combineTransform(
              preferences.data.map { it[IN_APP_HOTSPOT_USED] ?: 0 },
              preferences.data.map { it[IN_APP_DEVICES_CONNECTED] ?: 0 },
              preferences.data.map { it[IN_APP_APP_OPENED] ?: 0 },
              preferences.data.map { it[IN_APP_RATING_SHOWN_VERSION] ?: 0 },
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
                  settings[IN_APP_APP_OPENED] = 0
                  settings[IN_APP_HOTSPOT_USED] = 0
                  settings[IN_APP_DEVICES_CONNECTED] = 0

                  // And mark the latest version
                  settings[IN_APP_RATING_SHOWN_VERSION] = BuildConfig.VERSION_CODE
                }
              }
            }
          }
          .catch { err ->
            Timber.e(err) { "Error listening for composite showAppRating" }
            preferences.edit { settings ->
              settings[IN_APP_APP_OPENED] = 0
              settings[IN_APP_HOTSPOT_USED] = 0
              settings[IN_APP_DEVICES_CONNECTED] = 0
              settings[IN_APP_RATING_SHOWN_VERSION] = 0
            }
            emit(false)
          }
          // Need this or we run on the main thread
          .flowOn(context = Dispatchers.IO)

  override fun markHotspotUsed() =
      setPreference(
          key = IN_APP_HOTSPOT_USED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[IN_APP_HOTSPOT_USED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun markAppOpened() =
      setPreference(
          key = IN_APP_APP_OPENED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[IN_APP_APP_OPENED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun markDeviceConnected() =
      setPreference(
          key = IN_APP_DEVICES_CONNECTED,
          fallbackValue = 0,
          value = { settings ->
            val version = getInAppRatingShownVersion(settings)
            val current = settings[IN_APP_DEVICES_CONNECTED] ?: 0
            if (version.isInAppRatingAlreadyShown()) {
              return@setPreference current
            }

            return@setPreference current + 1
          },
      )

  override fun listenForPerformanceLimits(): Flow<ServerPerformanceLimit> =
      getPreference(
              key = SERVER_LIMITS,
              value = ServerPerformanceLimit.Defaults.BOUND_3N_CPU.coroutineLimit,
          )
          .map { ServerPerformanceLimit.create(it) }

  override fun setServerPerformanceLimit(limit: ServerPerformanceLimit) =
      setPreference(
          key = SERVER_LIMITS,
          fallbackValue = ServerPerformanceLimit.Defaults.BOUND_3N_CPU.coroutineLimit,
          value = { limit.coroutineLimit },
      )

  override fun listenForSocketTimeout(): Flow<ServerSocketTimeout> =
      getPreference(
              key = SOCKET_TIMEOUT,
              value = ServerSocketTimeout.Defaults.BALANCED.timeoutDuration.inWholeSeconds,
          )
          .map { ServerSocketTimeout.create(it) }

  override fun setSocketTimeout(limit: ServerSocketTimeout) =
      setPreference(
          key = SOCKET_TIMEOUT,
          fallbackValue = ServerSocketTimeout.Defaults.BALANCED.timeoutDuration.inWholeSeconds,
          value = {
            if (limit.timeoutDuration.isInfinite()) -1 else limit.timeoutDuration.inWholeSeconds
          },
      )

  override fun listenForNewEngineEnabled(): Flow<Boolean> =
      getPreference(
          key = NEW_ENGINE,
          value = DEFAULT_NEW_ENGINE,
      )

  override fun setNewEngine(enabled: Boolean) =
      setPreference(
          key = NEW_ENGINE,
          fallbackValue = DEFAULT_NEW_ENGINE,
          value = { enabled },
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

    private val SSID = stringPreferencesKey("key_ssid_1")
    private val PASSWORD = stringPreferencesKey("key_password_1")
    private val NETWORK_BAND = stringPreferencesKey("key_network_band_1")

    private val IS_HTTP_ENABLED = booleanPreferencesKey("key_http_enabled_1")
    private val HTTP_PORT = intPreferencesKey("key_port_1")
    private const val DEFAULT_IS_HTTP_ENABLED = true

    private val IS_SOCKS_ENABLED = booleanPreferencesKey("key_socks_enabled_1")
    private val SOCKS_PORT = intPreferencesKey("key_socks_port_1")
    private const val DEFAULT_IS_SOCKS_ENABLED = false

    private val IN_APP_HOTSPOT_USED = intPreferencesKey("key_in_app_hotspot_used_1")
    private val IN_APP_DEVICES_CONNECTED = intPreferencesKey("key_in_app_devices_connected_1")
    private val IN_APP_APP_OPENED = intPreferencesKey("key_in_app_app_opened_1")

    private val IN_APP_RATING_SHOWN_VERSION = intPreferencesKey("key_in_app_rating_shown_version")

    private val START_IGNORE_VPN = booleanPreferencesKey("key_start_ignore_vpn_1")
    private const val DEFAULT_START_IGNORE_VPN = false

    private val START_IGNORE_LOCATION = booleanPreferencesKey("key_start_ignore_location_1")
    private const val DEFAULT_START_IGNORE_LOCATION = false

    private val SHUTDOWN_NO_CLIENTS = booleanPreferencesKey("key_shutdown_no_clients_1")
    private const val DEFAULT_SHUTDOWN_NO_CLIENTS = false

    private val HOLD_WAKELOCK = booleanPreferencesKey("key_hold_wakelock_1")
    private const val DEFAULT_HOLD_WAKELOCK = false

    private val SERVER_LIMITS = intPreferencesKey("key_server_perf_limit_1")

    private val KEEP_SCREEN_ON = booleanPreferencesKey("key_keep_screen_on_1")
    private const val DEFAULT_KEEP_SCREEN_ON = false

    private val BROADCAST_TYPE = stringPreferencesKey("key_broadcast_type_1")

    private val PREFERRED_NETWORK = stringPreferencesKey("key_preferred_network_1")

    private val SOCKET_TIMEOUT = longPreferencesKey("key_socket_timeout_1")

    private val NEW_ENGINE = booleanPreferencesKey("key_new_engine_1")
    private const val DEFAULT_NEW_ENGINE = false
  }
}
