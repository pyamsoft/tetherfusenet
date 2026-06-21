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

package com.pyamsoft.tetherfi.status

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.status.sections.broadcast.BroadcastTypeSelection
import com.pyamsoft.tetherfi.status.sections.broadcast.PreferredNetworkSelection
import com.pyamsoft.tetherfi.status.sections.broadcast.renderBroadcastFrequency
import com.pyamsoft.tetherfi.status.sections.network.renderNetworkInformation
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.renderLinks
import com.pyamsoft.tetherfi.ui.test.TEST_PASSWORD
import com.pyamsoft.tetherfi.ui.test.TEST_SSID
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import org.jetbrains.annotations.TestOnly

private enum class StatusLoadedContentTypes {
  SPACER,
  BROADCAST_TYPE,
  PREFERRED_NETWORK,
}

@LintIgnoreLongMethod
internal fun LazyListScope.renderLoadedContent(
    itemModifier: Modifier = Modifier,
    appName: String,
    state: StatusViewState,
    serverViewState: ServerViewState,
    isEditable: Boolean,

    // Network
    wiDiStatus: RunningStatus,
    proxyStatus: RunningStatus,
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectBand: (ServerNetworkBand) -> Unit,

    onHttpEnabledChanged: (Boolean) -> Unit,
    onSocksEnabledChanged: (Boolean) -> Unit,
    onPortChanged: (Int) -> Unit,
    onEnableChangeFailed: (ServerPortTypes) -> Unit,

    // Status button
    onShowQRCode: () -> Unit,
    onRefreshConnection: () -> Unit,

    // Proxy Behavior
    onSelectBroadcastType: (BroadcastType) -> Unit,
    onSelectPreferredNetwork: (PreferredNetwork) -> Unit,

    // Errors
    onShowNetworkError: () -> Unit,
    onShowHotspotError: () -> Unit,
    // Jump links
    onJumpToHowTo: () -> Unit,
    onViewSlowSpeedHelp: () -> Unit,
) {
  renderNetworkInformation(
      itemModifier = itemModifier,
      isEditable = isEditable,
      appName = appName,
      state = state,
      serverViewState = serverViewState,
      wiDiStatus = wiDiStatus,
      proxyStatus = proxyStatus,
      onSsidChanged = onSsidChanged,
      onPasswordChanged = onPasswordChanged,
      onHttpEnabledChanged = onHttpEnabledChanged,
      onSocksEnabledChanged = onSocksEnabledChanged,
    onPortChanged = onPortChanged,
    onEnableChangeFailed = onEnableChangeFailed,
      onTogglePasswordVisibility = onTogglePasswordVisibility,
      onShowQRCode = onShowQRCode,
      onRefreshConnection = onRefreshConnection,
      onShowNetworkError = onShowNetworkError,
      onShowHotspotError = onShowHotspotError,
      onJumpToHowTo = onJumpToHowTo,
      onViewSlowSpeedHelp = onViewSlowSpeedHelp,
  )

  renderBroadcastFrequency(
      itemModifier = itemModifier,
      isEditable = isEditable,
      state = state,
      serverViewState = serverViewState,
      onSelectBand = onSelectBand,
  )

  item(
      contentType = StatusLoadedContentTypes.SPACER,
  ) {
    Spacer(
        modifier = itemModifier.height(MaterialTheme.keylines.content),
    )
  }

  item(
      contentType = StatusLoadedContentTypes.PREFERRED_NETWORK,
  ) {
    Card(
        modifier = itemModifier.padding(bottom = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      PreferredNetworkSelection(
          modifier = Modifier.padding(vertical = MaterialTheme.keylines.content),
          serverViewState = serverViewState,
          appName = appName,
          isEditable = isEditable,
          onSelectPreferredNetwork = onSelectPreferredNetwork,
      )
    }
  }

  item(
      contentType = StatusLoadedContentTypes.BROADCAST_TYPE,
  ) {
    Card(
        modifier = itemModifier.padding(bottom = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      BroadcastTypeSelection(
          modifier = Modifier.padding(vertical = MaterialTheme.keylines.content),
          serverViewState = serverViewState,
          appName = appName,
          isEditable = isEditable,
          onSelectBroadcastType = onSelectBroadcastType,
      )
    }
  }

  renderLinks(
      modifier = itemModifier,
      appName = appName,
  )

  item(
      contentType = StatusLoadedContentTypes.SPACER,
  ) {
    Spacer(
        modifier =
            itemModifier.padding(top = MaterialTheme.keylines.content).navigationBarsPadding(),
    )
  }
}

@TestOnly
@Composable
private fun PreviewLoadedContent(
    state: TestServerState,
    isEditable: Boolean,
    http: Boolean,
    socks: Boolean,
) {
  LazyColumn {
    renderLoadedContent(
        itemModifier = Modifier.widthIn(LANDSCAPE_MAX_WIDTH),
        state =
            MutableStatusViewState().apply {
              loadingState.value = StatusViewState.LoadingState.DONE
              this.ssid.value = TEST_SSID
              this.password.value = TEST_PASSWORD
              band.value = ServerNetworkBand.LEGACY
            },
        serverViewState = makeTestServerState(state, http, socks),
        appName = "TEST",
        onSelectBand = {},
        onPasswordChanged = {},
        onHttpEnabledChanged = {},
        onSocksEnabledChanged = {},
      onPortChanged = {},
        onSsidChanged = {},
        onTogglePasswordVisibility = {},
        onShowQRCode = {},
        onRefreshConnection = {},
        onShowHotspotError = {},
        onShowNetworkError = {},
        onJumpToHowTo = {},
        isEditable = isEditable,
        wiDiStatus = RunningStatus.NotRunning,
        proxyStatus = RunningStatus.NotRunning,
        onViewSlowSpeedHelp = {},
        onSelectBroadcastType = {},
        onSelectPreferredNetwork = {},
        onEnableChangeFailed = {},
    )
  }
}

@TestOnly
@Composable
private fun PreviewEmpty(
    isEditable: Boolean,
    http: Boolean,
    socks: Boolean,
) {
  PreviewLoadedContent(
      state = TestServerState.EMPTY,
      isEditable = isEditable,
      http = http,
      socks = socks,
  )
}

@TestOnly
@Composable
private fun PreviewConnected(
    isEditable: Boolean,
    http: Boolean,
    socks: Boolean,
) {
  PreviewLoadedContent(
      state = TestServerState.CONNECTED,
      isEditable = isEditable,
      http = http,
      socks = socks,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyEditableHttp() {
  PreviewEmpty(
      isEditable = true,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyNoEditableHttp() {
  PreviewEmpty(
      isEditable = false,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedEditableHttp() {
  PreviewConnected(
      isEditable = true,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedNoEditableHttp() {
  PreviewConnected(
      isEditable = false,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyEditableSocks() {
  PreviewEmpty(
      isEditable = true,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyNoEditableSocks() {
  PreviewEmpty(
      isEditable = false,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedEditableSocks() {
  PreviewConnected(
      isEditable = true,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedNoEditableSocks() {
  PreviewConnected(
      isEditable = false,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyEditableBoth() {
  PreviewEmpty(
      isEditable = true,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEmptyNoEditableBoth() {
  PreviewEmpty(
      isEditable = false,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedEditableBoth() {
  PreviewConnected(
      isEditable = true,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectedNoEditableBoth() {
  PreviewConnected(
      isEditable = false,
      http = true,
      socks = true,
  )
}
