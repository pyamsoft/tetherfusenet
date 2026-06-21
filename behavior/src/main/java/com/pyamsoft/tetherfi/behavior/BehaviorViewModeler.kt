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

package com.pyamsoft.tetherfi.behavior

import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.notify.NotifyGuard
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.StatusPreferences
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.battery.BatteryOptimizer
import com.pyamsoft.tetherfi.service.foreground.NotificationRefreshEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

class BehaviorViewModeler
@Inject
internal constructor(
    override val state: MutableBehaviorViewState,
    private val notificationRefreshBus: EventBus<NotificationRefreshEvent>,
    private val tweakPreferences: TweakPreferences,
    private val expertPreferences: ExpertPreferences,
    private val behaviorPreferences: StatusPreferences,
    private val notifyGuard: NotifyGuard,
    private val batteryOptimizer: BatteryOptimizer,
) : BehaviorViewState by state, AbstractViewModeler<BehaviorViewState>(state) {

  private data class LoadConfig(
      var tweakIgnoreVpn: Boolean,
      var tweakIgnoreLocation: Boolean,
      var tweakShutdownWithNoClients: Boolean,
      var tweakKeepScreenOn: Boolean,
      var tweakWakeLock: Boolean,
      var expertSocketTimeout: Boolean,
  )

  private fun markPreferencesLoaded(config: LoadConfig) {
    val isExpertReady = config.expertSocketTimeout

    val isRunningTweaksReady =
        config.tweakShutdownWithNoClients && config.tweakKeepScreenOn && config.tweakWakeLock

    val isIgnoreTweaksReady = config.tweakIgnoreVpn && config.tweakIgnoreLocation

    if (isRunningTweaksReady && isIgnoreTweaksReady && isExpertReady) {
      state.loadingState.value = BehaviorViewState.LoadingState.DONE
    }
  }

  override fun registerSaveState(
      registry: SaveableStateRegistry
  ): List<SaveableStateRegistry.Entry> =
      mutableListOf<SaveableStateRegistry.Entry>().apply {
        registry
            .registerProvider(KEY_SHOW_TIMEOUTS) { state.isShowingSocketTimeout.value }
            .also { add(it) }
      }

  override fun consumeRestoredState(registry: SaveableStateRegistry) {
    registry.consumeRestored(KEY_SHOW_TIMEOUTS)?.cast<Boolean>()?.also {
      state.isShowingSocketTimeout.value = it
    }
  }

  private fun loadPreferences(scope: CoroutineScope) {
    val s = state

    // If we are already loading, ignore this call
    if (s.loadingState.value != BehaviorViewState.LoadingState.NONE) {
      return
    }

    // Make this load config that we will update as things load in
    val config =
        LoadConfig(
            tweakIgnoreVpn = false,
            tweakIgnoreLocation = false,
            tweakKeepScreenOn = false,
            tweakShutdownWithNoClients = false,
            tweakWakeLock = false,
            expertSocketTimeout = false,
        )

    // Start loading
    s.loadingState.value = BehaviorViewState.LoadingState.LOADING

    scope.bindConfigPreferences(config)
    scope.bindServerPreferences(config)
    scope.bindBehaviorPreferences(config)
  }

  private fun CoroutineScope.bindServerPreferences(config: LoadConfig) {
    val scope = this
    val s = state

    // Always populate the latest ignore value
    tweakPreferences.listenForStartIgnoreVpn().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { ignore ->
          s.isIgnoreVpn.value = ignore

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.tweakIgnoreVpn = true
            markPreferencesLoaded(config)
          }
        }
      }
    }

    // Always populate the latest ignore value
    tweakPreferences.listenForStartIgnoreLocation().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { ignore ->
          s.isIgnoreLocation.value = ignore

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.tweakIgnoreLocation = true
            markPreferencesLoaded(config)
          }
        }
      }
    }

    // Always populate the latest shutdown value
    tweakPreferences.listenForShutdownWithNoClients().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { shutdown ->
          s.isShutdownWithNoClients.value = shutdown

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.tweakWakeLock = true
            markPreferencesLoaded(config)
          }
        }
      }
    }

    // Always populate the latest wakelock value
    tweakPreferences.listenForWakeLock().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { wakelock ->
          s.isHoldWakelock.value = wakelock

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.tweakShutdownWithNoClients = true
            markPreferencesLoaded(config)
          }
        }
      }
    }
  }

  private fun CoroutineScope.bindBehaviorPreferences(config: LoadConfig) {
    val scope = this
    val s = state

    // Always populate the latest keep screen on value
    behaviorPreferences.listenForKeepScreenOn().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { timeout ->
          s.isKeepScreenOn.value = timeout

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.tweakKeepScreenOn = true
            markPreferencesLoaded(config)
          }
        }
      }
    }
  }

  private fun CoroutineScope.bindConfigPreferences(config: LoadConfig) {
    val scope = this
    val s = state

    // Always populate the latest socket timeout value
    expertPreferences.listenForSocketTimeout().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { timeout ->
          s.socketTimeout.value = timeout

          // Watch constantly but only update the initial load config if we haven't loaded yet
          if (s.loadingState.value != BehaviorViewState.LoadingState.DONE) {
            config.expertSocketTimeout = true
            markPreferencesLoaded(config)
          }
        }
      }
    }
  }

  fun bind(
      scope: CoroutineScope,
  ) {
    loadPreferences(scope)
  }

  fun bindLifecycleResumed(
      scope: CoroutineScope,
      onRefreshConnectionInfo: () -> Unit,
  ) {
    scope.launch(context = Dispatchers.Main) {
      // Refresh system info
      handleRefreshSystemInfo(this)

      // Refresh connection info
      onRefreshConnectionInfo()
    }
  }

  fun handleRefreshSystemInfo(scope: CoroutineScope) {
    scope.launch(context = Dispatchers.Default) {
      val s = state

      // Battery optimization
      s.isBatteryOptimizationsIgnored.value = batteryOptimizer.isOptimizationsIgnored()

      // Notifications
      s.hasNotificationPermission.value = notifyGuard.canPostNotification()

      // Tell the service to refresh
      notificationRefreshBus.emit(NotificationRefreshEvent)
    }
  }

  fun handleOpenDialog(dialog: BehaviorViewDialogs) =
      when (dialog) {
        BehaviorViewDialogs.SOCKET_TIMEOUT -> {
          state.isShowingSocketTimeout.value = true
        }
      }

  fun handleCloseDialog(dialog: BehaviorViewDialogs) =
      when (dialog) {
        BehaviorViewDialogs.SOCKET_TIMEOUT -> {
          state.isShowingSocketTimeout.value = false
        }
      }

  fun handleToggleTweak(tweak: BehaviorViewTweaks) =
      when (tweak) {
        BehaviorViewTweaks.IGNORE_VPN -> {
          val newVal = state.isIgnoreVpn.updateAndGet { !it }
          tweakPreferences.setStartIgnoreVpn(newVal)
        }
        BehaviorViewTweaks.IGNORE_LOCATION -> {
          val newVal = state.isIgnoreLocation.updateAndGet { !it }
          tweakPreferences.setStartIgnoreLocation(newVal)
        }
        BehaviorViewTweaks.KEEP_SCREEN_ON -> {
          val newVal = state.isKeepScreenOn.updateAndGet { !it }
          behaviorPreferences.setKeepScreenOn(newVal)
        }
        BehaviorViewTweaks.SHUTDOWN_NO_CLIENTS -> {
          val newVal = state.isShutdownWithNoClients.updateAndGet { !it }
          tweakPreferences.setShutdownWithNoClients(newVal)
        }
        BehaviorViewTweaks.USE_WAKELOCK -> {
          val newVal = state.isHoldWakelock.updateAndGet { !it }
          tweakPreferences.setWakeLock(newVal)
        }
      }

  fun handleUpdateSocketTimeout(timeout: ServerSocketTimeout) {
    val newVal = state.socketTimeout.updateAndGet { timeout }
    expertPreferences.setSocketTimeout(newVal)
  }

  companion object {

    private const val KEY_SHOW_TIMEOUTS = "key_show_timeout"
  }
}
