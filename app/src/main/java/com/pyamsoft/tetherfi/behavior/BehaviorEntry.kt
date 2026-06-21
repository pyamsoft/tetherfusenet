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

import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.ui.inject.rememberComposableInjector
import com.pyamsoft.pydroid.ui.util.rememberNotNull
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import com.pyamsoft.tetherfi.ui.ServerViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Sets up permission request interaction */
@Composable
private fun RegisterPermissionRequests(
    permissionResponseBus: Flow<PermissionResponse>,
    onRefreshSystemInfo: CoroutineScope.() -> Unit,
) {
  // Create requesters
  val handleRefreshSystemInfo by rememberUpdatedState(onRefreshSystemInfo)

  LaunchedEffect(
      permissionResponseBus,
  ) {
    // See MainActivity
    permissionResponseBus.flowOn(context = Dispatchers.Default).also { f ->
      launch(context = Dispatchers.Default) {
        f.collect { resp ->
          when (resp) {
            is PermissionResponse.RefreshNotification -> {
              // Call to the VM to refresh info
              handleRefreshSystemInfo(this)
            }
            is PermissionResponse.ToggleProxy -> {
              // Blank
            }
          }
        }
      }
    }
  }
}

/** On mount hooks */
@Composable
private fun MountHooks(
    viewModel: BehaviorViewModeler,
    permissionResponseBus: Flow<PermissionResponse>,
    onRefreshConnection: () -> Unit,
) {
  val scope = rememberCoroutineScope()

  val handleRefreshSystemInfo by rememberUpdatedState { s: CoroutineScope ->
    viewModel.handleRefreshSystemInfo(scope = s)
  }

  // As early as possible because of Lifecycle quirks
  RegisterPermissionRequests(
      permissionResponseBus = permissionResponseBus,
      onRefreshSystemInfo = { handleRefreshSystemInfo(this) },
  )

  SaveStateDisposableEffect(viewModel)

  LaunchedEffect(
      viewModel,
  ) {
    viewModel.bind(scope = this)
  }

  val handleRefreshConnectionInfo by rememberUpdatedState { onRefreshConnection() }
  val bindLifecycleResumed by rememberUpdatedState {
    viewModel.bindLifecycleResumed(
        scope = scope,
        onRefreshConnectionInfo = { handleRefreshConnectionInfo() },
    )
  }
  LifecycleEventEffect(
      event = Lifecycle.Event.ON_RESUME,
  ) {
    bindLifecycleResumed()
  }
}

@Composable
fun BehaviorEntry(
    modifier: Modifier = Modifier,
    appName: String,
    lazyListState: LazyListState,
    serverViewState: ServerViewState,

    // Actions
    onRefreshConnection: () -> Unit,
    onLaunchIntent: (String) -> Unit,
) {
  val component = rememberComposableInjector { BehaviorInjector() }
  val viewModel = rememberNotNull(component.viewModel)
  val permissionRequestBus = rememberNotNull(component.permissionRequestBus)
  val permissionResponseBus = rememberNotNull(component.permissionResponseBus)

  // Use the LifecycleOwner.CoroutineScope (Activity usually)
  // so that the scope does not die because of navigation events
  val owner = LocalLifecycleOwner.current
  val lifecycleScope = owner.lifecycleScope

  // Hooks that run on mount
  MountHooks(
      viewModel = viewModel,
      permissionResponseBus = permissionResponseBus,
      onRefreshConnection = onRefreshConnection,
  )

  BehaviorScreen(
      modifier = modifier,
      lazyListState = lazyListState,
      state = viewModel,
      serverViewState = serverViewState,
      appName = appName,
      onOpenBatterySettings = {
        onLaunchIntent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      },
      onRequestNotificationPermission = {
        lifecycleScope.launch(context = Dispatchers.Default) {
          // See MainActivity
          permissionRequestBus.emit(PermissionRequests.Notification)
        }
      },
      onToggleIgnoreVpn = { viewModel.handleToggleTweak(BehaviorViewTweaks.IGNORE_VPN) },
      onToggleIgnoreLocation = { viewModel.handleToggleTweak(BehaviorViewTweaks.IGNORE_LOCATION) },
      onToggleShutdownWithNoClients = {
        viewModel.handleToggleTweak(BehaviorViewTweaks.SHUTDOWN_NO_CLIENTS)
      },
      onToggleKeepScreenOn = { viewModel.handleToggleTweak(BehaviorViewTweaks.KEEP_SCREEN_ON) },
      onHideSocketTimeout = { viewModel.handleCloseDialog(BehaviorViewDialogs.SOCKET_TIMEOUT) },
      onShowSocketTimeout = { viewModel.handleOpenDialog(BehaviorViewDialogs.SOCKET_TIMEOUT) },
      onUpdateSocketTimeout = { viewModel.handleUpdateSocketTimeout(it) },
      onToggleWakeLock = { viewModel.handleToggleTweak(BehaviorViewTweaks.USE_WAKELOCK) },
  )
}
