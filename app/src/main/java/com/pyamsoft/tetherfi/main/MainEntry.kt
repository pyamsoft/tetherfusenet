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

import android.provider.Settings
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.pydroid.ui.inject.rememberComposableInjector
import com.pyamsoft.pydroid.ui.util.fillUpToPortraitSize
import com.pyamsoft.pydroid.ui.util.rememberNotNull
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.qr.QRCodeEntry
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import com.pyamsoft.tetherfi.ui.dialog.SlowSpeedsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets up permission request interaction */
@Composable
private fun RegisterPermissionRequests(
    permissionResponseBus: Flow<PermissionResponse>,
    onToggleProxy: CoroutineScope.() -> Unit,
) {
  // Create requesters
  val handleToggleProxy by rememberUpdatedState(onToggleProxy)

  LaunchedEffect(
      permissionResponseBus,
  ) {
    // See MainActivity
    permissionResponseBus.flowOn(context = Dispatchers.Default).also { f ->
      launch(context = Dispatchers.Default) {
        f.collect { resp ->
          when (resp) {
            is PermissionResponse.RefreshNotification -> {
              // Blank
            }
            is PermissionResponse.ToggleProxy -> {
              handleToggleProxy()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WatchTabSwipe(
    pagerState: PagerState,
    allTabs: List<MainView>,
) {
  val hapticManager = LocalHapticManager.current

  // Watch for a swipe causing a page change and update accordingly
  LaunchedEffect(
      pagerState,
      allTabs,
      hapticManager,
  ) {
    // We don't need to remember and re-render off this, so it's fine here
    // We keep track of the previous page to avoid having the device
    // buzz when the tabs "initially mount"
    var previousPage: MainView? = null

    snapshotFlow { pagerState.targetPage }
        .distinctUntilChanged()
        .mapNotNull { allTabs.getOrNull(it) }
        .collect { page ->
          Timber.d { "Page swiped: $page" }
          // Buzz only when we are caused by a Swipe
          // since, by default, this is mounted with NULL
          if (previousPage != null) {
            withContext(context = Dispatchers.Main) { hapticManager?.actionButtonPress() }
          }
          previousPage = page
        }
  }
}

@Composable
private fun MountHooks(
    viewModel: MainViewModeler,
    pagerState: PagerState,
    allTabs: List<MainView>,
    permissionResponseBus: Flow<PermissionResponse>,
    onToggleProxy: CoroutineScope.() -> Unit,
    onShowInAppRating: () -> Unit,
) {
  val handleShowInAppRating by rememberUpdatedState(onShowInAppRating)

  SaveStateDisposableEffect(viewModel)

  WatchTabSwipe(
      pagerState = pagerState,
      allTabs = allTabs,
  )

  // As early as possible because of Lifecycle quirks
  RegisterPermissionRequests(
      permissionResponseBus = permissionResponseBus,
      onToggleProxy = { onToggleProxy() },
  )

  LaunchedEffect(viewModel) {
    viewModel.bind(
        scope = this,
        onShowInAppRating = { handleShowInAppRating() },
    )
  }

  LifecycleEventEffect(
      event = Lifecycle.Event.ON_START,
  ) {
    viewModel.handleAnalyticsMarkOpened()
  }
}

@Composable
fun MainEntry(
    modifier: Modifier = Modifier,
    appName: String,
    allTabs: List<MainView>,
    pagerState: PagerState,

    // Action
    onShowInAppRating: () -> Unit,
    onLaunchIntent: (String) -> Unit,

    // Tile
    onUpdateTile: (RunningStatus) -> Unit,
) {
  val handleShowInAppRating by rememberUpdatedState(onShowInAppRating)

  val scope = rememberCoroutineScope()
  val component = rememberComposableInjector { MainInjector() }
  val viewModel = rememberNotNull(component.viewModel)
  val permissionRequestBus = rememberNotNull(component.permissionRequestBus)
  val permissionResponseBus = rememberNotNull(component.permissionResponseBus)

  // TODO Experimental
  // val experimentalRuntimeFlags = rememberNotNull(component.experimentalRuntimeFlags)

  // Use the LifecycleOwner.CoroutineScope (Activity usually)
  // so that the scope does not die because of navigation events
  val owner = LocalLifecycleOwner.current
  val lifecycleScope = owner.lifecycleScope

  val handleTabSelected by rememberUpdatedState { tab: MainView ->
    // Click fires the index to update
    // The index updating is caught by the snapshot flow
    // Which then triggers the page update function
    val index = allTabs.indexOf(tab)
    scope.launch(context = Dispatchers.Main) { pagerState.animateScrollToPage(index) }
  }

  MountHooks(
      viewModel = viewModel,
      permissionResponseBus = permissionResponseBus,
      pagerState = pagerState,
      allTabs = allTabs,
      onShowInAppRating = { handleShowInAppRating() },
      onToggleProxy = { viewModel.handleToggleProxy() },
  )

  MainScreen(
      modifier = modifier,
      appName = appName,
      state = viewModel,
      pagerState = pagerState,
      allTabs = allTabs,
      onTabChanged = { handleTabSelected(it) },
      onShowQRCode = { viewModel.handleOpenDialog(MainViewDialogs.QR_CODE) },
      onRefreshConnection = { viewModel.handleRefreshConnectionInfo(scope) },
      onJumpToHowTo = { handleTabSelected(MainView.INFO) },
      onShowSlowSpeedHelp = { viewModel.handleOpenDialog(MainViewDialogs.SLOW_SPEED_HELP) },
      onToggleProxy = { viewModel.handleToggleProxy() },
      onOpenNetworkError = { viewModel.handleOpenDialog(MainViewDialogs.NETWORK_ERROR) },
      onOpenBroadcastError = { viewModel.handleOpenDialog(MainViewDialogs.BROADCAST_ERROR) },
      onOpenProxyError = { viewModel.handleOpenDialog(MainViewDialogs.PROXY_ERROR) },
      onOpenHotspotError = { viewModel.handleOpenDialog(MainViewDialogs.HOTSPOT_ERROR) },
      onHttpEnabledChanged = { viewModel.handleEnabledChanged(it, ServerPortTypes.HTTP) },
      onSocksEnabledChanged = { viewModel.handleEnabledChanged(it, ServerPortTypes.SOCKS) },
      onPortChanged = { viewModel.handlePortChanged(it) },
      onUpdateTile = onUpdateTile,
      onLaunchIntent = onLaunchIntent,
  )

  MainDialogs(
      dialogModifier = Modifier.fillUpToPortraitSize().widthIn(max = LANDSCAPE_MAX_WIDTH),
      state = viewModel,
      appName = appName,
      onDismissBlocker = { viewModel.handleDismissBlocker(it) },
      onDismissSetupError = { viewModel.handleCloseDialog(MainViewDialogs.SETUP_ERROR) },
      onHideNetworkError = { viewModel.handleCloseDialog(MainViewDialogs.NETWORK_ERROR) },
      onHideBroadcastError = { viewModel.handleCloseDialog(MainViewDialogs.BROADCAST_ERROR) },
      onHideProxyError = { viewModel.handleCloseDialog(MainViewDialogs.PROXY_ERROR) },
      onHideHotspotError = { viewModel.handleCloseDialog(MainViewDialogs.HOTSPOT_ERROR) },
      onRequestPermissions = {
        // Request permissions
        lifecycleScope.launch(context = Dispatchers.Default) {
          // See MainActivity
          permissionRequestBus.emit(PermissionRequests.Server)
        }
      },
      onOpenPermissionSettings = { onLaunchIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
      onOpenLocationSettings = { onLaunchIntent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
      onOpenBackroundDataSettings = {
        onLaunchIntent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS)
      },
  )

  val isShowingQRCodeDialog by viewModel.isShowingQRCodeDialog.collectAsStateWithLifecycle()
  if (isShowingQRCodeDialog) {
    val group by viewModel.group.collectAsStateWithLifecycle()

    group.cast<BroadcastNetworkStatus.GroupInfo.Connected>()?.also { grp ->
      QRCodeEntry(
          Modifier.fillUpToPortraitSize()
              .widthIn(
                  max = LANDSCAPE_MAX_WIDTH,
              ),
          ssid = grp.ssid,
          password = grp.password,
          onDismiss = { viewModel.handleCloseDialog(MainViewDialogs.QR_CODE) },
      )
    }
  }

  val isShowingSlowSpeedHelp by viewModel.isShowingSlowSpeedHelp.collectAsStateWithLifecycle()
  if (isShowingSlowSpeedHelp) {
    SlowSpeedsDialog(
        modifier = Modifier.fillUpToPortraitSize().widthIn(max = LANDSCAPE_MAX_WIDTH),
        onDismiss = { viewModel.handleCloseDialog(MainViewDialogs.SLOW_SPEED_HELP) },
    )
  }
}
