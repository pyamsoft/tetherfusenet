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

package com.pyamsoft.tetherfi.status

import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.WifiPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeExpertPreferences : ExpertPreferences {
  val broadcastTypeCalls = mutableListOf<BroadcastType>()
  val preferredNetworkCalls = mutableListOf<PreferredNetwork>()

  override fun listenForSocketTimeout(): Flow<com.pyamsoft.tetherfi.server.ServerSocketTimeout> =
      MutableStateFlow(com.pyamsoft.tetherfi.server.ServerSocketTimeout.Defaults.BALANCED)

  override fun setSocketTimeout(limit: com.pyamsoft.tetherfi.server.ServerSocketTimeout) = Unit

  override fun listenForBroadcastType(): Flow<BroadcastType> =
      MutableStateFlow(BroadcastType.entries.first())

  override fun setBroadcastType(type: BroadcastType) {
    broadcastTypeCalls.add(type)
  }

  override fun listenForPreferredNetwork(): Flow<PreferredNetwork> =
      MutableStateFlow(PreferredNetwork.entries.first())

  override fun setPreferredNetwork(network: PreferredNetwork) {
    preferredNetworkCalls.add(network)
  }
}

private class FakeProxyPreferences(
    private val port: Int = 8228,
    private val httpEnabled: Boolean = true,
    private val socksEnabled: Boolean = true,
) : ProxyPreferences {
  var listenForPortChangesCallCount = 0
    private set

  override fun listenForPortChanges(): Flow<Int> {
    listenForPortChangesCallCount += 1
    return MutableStateFlow(port)
  }

  override fun setPort(port: Int) = Unit

  override fun listenForHttpEnabledChanges(): Flow<Boolean> = MutableStateFlow(httpEnabled)

  override fun setHttpEnabled(enabled: Boolean) = Unit

  override fun listenForSocksEnabledChanges(): Flow<Boolean> = MutableStateFlow(socksEnabled)

  override fun setSocksEnabled(enabled: Boolean) = Unit
}

private class FakeWifiPreferences : WifiPreferences {
  val setSsidCalls = mutableListOf<String>()
  val setPasswordCalls = mutableListOf<String>()
  val setNetworkBandCalls = mutableListOf<ServerNetworkBand>()

  override fun listenForSsidChanges(): Flow<String> = MutableStateFlow("TetherFi")

  override fun setSsid(ssid: String) {
    setSsidCalls.add(ssid)
  }

  override fun listenForPasswordChanges(): Flow<String> = MutableStateFlow("password")

  override fun setPassword(password: String) {
    setPasswordCalls.add(password)
  }

  override fun listenForNetworkBandChanges(): Flow<ServerNetworkBand> =
      MutableStateFlow(ServerNetworkBand.LEGACY)

  override fun setNetworkBand(band: ServerNetworkBand) {
    setNetworkBandCalls.add(band)
  }
}

private fun newViewModeler(
  dispatchers: AppDispatchers,
    state: MutableStatusViewState = MutableStatusViewState(),
    expertPreferences: ExpertPreferences = FakeExpertPreferences(),
    proxyPreferences: ProxyPreferences = FakeProxyPreferences(),
    wifiPreferences: WifiPreferences = FakeWifiPreferences(),
): StatusViewModeler =
    StatusViewModeler(
        state = state,
        expertPreferences = expertPreferences,
        proxyPreferences = proxyPreferences,
        wifiPreferences = wifiPreferences,
      dispatchers = dispatchers,
    )

class StatusViewModelerTest {

  @Test
  fun `bind loads preferences and marks loading state done`() = runTest {
    val state = MutableStatusViewState()
    val proxyPreferences = FakeProxyPreferences()
    val viewModeler = newViewModeler(
      state = state, proxyPreferences = proxyPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.bind(this)

    val finalState = state.loadingState.first { it == StatusViewState.LoadingState.DONE }
    assertEquals(StatusViewState.LoadingState.DONE, finalState)

    // Custom WiFi Direct config is unavailable tests
    // TODO(Peter): Run this under robolectric for SDK split testing
    assertEquals("", state.ssid.value)
    assertEquals("", state.password.value)
    assertNull(state.band.value)
  }

  @Test
  fun `bind is a no-op while already loading`() = runTest {
    val proxyPreferences = FakeProxyPreferences()
    val viewModeler = newViewModeler(
      proxyPreferences = proxyPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.bind(this)
    viewModeler.bind(this)

    assertEquals(1, proxyPreferences.listenForPortChangesCallCount)
  }

  @Test
  fun `handleTogglePasswordVisibility flips the flag`() {
    val state = MutableStatusViewState()
    val viewModeler = newViewModeler(
      state = state,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleTogglePasswordVisibility()
    assertTrue(state.isPasswordVisible.value)

    viewModeler.handleTogglePasswordVisibility()
    assertFalse(state.isPasswordVisible.value)
  }

  @Test
  fun `handleSsidChanged updates state and writes through to preferences`() {
    val state = MutableStatusViewState()
    val wifiPreferences = FakeWifiPreferences()
    val viewModeler = newViewModeler(
      state = state, wifiPreferences = wifiPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleSsidChanged("NewSsid")

    assertEquals("NewSsid", state.ssid.value)
    assertEquals(listOf("NewSsid"), wifiPreferences.setSsidCalls)
  }

  @Test
  fun `handlePasswordChanged updates state and writes through to preferences`() {
    val state = MutableStatusViewState()
    val wifiPreferences = FakeWifiPreferences()
    val viewModeler = newViewModeler(
      state = state, wifiPreferences = wifiPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handlePasswordChanged("NewPassword")

    assertEquals("NewPassword", state.password.value)
    assertEquals(listOf("NewPassword"), wifiPreferences.setPasswordCalls)
  }

  @Test
  fun `handleChangeBand updates state and writes through to preferences`() {
    val state = MutableStatusViewState()
    val wifiPreferences = FakeWifiPreferences()
    val viewModeler = newViewModeler(
      state = state, wifiPreferences = wifiPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleChangeBand(ServerNetworkBand.MODERN)

    assertEquals(ServerNetworkBand.MODERN, state.band.value)
    assertEquals(listOf(ServerNetworkBand.MODERN), wifiPreferences.setNetworkBandCalls)
  }

  @Test
  fun `handleUpdateBroadcastType delegates to expert preferences`() {
    val expertPreferences = FakeExpertPreferences()
    val viewModeler = newViewModeler(
      expertPreferences = expertPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleUpdateBroadcastType(BroadcastType.entries.last())

    assertEquals(listOf(BroadcastType.entries.last()), expertPreferences.broadcastTypeCalls)
  }

  @Test
  fun `handleUpdatePreferredNetwork delegates to expert preferences`() {
    val expertPreferences = FakeExpertPreferences()
    val viewModeler = newViewModeler(
      expertPreferences = expertPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleUpdatePreferredNetwork(PreferredNetwork.entries.last())

    assertEquals(listOf(PreferredNetwork.entries.last()), expertPreferences.preferredNetworkCalls)
  }
}
