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

package com.pyamsoft.tetherfi.tile

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.createThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastStatus
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.proxy.ProxyStatus
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.ServiceLauncher
import com.pyamsoft.tetherfi.service.prereq.HotspotRequirements
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.service.tile.TileHandler
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

private class FakeHotspotRequirements(
    private val blockers: Collection<HotspotStartBlocker> = emptySet(),
) : HotspotRequirements {
  override suspend fun blockers(): Collection<HotspotStartBlocker> = blockers
}

@CheckResult
private fun newServiceLauncher(): ServiceLauncher =
    ServiceLauncher(
        context = RuntimeEnvironment.getApplication(),
        foregroundServiceClass = TestForegroundService::class.java,
        wiDiStatus = BroadcastStatus(),
        proxyStatus = ProxyStatus(),
    )

@CheckResult
private fun newViewModeler(
    dispatchers: AppDispatchers,
    state: MutableProxyTileViewState = MutableProxyTileViewState(),
    networkStatus: BroadcastNetworkStatus = FakeBroadcastNetworkStatus(),
    proxy: SharedProxy = FakeSharedProxy(),
    requirements: HotspotRequirements = FakeHotspotRequirements(),
    serviceLauncher: ServiceLauncher = newServiceLauncher(),
    appScope: AppCoroutineScope = AppCoroutineScope(appScope = CoroutineScope(Job())),
): ProxyTileViewModeler =
    ProxyTileViewModeler(
        state = state,
        handler =
            TileHandler(
                enforcer = createThreadEnforcer(debug = false),
                networkStatus = networkStatus,
                proxy = proxy,
                dispatchers = dispatchers,
            ),
        serviceLauncher = serviceLauncher,
        requirements = requirements,
        appScope = appScope,
        dispatchers = dispatchers,
    )

@RunWith(RobolectricTestRunner::class)
@Config(
    // Need this here since Robolectric does not yet support API 37 (which is default otherwise)
    minSdk = Build.VERSION_CODES.O,
    maxSdk = Build.VERSION_CODES.BAKLAVA,
)
class ProxyTileViewModelerTest {

  @Test
  fun `init reads the overall status from the handler`() {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val state = MutableProxyTileViewState()

    @SuppressLint("CheckResult")
    newViewModeler(
        state = state,
        networkStatus = networkStatus,
        dispatchers = AppDispatchers.create(),
    )

    assertEquals(RunningStatus.Running, state.status.value)
  }

  @Test
  fun `TOGGLE starts the proxy when not running and no blockers exist`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            networkStatus = networkStatus,
            requirements = FakeHotspotRequirements(blockers = emptySet()),
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleToggleProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertFalse(noActionCalled)
    val started = shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService()
    assertEquals(TestForegroundService::class.java.name, started?.component?.className)
  }

  @Test
  fun `TOGGLE stops the proxy when running`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            networkStatus = networkStatus,
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleToggleProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertFalse(noActionCalled)
    val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
    assertEquals(TestForegroundService::class.java.name, stopped?.component?.className)
  }

  @Test
  fun `TOGGLE is a no-op while an operation is in progress`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Starting)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            networkStatus = networkStatus,
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleToggleProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertTrue(noActionCalled)
    assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService())
  }

  @Test
  fun `START is a no-op when already running`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.Running)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            networkStatus = networkStatus,
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleStartProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertTrue(noActionCalled)
    assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService())
  }

  @Test
  fun `STOP is a no-op when not runningg`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            networkStatus = networkStatus,
            requirements = FakeHotspotRequirements(blockers = emptySet()),
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleStopProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertTrue(noActionCalled)
    assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService)
  }

  private fun TestScope.blockerTest(blocker: HotspotStartBlocker, message: String) {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val state = MutableProxyTileViewState()
    val backingScope = CoroutineScope(Job())
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            requirements = FakeHotspotRequirements(blockers = setOf(blocker)),
            appScope = AppCoroutineScope(appScope = backingScope),
            dispatchers = testAppDispatchers(),
        )
    var noActionCalled = false

    viewModeler.handleStartProxy { noActionCalled = true }

    @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

    assertFalse(noActionCalled)
    val status = state.status.value
    assertTrue(status is RunningStatus.HotspotError)
    assertTrue(
        status.throwable.message
            .orEmpty()
            .contains(
                message,
                ignoreCase = true,
            )
    )
    val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
    assertEquals(TestForegroundService::class.java.name, stopped?.component?.className)
  }

  @Test
  fun `Permission blockers`() = runTest {
    blockerTest(HotspotStartBlocker.PERMISSION, "permission")

    // While not required this is a blocker unless the override exists.
    blockerTest(HotspotStartBlocker.VPN, "vpn")
  }

  @Test
  fun `handleDismissed hides the tile`() {
    val state = MutableProxyTileViewState()
    state.isShowing.value = true
    val viewModeler =
        newViewModeler(
            state = state,
            dispatchers = AppDispatchers.create(),
        )

    viewModeler.handleDismissed()

    assertFalse(state.isShowing.value)
  }

  @Test
  fun `bind wires handler status callbacks through to state`() = runTest {
    val networkStatus = FakeBroadcastNetworkStatus(initialStatus = RunningStatus.NotRunning)
    val proxy = FakeSharedProxy(initialStatus = RunningStatus.NotRunning)
    val state = MutableProxyTileViewState()
    val viewModeler =
        newViewModeler(
            state = state,
            networkStatus = networkStatus,
            proxy = proxy,
            dispatchers = testAppDispatchers(),
        )

    val bindScope = CoroutineScope(Job())
    try {
      viewModeler.bind(bindScope)

      networkStatus.statusFlow.value = RunningStatus.Running

      @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

      assertEquals(RunningStatus.Running, state.status.value)

      networkStatus.statusFlow.value = RunningStatus.Stopping

      @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

      assertEquals(RunningStatus.Stopping, state.status.value)

      networkStatus.statusFlow.value = RunningStatus.NotRunning

      @OptIn(ExperimentalCoroutinesApi::class) advanceUntilIdle()

      assertEquals(RunningStatus.NotRunning, state.status.value)
    } finally {
      bindScope.cancel()
    }
  }
}
