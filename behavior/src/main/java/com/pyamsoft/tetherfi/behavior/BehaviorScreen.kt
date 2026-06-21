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

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.util.fillUpToPortraitSize
import com.pyamsoft.tetherfi.server.ServerPerformanceLimit
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.LoadingSpinner
import com.pyamsoft.tetherfi.ui.STATIC_HOTSPOT_ERROR
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.renderLinks
import com.pyamsoft.tetherfi.ui.renderPYDroidExtras
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import org.jetbrains.annotations.TestOnly

private enum class BehaviorScreenContentTypes {
  LOADING,
}

@Composable
fun BehaviorScreen(
    modifier: Modifier = Modifier,
    appName: String,
    lazyListState: LazyListState,
    state: BehaviorViewState,
    serverViewState: ServerViewState,

    // Battery Optimization
    onOpenBatterySettings: () -> Unit,

    // Notification
    onRequestNotificationPermission: () -> Unit,

    // Tweaks
    onToggleIgnoreVpn: () -> Unit,
    onToggleIgnoreLocation: () -> Unit,
    onToggleShutdownWithNoClients: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleWakeLock: () -> Unit,

    // Expert
    onShowSocketTimeout: () -> Unit,
    onHideSocketTimeout: () -> Unit,
    onUpdateSocketTimeout: (ServerSocketTimeout) -> Unit,
) {
  val wiDiStatus by serverViewState.wiDiStatus.collectAsStateWithLifecycle()
  val proxyStatus by serverViewState.proxyStatus.collectAsStateWithLifecycle()

  val hotspotStatus =
      remember(
          wiDiStatus,
          proxyStatus,
      ) {
        if (wiDiStatus is RunningStatus.Error || proxyStatus is RunningStatus.Error) {
          return@remember STATIC_HOTSPOT_ERROR
        }

        // If either is starting, mark us starting
        if (wiDiStatus is RunningStatus.Starting || proxyStatus is RunningStatus.Starting) {
          return@remember RunningStatus.Starting
        }

        // If the wifi direct broadcast is up, but the proxy is not up yet, mark starting
        if (wiDiStatus is RunningStatus.Running && proxyStatus !is RunningStatus.Running) {
          return@remember RunningStatus.Starting
        }

        // If either is stopping, mark us stopping
        if (wiDiStatus is RunningStatus.Stopping || proxyStatus is RunningStatus.Stopping) {
          return@remember RunningStatus.Stopping
        }

        if (wiDiStatus is RunningStatus.Running) {
          // If the Wifi Direct is running, watch the proxy behavior
          return@remember proxyStatus
        } else {
          // Otherwise fallback to wiDi behavior
          return@remember wiDiStatus
        }
      }

  val isEditable =
      remember(hotspotStatus) {
        when (hotspotStatus) {
          is RunningStatus.Running,
          is RunningStatus.Starting,
          is RunningStatus.Stopping -> false

          else -> true
        }
      }

  val showNotificationSettings = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
  val loadingState by state.loadingState.collectAsStateWithLifecycle()

  LazyColumn(
      modifier = modifier,
      state = lazyListState,
      contentPadding = PaddingValues(horizontal = MaterialTheme.keylines.content),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    renderPYDroidExtras(
        modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
    )

    renderLinks(
        modifier = Modifier.width(LANDSCAPE_MAX_WIDTH),
        appName = appName,
    )

    when (loadingState) {
      BehaviorViewState.LoadingState.NONE,
      BehaviorViewState.LoadingState.LOADING -> {
        item(
            contentType = BehaviorScreenContentTypes.LOADING,
        ) {
          LoadingSpinner(
              modifier =
                  Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH)
                      .padding(MaterialTheme.keylines.content),
          )
        }
      }

      BehaviorViewState.LoadingState.DONE -> {
        renderLoadedContent(
            // Not widthIn because a TextField does not take up "all" by default
            itemModifier = Modifier.width(LANDSCAPE_MAX_WIDTH),
            appName = appName,
            state = state,
            isEditable = isEditable,
            showNotificationSettings = showNotificationSettings,
            onOpenBatterySettings = onOpenBatterySettings,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onToggleIgnoreVpn = onToggleIgnoreVpn,
            onToggleIgnoreLocation = onToggleIgnoreLocation,
            onToggleShutdownWithNoClients = onToggleShutdownWithNoClients,
            onToggleKeepScreenOn = onToggleKeepScreenOn,
            onShowSocketTimeout = onShowSocketTimeout,
            onToggleWakeLock = onToggleWakeLock,
        )
      }
    }
  }

  BehaviorDialogs(
      dialogModifier = Modifier.fillUpToPortraitSize().widthIn(max = LANDSCAPE_MAX_WIDTH),
      state = state,
      onHideSocketTimeout = onHideSocketTimeout,
      onUpdateSocketTimeout = onUpdateSocketTimeout,
  )
}

@TestOnly
@Composable
private fun PreviewBehaviorScreen(
    isLoading: Boolean,
) {
  BehaviorScreen(
      state =
          MutableBehaviorViewState().apply {
            loadingState.value =
                if (isLoading) BehaviorViewState.LoadingState.LOADING
                else BehaviorViewState.LoadingState.DONE
          },
      lazyListState = rememberLazyListState(),
      serverViewState =
          makeTestServerState(
              TestServerState.EMPTY,
              isHttpEnabled = false,
              isSocksEnabled = false,
          ),
      appName = "TEST",
      onRequestNotificationPermission = {},
      onOpenBatterySettings = {},
      onToggleIgnoreVpn = {},
      onToggleIgnoreLocation = {},
      onToggleShutdownWithNoClients = {},
      onToggleKeepScreenOn = {},
      onUpdateSocketTimeout = {},
      onHideSocketTimeout = {},
      onShowSocketTimeout = {},
      onToggleWakeLock = {},
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewBehaviorScreenLoading() {
  PreviewBehaviorScreen(
      isLoading = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewBehaviorScreenEditing() {
  PreviewBehaviorScreen(
      isLoading = false,
  )
}
