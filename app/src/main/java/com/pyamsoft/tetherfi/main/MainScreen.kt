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

package com.pyamsoft.tetherfi.main

import androidx.annotation.CheckResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.ui.util.rememberAsStateList
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.annotations.TestOnly

private val SCROLL_THRESHOLD = 48.dp

// 1500 ish means "anything slightly faster than just a scroll from a thumb"
// 1900 is just about "i actually meant to fling scroll"
// 2400 is "really this is a deliberate scroll"

private val FLING_THRESHOLD = 1900.dp

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    appName: String,
    state: MainViewState,
    pagerState: PagerState,
    allTabs: List<MainView>,

    // Main
    onHttpEnabledChanged: (Boolean) -> Unit,
    onSocksEnabledChanged: (Boolean) -> Unit,
    onPortChanged: (Int) -> Unit,

    // Settings
    onTabChanged: (MainView) -> Unit,

    // Actions
    onShowQRCode: () -> Unit,
    onRefreshConnection: () -> Unit,
    onJumpToHowTo: () -> Unit,
    onLaunchIntent: (String) -> Unit,
    onShowSlowSpeedHelp: () -> Unit,
    onToggleProxy: () -> Unit,

    // Dialogs
    onOpenNetworkError: () -> Unit,
    onOpenHotspotError: () -> Unit,
    onOpenProxyError: () -> Unit,
    onOpenBroadcastError: () -> Unit,

    // Tile
    onUpdateTile: (RunningStatus) -> Unit,
) {
  val (snackbarError, setSnackbarError) = remember { mutableStateOf<ServerPortTypes?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }

  val (isBottomBarVisible, setBottomBarVisible) = remember { mutableStateOf(true) }
  val bottomBarScrollConnection = rememberBottomBarScrollConnection { setBottomBarVisible(it) }

  // Whenever the page changes, make the bar visible again
  LaunchedEffect(pagerState.currentPage) {
    setBottomBarVisible(true)
  }

  LaunchedEffect(snackbarError, snackbarHostState, setSnackbarError) {
    if (snackbarError != null) {
      snackbarHostState.showSnackbar(
          message = "You must enable at least one proxy type.",
          duration = SnackbarDuration.Short,
      )

      setSnackbarError(null)
    }
  }

  Scaffold(
      modifier = modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        MainTopBar(
            modifier = Modifier.fillMaxWidth(),
            appName = appName,
        )
      },
  ) { pv ->
    val layoutDirection = LocalLayoutDirection.current
    Box(
        modifier =
            Modifier.padding(
                    // Do NOT use bottom padding so that we can "full bleed" into the nav bar
                    top = remember(pv) { pv.calculateTopPadding() },
                    start =
                        remember(pv, layoutDirection) { pv.calculateStartPadding(layoutDirection) },
                    end = remember(pv, layoutDirection) { pv.calculateEndPadding(layoutDirection) },
                )
                // Watch scrolling on this container
                .nestedScroll(bottomBarScrollConnection),
        contentAlignment = Alignment.Center,
    ) {
      MainContent(
          modifier = Modifier.fillMaxSize(),
          appName = appName,
          pagerState = pagerState,
          state = state,
          allTabs = allTabs,
          onShowQRCode = onShowQRCode,
          onRefreshConnection = onRefreshConnection,
          onJumpToHowTo = onJumpToHowTo,
          onUpdateTile = onUpdateTile,
          onLaunchIntent = onLaunchIntent,
          onShowSlowSpeedHelp = onShowSlowSpeedHelp,
          onToggleProxy = onToggleProxy,
          onOpenNetworkError = onOpenNetworkError,
          onOpenHotspotError = onOpenHotspotError,
          onOpenProxyError = onOpenProxyError,
          onOpenBroadcastError = onOpenBroadcastError,
          onHttpEnabledChanged = onHttpEnabledChanged,
          onSocksEnabledChanged = onSocksEnabledChanged,
          onPortChanged = onPortChanged,
          onEnableChangeFailed = { setSnackbarError(it) },
      )

      AnimatedVisibility(
          modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
          visible = isBottomBarVisible,
          enter = slideInVertically { it },
          exit = slideOutVertically { it },
      ) {
        MainBottomBar(
            modifier = Modifier.fillMaxWidth(),
            pagerState = pagerState,
            allTabs = allTabs,
            onTabChanged = onTabChanged,
        )
      }
    }
  }
}

@Composable
@CheckResult
private fun rememberBottomBarScrollConnection(
    onVisibilityChanged: (visible: Boolean) -> Unit
): NestedScrollConnection {
  val handleVisibilityChanged by rememberUpdatedState(onVisibilityChanged)

  val density = LocalDensity.current
  val scrollThreshold = density.run { SCROLL_THRESHOLD.toPx() }
  val flingThreshold = density.run { FLING_THRESHOLD.toPx() }

  return remember(
      scrollThreshold,
      flingThreshold,
  ) {
    object : NestedScrollConnection {
      private var accumulated = 0F

      override suspend fun onPreFling(available: Velocity): Velocity {
        if (available.y < -flingThreshold) {
          handleVisibilityChanged(false)
          accumulated = 0F
        }

        return Velocity.Zero
      }

      override fun onPostScroll(
          consumed: Offset,
          available: Offset,
          source: NestedScrollSource,
      ): Offset {
        val delta = consumed.y
        if (delta > 0F) {
          accumulated += delta
          if (accumulated > scrollThreshold) {
            handleVisibilityChanged(true)
            accumulated = 0F
          }
        } else if (delta < 0F) {
          accumulated = 0F
        }

        // Always return Zero so that we do NOT consume any of the scroll,
        // we just watch it
        return Offset.Zero
      }
    }
  }
}

@TestOnly
@Composable
private fun PreviewMainScreen(
    isShowingQr: Boolean,
    isShowingSlowSpeedHelp: Boolean,
    http: Boolean,
    socks: Boolean,
) {
  val state =
      object : MainViewState {
        override val isShowingQRCodeDialog = MutableStateFlow(isShowingQr)
        override val isShowingSlowSpeedHelp = MutableStateFlow(isShowingSlowSpeedHelp)
        override val group = MutableStateFlow(BroadcastNetworkStatus.GroupInfo.Empty)
        override val connection = MutableStateFlow(BroadcastNetworkStatus.ConnectionInfo.Empty)

        override val isHttpEnabled = MutableStateFlow(http)
        override val isSocksEnabled = MutableStateFlow(socks)
        override val port = MutableStateFlow(0)

        // TODO support RNDIS
        override val broadcastType = MutableStateFlow(BroadcastType.WIFI_DIRECT)

        // TODO support other network prefs
        override val preferredNetwork = MutableStateFlow<PreferredNetwork?>(PreferredNetwork.NONE)

        override val wiDiStatus = MutableStateFlow<RunningStatus>(RunningStatus.NotRunning)
        override val proxyStatus = MutableStateFlow<RunningStatus>(RunningStatus.NotRunning)
        override val startBlockers = MutableStateFlow<Collection<HotspotStartBlocker>>(emptySet())

        override val isShowingSetupError = MutableStateFlow(false)
        override val isShowingNetworkError = MutableStateFlow(false)
        override val isShowingHotspotError = MutableStateFlow(false)
        override val isShowingBroadcastError = MutableStateFlow(false)
        override val isShowingProxyError = MutableStateFlow(false)
      }
  val allTabs = MainView.entries.rememberAsStateList()

  MainScreen(
      appName = "TEST",
      state = state,
      pagerState = rememberPagerState { allTabs.size },
      allTabs = allTabs,
      onTabChanged = {},
      onShowQRCode = {},
      onRefreshConnection = {},
      onJumpToHowTo = {},
      onLaunchIntent = {},
      onUpdateTile = {},
      onShowSlowSpeedHelp = {},
      onToggleProxy = {},
      onOpenBroadcastError = {},
      onOpenProxyError = {},
      onOpenNetworkError = {},
      onOpenHotspotError = {},
      onHttpEnabledChanged = {},
      onSocksEnabledChanged = {},
      onPortChanged = {},
  )
}

@Preview
@Composable
private fun PreviewMainScreenDefaultHttp() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = false,
  )
}

@Preview
@Composable
private fun PreviewMainScreenSettingsHttp() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = false,
  )
}

@Preview
@Composable
private fun PreviewMainScreenQrHttp() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = false,
  )
}

@Preview
@Composable
private fun PreviewMainScreenHelpHttp() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = true,
      socks = false,
  )
}

@Preview
@Composable
private fun PreviewMainScreenAllHttp() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = true,
      socks = false,
  )
}

@Preview
@Composable
private fun PreviewMainScreenDefaultSocks() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = false,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenSettingsSocks() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = false,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenQrSocks() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = false,
      http = false,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenHelpSocks() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = false,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenAllSocks() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = false,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenDefaultBoth() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenSettingsBoth() {
  PreviewMainScreen(
      isShowingQr = false,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenQrBoth() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = false,
      http = true,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenHelpBoth() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = true,
      socks = true,
  )
}

@Preview
@Composable
private fun PreviewMainScreenAllBoth() {
  PreviewMainScreen(
      isShowingQr = true,
      isShowingSlowSpeedHelp = true,
      http = true,
      socks = true,
  )
}
