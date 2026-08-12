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

package com.pyamsoft.tetherfi.behavior

import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.notify.NotifyGuard
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.StatusPreferences
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.battery.BatteryOptimizer
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.service.foreground.NotificationRefreshEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeTweakPreferences : TweakPreferences {
  val ignoreVpnCalls = mutableListOf<Boolean>()
  val ignoreLocationCalls = mutableListOf<Boolean>()
  val shutdownWithNoClientsCalls = mutableListOf<Boolean>()
  val wakeLockCalls = mutableListOf<Boolean>()

  override fun listenForStartIgnoreVpn(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreVpn(ignore: Boolean) {
    ignoreVpnCalls.add(ignore)
  }

  override fun listenForStartIgnoreLocation(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreLocation(ignore: Boolean) {
    ignoreLocationCalls.add(ignore)
  }

  override fun listenForShutdownWithNoClients(): Flow<Boolean> = MutableStateFlow(false)

  override fun setShutdownWithNoClients(shutdown: Boolean) {
    shutdownWithNoClientsCalls.add(shutdown)
  }

  override fun listenForWakeLock(): Flow<Boolean> = MutableStateFlow(false)

  override fun setWakeLock(wakelock: Boolean) {
    wakeLockCalls.add(wakelock)
  }
}

private class FakeExpertPreferences : ExpertPreferences {
  val socketTimeoutCalls = mutableListOf<ServerSocketTimeout>()

  override fun listenForSocketTimeout(): Flow<ServerSocketTimeout> =
      MutableStateFlow(ServerSocketTimeout.Defaults.BALANCED)

  override fun setSocketTimeout(limit: ServerSocketTimeout) {
    socketTimeoutCalls.add(limit)
  }

  override fun listenForBroadcastType(): Flow<BroadcastType> =
      MutableStateFlow(BroadcastType.entries.first())

  override fun setBroadcastType(type: BroadcastType) = Unit

  override fun listenForPreferredNetwork(): Flow<PreferredNetwork> =
      MutableStateFlow(PreferredNetwork.entries.first())

  override fun setPreferredNetwork(network: PreferredNetwork) = Unit
}

private class FakeStatusPreferences : StatusPreferences {
  override fun listenForKeepScreenOn(): Flow<Boolean> = MutableStateFlow(false)

  override fun setKeepScreenOn(keep: Boolean) = Unit
}

private class FakeNotifyGuard(private val canPost: Boolean) : NotifyGuard {
  override fun canPostNotification(): Boolean = canPost
}

private class FakeBatteryOptimizer(private val ignored: Boolean) : BatteryOptimizer {
  override suspend fun isOptimizationsIgnored(): Boolean = ignored
}

private fun newViewModeler(
    state: MutableBehaviorViewState = MutableBehaviorViewState(),
    notificationRefreshBus: EventBus<NotificationRefreshEvent> = EventBus.create(),
    tweakPreferences: TweakPreferences = FakeTweakPreferences(),
    expertPreferences: ExpertPreferences = FakeExpertPreferences(),
    behaviorPreferences: StatusPreferences = FakeStatusPreferences(),
    notifyGuard: NotifyGuard = FakeNotifyGuard(true),
    batteryOptimizer: BatteryOptimizer = FakeBatteryOptimizer(true),
): BehaviorViewModeler =
    BehaviorViewModeler(
        state = state,
        notificationRefreshBus = notificationRefreshBus,
        tweakPreferences = tweakPreferences,
        expertPreferences = expertPreferences,
        behaviorPreferences = behaviorPreferences,
        notifyGuard = notifyGuard,
        batteryOptimizer = batteryOptimizer,
    )

class BehaviorViewModelerTest {

  @Test
  fun `bind loads preferences and marks loading state done`() = runTest {
    val state = MutableBehaviorViewState()
    val viewModeler = newViewModeler(state = state)

    val bindScope = CoroutineScope(Job())
    try {
      viewModeler.bind(bindScope)

      val finalState = state.loadingState.first { it == BehaviorViewState.LoadingState.DONE }
      assertEquals(BehaviorViewState.LoadingState.DONE, finalState)
    } finally {
      bindScope.cancel()
    }
  }

  @Test
  fun `bind is a no-op while already loading`() = runTest {
    val tweakPreferences = FakeTweakPreferences()
    val state = MutableBehaviorViewState()
    val viewModeler = newViewModeler(state = state, tweakPreferences = tweakPreferences)

    val bindScope = CoroutineScope(Job())
    try {
      viewModeler.bind(bindScope)
      state.loadingState.first { it == BehaviorViewState.LoadingState.DONE }

      // A second bind() call while already loaded is still a no-op re-entrancy guard: it only
      // fires when loadingState is NONE, and it never resets back to NONE.
      viewModeler.bind(bindScope)

      assertEquals(BehaviorViewState.LoadingState.DONE, state.loadingState.value)
    } finally {
      bindScope.cancel()
    }
  }

  @Test
  fun `handleToggleTweak IGNORE_VPN flips state and writes pref`() {
    val state = MutableBehaviorViewState()
    val tweakPreferences = FakeTweakPreferences()
    val viewModeler = newViewModeler(state = state, tweakPreferences = tweakPreferences)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.IGNORE_VPN)

    assertTrue(state.isIgnoreVpn.value)
    assertEquals(listOf(true), tweakPreferences.ignoreVpnCalls)
  }

  @Test
  fun `handleToggleTweak IGNORE_LOCATION flips state and writes pref`() {
    val state = MutableBehaviorViewState()
    val tweakPreferences = FakeTweakPreferences()
    val viewModeler = newViewModeler(state = state, tweakPreferences = tweakPreferences)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.IGNORE_LOCATION)

    assertTrue(state.isIgnoreLocation.value)
    assertEquals(listOf(true), tweakPreferences.ignoreLocationCalls)
  }

  @Test
  fun `handleToggleTweak KEEP_SCREEN_ON flips state`() {
    val state = MutableBehaviorViewState()
    val viewModeler = newViewModeler(state = state)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.KEEP_SCREEN_ON)
    assertTrue(state.isKeepScreenOn.value)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.KEEP_SCREEN_ON)
    assertFalse(state.isKeepScreenOn.value)
  }

  @Test
  fun `handleToggleTweak SHUTDOWN_NO_CLIENTS flips state and pref`() {
    val state = MutableBehaviorViewState()
    val tweakPreferences = FakeTweakPreferences()
    val viewModeler = newViewModeler(state = state, tweakPreferences = tweakPreferences)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.SHUTDOWN_NO_CLIENTS)

    assertTrue(state.isShutdownWithNoClients.value)
    assertEquals(listOf(true), tweakPreferences.shutdownWithNoClientsCalls)
  }

  @Test
  fun `handleToggleTweak USE_WAKELOCK flips state and writes pref`() {
    val state = MutableBehaviorViewState()
    val tweakPreferences = FakeTweakPreferences()
    val viewModeler = newViewModeler(state = state, tweakPreferences = tweakPreferences)

    viewModeler.handleToggleTweak(BehaviorViewTweaks.USE_WAKELOCK)

    assertTrue(state.isHoldWakelock.value)
    assertEquals(listOf(true), tweakPreferences.wakeLockCalls)
  }

  @Test
  fun `handleUpdateSocketTimeout updates state and writes pref`() {
    val state = MutableBehaviorViewState()
    val expertPreferences = FakeExpertPreferences()
    val viewModeler = newViewModeler(state = state, expertPreferences = expertPreferences)

    viewModeler.handleUpdateSocketTimeout(ServerSocketTimeout.Defaults.NICE)

    assertEquals(ServerSocketTimeout.Defaults.NICE, state.socketTimeout.value)
    assertEquals(
        listOf<ServerSocketTimeout>(ServerSocketTimeout.Defaults.NICE),
        expertPreferences.socketTimeoutCalls,
    )
  }

  @Test
  fun `handleOpenDialog and handleCloseDialog toggle SOCKET_TIMEOUT`() {
    val state = MutableBehaviorViewState()
    val viewModeler = newViewModeler(state = state)

    viewModeler.handleOpenDialog(BehaviorViewDialogs.SOCKET_TIMEOUT)
    assertTrue(state.isShowingSocketTimeout.value)

    viewModeler.handleCloseDialog(BehaviorViewDialogs.SOCKET_TIMEOUT)
    assertFalse(state.isShowingSocketTimeout.value)
  }

  @Test
  fun `handleRefreshSystemInfo updates battery and notification state and emits refresh event`() =
      runTest {
        val state = MutableBehaviorViewState()
        val notificationRefreshBus = EventBus.create<NotificationRefreshEvent>()
        val viewModeler =
            newViewModeler(
                state = state,
                notificationRefreshBus = notificationRefreshBus,
                notifyGuard = FakeNotifyGuard(true),
                batteryOptimizer = FakeBatteryOptimizer(true),
            )

        // Queue this up here
        val awaiter = async { notificationRefreshBus.first() }

        // Wait for the bus subscriber to arrive
        //
        // we must do this first or else this test will just hang forever
        notificationRefreshBus.subscriptionCount.first { it > 0 }

        viewModeler.handleRefreshSystemInfo(this)

        assertEquals(NotificationRefreshEvent, awaiter.await())
        assertTrue(state.isBatteryOptimizationsIgnored.value)
        assertTrue(state.hasNotificationPermission.value)
      }
}
