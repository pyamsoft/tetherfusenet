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

package com.pyamsoft.tetherfi.status

import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.WifiPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StatusViewModeler
@Inject
internal constructor(
    override val state: MutableStatusViewState,
    private val expertPreferences: ExpertPreferences,
    private val proxyPreferences: ProxyPreferences,
    private val wifiPreferences: WifiPreferences,
) : StatusViewState by state, AbstractViewModeler<StatusViewState>(state) {

  private data class LoadConfig(
      var ssid: Boolean,
      var password: Boolean,
      var band: Boolean,
      var port: Boolean,
      var isHttpEnabled: Boolean,
      var isSocksEnabled: Boolean,
  )

  private fun markPreferencesLoaded(config: LoadConfig) {
    val isProxyReady = config.port && config.isHttpEnabled && config.isSocksEnabled
    val isWifiDirectReady = config.ssid && config.password && config.band

    if (isProxyReady && isWifiDirectReady) {
      state.loadingState.value = StatusViewState.LoadingState.DONE
    }
  }

  fun handleToggleProxy(onToggleProxy: () -> Unit) {
    // Hide the password
    state.isPasswordVisible.value = false
    onToggleProxy()
  }

  private fun loadPreferences(scope: CoroutineScope) {
    val s = state

    // If we are already loading, ignore this call
    if (s.loadingState.value != StatusViewState.LoadingState.NONE) {
      return
    }

    // Make this load config that we will update as things load in
    val config =
        LoadConfig(
            ssid = false,
            password = false,
            band = false,
          port = false,
            isHttpEnabled = false,
            isSocksEnabled = false,
        )

    // Start loading
    s.loadingState.value = StatusViewState.LoadingState.LOADING

    scope.bindConfigPreferences(config)
  }

  private fun CoroutineScope.bindProxyPreferences(config: LoadConfig) {
    val scope = this

    proxyPreferences.listenForHttpEnabledChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        // We don't need to do anything with this, we just need to be sure
        // that some value has loaded.
        // Actual values are provided by ServerViewState
        f.first()

        config.isHttpEnabled = true
        markPreferencesLoaded(config)
      }
    }

    proxyPreferences.listenForSocksEnabledChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        // We don't need to do anything with this, we just need to be sure
        // that some value has loaded.
        // Actual values are provided by ServerViewState
        f.first()

        config.isSocksEnabled = true
        markPreferencesLoaded(config)
      }
    }

    proxyPreferences.listenForPortChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        // We don't need to do anything with this, we just need to be sure
        // that some value has loaded.
        // Actual values are provided by ServerViewState
        f.first()

        config.port = true
        markPreferencesLoaded(config)
      }
    }
  }

  private fun CoroutineScope.bindWifiPreferences(config: LoadConfig) {
    val scope = this
    val s = state

    // Only pull once since after this point, the state will be driven by the input
    wifiPreferences.listenForSsidChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        // Only pull once since after this point, the state will be driven by the input
        val ssid = f.first()

        // Write the SSID to save it to Preferences
        handleSsidChanged(ssid)

        config.ssid = true
        markPreferencesLoaded(config)
      }
    }

    // Only pull once since after this point, the state will be driven by the input
    wifiPreferences.listenForPasswordChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        // Only pull once since after this point, the state will be driven by the input
        val password = f.first()

        // Write the password to save it to Preferences
        handlePasswordChanged(password)

        config.password = true
        markPreferencesLoaded(config)
      }
    }

    wifiPreferences.listenForNetworkBandChanges().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { band ->
          // Write the band to save it to Preferences
          handleChangeBand(band)

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != StatusViewState.LoadingState.DONE) {
            config.band = true
            markPreferencesLoaded(config)
          }
        }
      }
    }
  }

  private fun CoroutineScope.bindConfigPreferences(config: LoadConfig) {
    val scope = this
    val s = state

    scope.bindProxyPreferences(config)

    if (ServerDefaults.canUseCustomConfig()) {
      scope.bindWifiPreferences(config)
    } else {
      // No custom WiFi Direct config is allowed, fallback
      s.ssid.value = ""
      s.password.value = ""
      s.band.value = null

      // Mark loaded and attempt flag setting
      config.ssid = true
      config.password = true
      config.band = true
      markPreferencesLoaded(config)
    }
  }

  fun bind(
      scope: CoroutineScope,
  ) {
    loadPreferences(scope)
  }

  fun handleSsidChanged(ssid: String) {
    state.ssid.value = ssid
    wifiPreferences.setSsid(ssid)
  }

  fun handlePasswordChanged(password: String) {
    state.password.value = password
    wifiPreferences.setPassword(password)
  }

  fun handleChangeBand(band: ServerNetworkBand) {
    state.band.value = band
    wifiPreferences.setNetworkBand(band)
  }

  fun handleTogglePasswordVisibility() {
    state.isPasswordVisible.update { !it }
  }

  fun handleUpdateBroadcastType(type: BroadcastType) {
    expertPreferences.setBroadcastType(type)
  }

  fun handleUpdatePreferredNetwork(network: PreferredNetwork) {
    expertPreferences.setPreferredNetwork(network)
  }
}
