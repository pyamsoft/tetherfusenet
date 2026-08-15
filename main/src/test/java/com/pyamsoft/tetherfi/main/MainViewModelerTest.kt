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

package com.pyamsoft.tetherfi.main

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pyamsoft.pydroid.core.createThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.broadcast.BroadcastEvent
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkUpdater
import com.pyamsoft.tetherfi.server.broadcast.BroadcastObserver
import com.pyamsoft.tetherfi.server.broadcast.BroadcastStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.proxy.ProxyStatus
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.ServiceLauncher
import com.pyamsoft.tetherfi.service.prereq.HotspotRequirements
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TestForegroundService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null
}

private class FakeSharedProxy(initialStatus: RunningStatus = RunningStatus.NotRunning) :
    SharedProxy {
  val statusFlow = MutableStateFlow(initialStatus)

  override fun getCurrentStatus(): RunningStatus = statusFlow.value

  override fun onStatusChanged(): Flow<RunningStatus> = statusFlow

  override suspend fun start(
      lock: Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  ) = Unit
}

private class FakeBroadcastNetworkStatus(initialStatus: RunningStatus = RunningStatus.NotRunning) :
    BroadcastNetworkStatus {
  val statusFlow = MutableStateFlow(initialStatus)

  override fun getCurrentStatus(): RunningStatus = statusFlow.value

  override fun onStatusChanged(): Flow<RunningStatus> = statusFlow

  override fun onGroupInfoChanged(): Flow<BroadcastNetworkStatus.GroupInfo> =
      MutableStateFlow(BroadcastNetworkStatus.GroupInfo.Empty)

  override fun onConnectionInfoChanged(): Flow<BroadcastNetworkStatus.ConnectionInfo> =
      MutableStateFlow(BroadcastNetworkStatus.ConnectionInfo.Empty)
}

private class FakeBroadcastNetworkUpdater : BroadcastNetworkUpdater {
  private val _updateCallCount = MutableStateFlow(0)
  val updateCallCount: StateFlow<Int> = _updateCallCount

  override suspend fun updateNetworkInfo() {
    _updateCallCount.update { it + 1 }
  }
}

private class FakeBroadcastObserver : BroadcastObserver {
  override fun listenNetworkEvents(): Flow<BroadcastEvent> = emptyFlow()
}

private class FakeInAppRatingPreferences : InAppRatingPreferences {
  override fun listenShowInAppRating(): Flow<Boolean> = MutableStateFlow(false)

  override fun markHotspotUsed() = Unit

  override fun markAppOpened() = Unit

  override fun markDeviceConnected() = Unit
}

private class FakeProxyPreferences : ProxyPreferences {
  val setPortCalls = mutableListOf<Int>()
  val setHttpEnabledCalls = mutableListOf<Boolean>()
  val setSocksEnabledCalls = mutableListOf<Boolean>()

  override fun listenForPortChanges(): Flow<Int> = MutableStateFlow(0)

  override fun setPort(port: Int) {
    setPortCalls.add(port)
  }

  override fun listenForHttpEnabledChanges(): Flow<Boolean> = MutableStateFlow(false)

  override fun setHttpEnabled(enabled: Boolean) {
    setHttpEnabledCalls.add(enabled)
  }

  override fun listenForSocksEnabledChanges(): Flow<Boolean> = MutableStateFlow(false)

  override fun setSocksEnabled(enabled: Boolean) {
    setSocksEnabledCalls.add(enabled)
  }
}

private class FakeExpertPreferences : ExpertPreferences {
  override fun listenForSocketTimeout(): Flow<ServerSocketTimeout> =
      MutableStateFlow(ServerSocketTimeout.Defaults.BALANCED)

  override fun setSocketTimeout(limit: ServerSocketTimeout) = Unit

  override fun listenForBroadcastType(): Flow<BroadcastType> =
      MutableStateFlow(BroadcastType.entries.first())

  override fun setBroadcastType(type: BroadcastType) = Unit

  override fun listenForPreferredNetwork(): Flow<PreferredNetwork> =
      MutableStateFlow(PreferredNetwork.entries.first())

  override fun setPreferredNetwork(network: PreferredNetwork) = Unit
}

private class FakeHotspotRequirements(
    private val blockers: Collection<HotspotStartBlocker> = emptySet(),
) : HotspotRequirements {
  override suspend fun blockers(): Collection<HotspotStartBlocker> = blockers
}

private fun newServiceLauncher(): ServiceLauncher =
    ServiceLauncher(
        context = RuntimeEnvironment.getApplication(),
        foregroundServiceClass = TestForegroundService::class.java,
        wiDiStatus = BroadcastStatus(),
        proxyStatus = ProxyStatus(),
    )

private fun newViewModeler(
  dispatchers: AppDispatchers,
    state: MutableMainViewState = MutableMainViewState(),
    proxy: SharedProxy = FakeSharedProxy(),
    requirements: HotspotRequirements = FakeHotspotRequirements(),
    networkStatus: BroadcastNetworkStatus = FakeBroadcastNetworkStatus(),
    networkUpdater: BroadcastNetworkUpdater = FakeBroadcastNetworkUpdater(),
    broadcastObserver: BroadcastObserver = FakeBroadcastObserver(),
    inAppRatingPreferences: InAppRatingPreferences = FakeInAppRatingPreferences(),
    proxyPreferences: ProxyPreferences = FakeProxyPreferences(),
    expertPreferences: ExpertPreferences = FakeExpertPreferences(),
    serviceLauncher: ServiceLauncher = newServiceLauncher(),
    appScope: AppCoroutineScope = AppCoroutineScope(appScope = CoroutineScope(Job())),
): MainViewModeler =
    MainViewModeler(
        state = state,
        proxy = proxy,
        enforcer = createThreadEnforcer(debug = false),
        requirements = requirements,
        networkStatus = networkStatus,
        networkUpdater = networkUpdater,
        broadcastObserver = broadcastObserver,
        inAppRatingPreferences = inAppRatingPreferences,
        proxyPreferences = proxyPreferences,
        expertPreferences = expertPreferences,
        serviceLauncher = serviceLauncher,
        appScope = appScope,
      dispatchers = dispatchers,
    )

@RunWith(RobolectricTestRunner::class)
@Config(
    // Need this here since Robolectric does not yet support API 37 (which is default otherwise)
    minSdk = Build.VERSION_CODES.O,
    maxSdk = Build.VERSION_CODES.BAKLAVA,
)
class MainViewModelerTest {

  @Test
  fun `handleToggleProxy starts the proxy when not running and no blockers exist`() = runTest {
    val state = MutableMainViewState()
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            requirements = FakeHotspotRequirements(blockers = emptySet()),
            appScope = AppCoroutineScope(appScope = backingScope),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleToggleProxy()
    awaitImmediateNextJobCompletion(backingScope)

    assertTrue(state.startBlockers.value.isEmpty())
    val started = shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService()
    assertEquals(TestForegroundService::class.java.name, started?.component?.className)
  }

  @Test
  fun `handleToggleProxy records blockers and stops the proxy when blockers exist`() = runTest {
    val state = MutableMainViewState()
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            requirements =
                FakeHotspotRequirements(blockers = setOf(HotspotStartBlocker.PERMISSION)),
            appScope = AppCoroutineScope(appScope = backingScope),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleToggleProxy()
    awaitImmediateNextJobCompletion(backingScope)

    assertEquals(setOf(HotspotStartBlocker.PERMISSION), state.startBlockers.value)
    val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
    assertEquals(TestForegroundService::class.java.name, stopped?.component?.className)
  }

  @Test
  fun `handleToggleProxy stops the proxy when running`() = runTest {
    val state = MutableMainViewState()
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            appScope = AppCoroutineScope(appScope = backingScope),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleToggleProxy()
    awaitImmediateNextJobCompletion(backingScope)

    val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
    assertEquals(TestForegroundService::class.java.name, stopped?.component?.className)
  }

  @Test
  fun `handleToggleProxy resets error state when either status is in error`() = runTest {
    val state = MutableMainViewState()
    val networkStatus =
        FakeBroadcastNetworkStatus(
            initialStatus = RunningStatus.HotspotError(RuntimeException()),
        )
    val backingScope = CoroutineScope(Job())
    val wiDiStatus = BroadcastStatus()
    val proxyStatus = ProxyStatus()
    wiDiStatus.set(RunningStatus.HotspotError(RuntimeException()), clearError = true)
    val serviceLauncher =
        ServiceLauncher(
            context = RuntimeEnvironment.getApplication(),
            foregroundServiceClass = TestForegroundService::class.java,
            wiDiStatus = wiDiStatus,
            proxyStatus = proxyStatus,
        )
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            serviceLauncher = serviceLauncher,
            appScope = AppCoroutineScope(appScope = backingScope),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleToggleProxy()
    awaitImmediateNextJobCompletion(backingScope)

    val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
    assertEquals(TestForegroundService::class.java.name, stopped?.component?.className)
    assertEquals(RunningStatus.NotRunning, wiDiStatus.get())
    assertEquals(RunningStatus.NotRunning, proxyStatus.get())
  }

  @Test
  fun `handleToggleProxy is a no-op while the hotspot is transitioning`() = runTest {
    val state = MutableMainViewState()
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Starting)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            appScope = AppCoroutineScope(appScope = backingScope),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleToggleProxy()
    awaitImmediateNextJobCompletion(backingScope)

    val application = shadowOf(RuntimeEnvironment.getApplication())
    assertEquals(null, application.peekNextStartedService())
    assertEquals(null, application.nextStoppedService)
  }

  @Test
  fun `handleDismissBlocker removes only the dismissed blocker from state`() {
    val state = MutableMainViewState()
    state.startBlockers.value = setOf(HotspotStartBlocker.PERMISSION, HotspotStartBlocker.VPN)
    val viewModeler = newViewModeler(
      state = state,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleDismissBlocker(HotspotStartBlocker.VPN)

    // Collection.minus() always yields a List regardless of the source collection type.
    assertEquals(listOf(HotspotStartBlocker.PERMISSION), state.startBlockers.value)
  }

  @Test
  fun `handlePortChanged updates state and writes preferences`() {
    val state = MutableMainViewState()
    val proxyPreferences = FakeProxyPreferences()
    val viewModeler = newViewModeler(
      state = state, proxyPreferences = proxyPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handlePortChanged(9999)

    assertEquals(9999, state.port.value)
    assertEquals(listOf(9999), proxyPreferences.setPortCalls)
  }

  @Test
  fun `handleEnabledChanged HTTP flips only the http flag and writes prefs`() {
    val state = MutableMainViewState()
    val proxyPreferences = FakeProxyPreferences()
    val viewModeler = newViewModeler(
      state = state, proxyPreferences = proxyPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleEnabledChanged(enabled = true, type = ServerPortTypes.HTTP)

    assertTrue(state.isHttpEnabled.value)
    assertEquals(listOf(true), proxyPreferences.setHttpEnabledCalls)
    assertTrue(proxyPreferences.setSocksEnabledCalls.isEmpty())
  }

  @Test
  fun `handleEnabledChanged SOCKS flips only the socks flag and writes prefs`() {
    val state = MutableMainViewState()
    val proxyPreferences = FakeProxyPreferences()
    val viewModeler = newViewModeler(
      state = state, proxyPreferences = proxyPreferences,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleEnabledChanged(enabled = true, type = ServerPortTypes.SOCKS)

    assertTrue(state.isSocksEnabled.value)
    assertEquals(listOf(true), proxyPreferences.setSocksEnabledCalls)
    assertTrue(proxyPreferences.setHttpEnabledCalls.isEmpty())
  }

  @Test
  fun `handleOpenDialog QR_CODE shows dialog only when hotspot data is valid and running`() {
    val state = MutableMainViewState()
    state.group.value =
        BroadcastNetworkStatus.GroupInfo.Connected(
            ssid = "ssid",
            password = "password",
            clients = emptyList(),
        )
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val viewModeler = newViewModeler(
      state = state, networkStatus = networkStatus,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleOpenDialog(MainViewDialogs.QR_CODE)

    assertTrue(state.isShowingQRCodeDialog.value)
  }

  @Test
  fun `handleOpenDialog QR_CODE does not show dialog when not running`() {
    val state = MutableMainViewState()
    state.group.value =
        BroadcastNetworkStatus.GroupInfo.Connected(
            ssid = "ssid",
            password = "password",
            clients = emptyList(),
        )
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val viewModeler = newViewModeler(
      state = state, networkStatus = networkStatus,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    viewModeler.handleOpenDialog(MainViewDialogs.QR_CODE)

    assertFalse(state.isShowingQRCodeDialog.value)
  }

  @Test
  fun `handleOpenDialog and handleCloseDialog toggle the simple dialog flags`() {
    val state = MutableMainViewState()
    val viewModeler = newViewModeler(
      state = state,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    val simpleDialogs =
        mapOf<MainViewDialogs, () -> Boolean>(
            MainViewDialogs.SLOW_SPEED_HELP to { state.isShowingSlowSpeedHelp.value },
            MainViewDialogs.SETUP_ERROR to { state.isShowingSetupError.value },
            MainViewDialogs.NETWORK_ERROR to { state.isShowingNetworkError.value },
            MainViewDialogs.HOTSPOT_ERROR to { state.isShowingHotspotError.value },
            MainViewDialogs.BROADCAST_ERROR to { state.isShowingBroadcastError.value },
            MainViewDialogs.PROXY_ERROR to { state.isShowingProxyError.value },
        )

    for ((dialog, isShowing) in simpleDialogs) {
      viewModeler.handleOpenDialog(dialog)
      assertTrue(isShowing(), "$dialog should be showing after open")

      viewModeler.handleCloseDialog(dialog)
      assertFalse(isShowing(), "$dialog should be hidden after close")
    }
  }

  @Test
  fun `bind refreshes connection info and closes QR dialog when the hotspot turns off`() = runTest {
    val state = MutableMainViewState()
    state.isShowingQRCodeDialog.value = true
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val networkUpdater = FakeBroadcastNetworkUpdater()
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            networkUpdater = networkUpdater,
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    val bindScope = CoroutineScope(Job())
    try {
      viewModeler.bind(bindScope) {}

      networkStatus.statusFlow.value = RunningStatus.NotRunning

      networkUpdater.updateCallCount.first { it == 1 }
      state.isShowingQRCodeDialog.first { !it }
    } finally {
      bindScope.cancel()
    }
  }

  @Test
  fun `bind refreshes connection info without closing dialogs when the hotspot turns on`() =
      runTest {
        val state = MutableMainViewState()
        val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
        val networkUpdater = FakeBroadcastNetworkUpdater()
        val viewModeler =
            newViewModeler(
                state = state,
                networkStatus = networkStatus,
                networkUpdater = networkUpdater,
              // TODO(Peter): Do we need test dispatchers?
              dispatchers = AppDispatchers.create(),
            )

        val bindScope = CoroutineScope(Job())
        try {
          viewModeler.bind(bindScope) {}

          networkStatus.statusFlow.value = RunningStatus.Running

          networkUpdater.updateCallCount.first { it == 1 }
        } finally {
          bindScope.cancel()
        }
      }

  @Test
  fun `bind shows and clears the setup error dialog as wiDi status changes`() = runTest {
    val state = MutableMainViewState()
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val proxy = FakeSharedProxy(initialStatus = RunningStatus.NotRunning)
    val viewModeler = newViewModeler(
      state = state, networkStatus = networkStatus, proxy = proxy,
      // TODO(Peter): Do we need test dispatchers?
      dispatchers = AppDispatchers.create(),
    )

    val bindScope = CoroutineScope(Job())
    try {
      viewModeler.bind(bindScope) {}

      networkStatus.statusFlow.value = RunningStatus.HotspotError(RuntimeException())
      state.isShowingSetupError.first { it }

      networkStatus.statusFlow.value = RunningStatus.NotRunning
      state.isShowingSetupError.first { !it }
    } finally {
      bindScope.cancel()
    }
  }
}
