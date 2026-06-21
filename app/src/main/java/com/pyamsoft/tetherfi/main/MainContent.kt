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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pyamsoft.tetherfi.behavior.BehaviorEntry
import com.pyamsoft.tetherfi.connections.ConnectionEntry
import com.pyamsoft.tetherfi.info.InfoEntry
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.settings.SettingsEntry
import com.pyamsoft.tetherfi.status.StatusEntry
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import com.pyamsoft.tetherfi.ui.ServerViewState

@Composable
fun MainContent(
  modifier: Modifier = Modifier,
  appName: String,
  pagerState: PagerState,
  state: ServerViewState,
  allTabs: List<MainView>,

    // Main
  onHttpEnabledChanged: (Boolean) -> Unit,
  onPortChanged: (Int) -> Unit,
  onSocksEnabledChanged: (Boolean) -> Unit,

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

    // Error
  onEnableChangeFailed: (ServerPortTypes) -> Unit,
) {
  val statusLazyListState = rememberLazyListState()
  val behaviorLazyListState = rememberLazyListState()
  val infoLazyListState = rememberLazyListState()
  val connectionLazyListState = rememberLazyListState()
  val settingsLazyListState = rememberLazyListState()

  HorizontalPager(
      modifier = modifier,
      state = pagerState,
  ) { page ->
    val screen =
        remember(
            allTabs,
            page,
        ) {
          allTabs[page]
        }

    when (screen) {
      MainView.INFO -> {
        InfoEntry(
            modifier = Modifier.fillMaxSize(),
            appName = appName,
            lazyListState = infoLazyListState,
            serverViewState = state,
            onShowQRCode = onShowQRCode,
            onShowSlowSpeedHelp = onShowSlowSpeedHelp,
        )
      }
      MainView.BEHAVIOR -> {
        BehaviorEntry(
            modifier = Modifier.fillMaxSize(),
            appName = appName,
            lazyListState = behaviorLazyListState,
            serverViewState = state,
            onRefreshConnection = onRefreshConnection,
            onLaunchIntent = onLaunchIntent,
        )
      }
      MainView.STATUS -> {
        StatusEntry(
            modifier = Modifier.fillMaxSize(),
            appName = appName,
            lazyListState = statusLazyListState,
            serverViewState = state,
            onShowQRCode = onShowQRCode,
            onRefreshConnection = onRefreshConnection,
            onJumpToHowTo = onJumpToHowTo,
            onUpdateTile = onUpdateTile,
            onShowSlowSpeedHelp = onShowSlowSpeedHelp,
            onToggleProxy = onToggleProxy,
            onOpenNetworkError = onOpenNetworkError,
            onOpenHotspotError = onOpenHotspotError,
            onOpenProxyError = onOpenProxyError,
            onOpenBroadcastError = onOpenBroadcastError,
            onEnableChangeFailed = onEnableChangeFailed,
            onHttpEnabledChanged = onHttpEnabledChanged,
            onSocksEnabledChanged = onSocksEnabledChanged,
          onPortChanged = onPortChanged,
        )
      }
      MainView.CONNECTIONS -> {
        ConnectionEntry(
            modifier = Modifier.fillMaxSize(),
            appName = appName,
            lazyListState = connectionLazyListState,
            serverViewState = state,
        )
      }
      MainView.SETTINGS -> {
        SettingsEntry(
            modifier = Modifier.fillMaxSize(),
            lazyListState = settingsLazyListState,
        )
      }
    }
  }
}
