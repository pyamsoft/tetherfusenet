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

package com.pyamsoft.tetherfi.status.sections.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.theme.HairlineSize
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.status.MutableStatusViewState
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.test.TEST_PASSWORD
import com.pyamsoft.tetherfi.ui.test.TEST_SSID
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import com.pyamsoft.tetherfi.ui.trouble.TroubleshootUnableToStart
import org.jetbrains.annotations.TestOnly

private enum class NetworkStatusWidgetsContentTypes {
  NETWORK_ERROR,
}

@LintIgnoreLongMethod
internal fun LazyListScope.renderNetworkInformation(
    itemModifier: Modifier = Modifier,
    appName: String,

    // State
    state: StatusViewState,
    serverViewState: ServerViewState,

    // Running
    isEditable: Boolean,
    wiDiStatus: RunningStatus,
    proxyStatus: RunningStatus,

    // Network config
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,

    onHttpEnabledChanged: (Boolean) -> Unit,
    onSocksEnabledChanged: (Boolean) -> Unit,
    onPortChanged: (Int) -> Unit,
    onEnableChangeFailed: (ServerPortTypes) -> Unit,

    // Connections
    onShowQRCode: () -> Unit,
    onRefreshConnection: () -> Unit,

    // Errors
    onShowNetworkError: () -> Unit,
    onShowHotspotError: () -> Unit,

    // Jump links
    onJumpToHowTo: () -> Unit,
    onViewSlowSpeedHelp: () -> Unit,
) {
  item(
      contentType = NetworkStatusWidgetsContentTypes.NETWORK_ERROR,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()
    val isBroadcastError = remember(wiDiStatus) { wiDiStatus is RunningStatus.Error }
    val isProxyError = remember(proxyStatus) { proxyStatus is RunningStatus.Error }

    val errorThrowable =
        remember(wiDiStatus, proxyStatus) {
          if (wiDiStatus is RunningStatus.Error) {
            return@remember wiDiStatus.throwable
          }

          if (proxyStatus is RunningStatus.Error) {
            return@remember proxyStatus.throwable
          }

          return@remember null
        }

    val showErrorHintMessage =
        remember(
            isBroadcastError,
            isProxyError,
        ) {
          isBroadcastError || isProxyError
        }

    AnimatedVisibility(
        visible = showErrorHintMessage,
    ) {
      Box(
          modifier =
              itemModifier
                  .padding(bottom = MaterialTheme.keylines.content)
                  .border(
                      width = HairlineSize,
                      color = MaterialTheme.colorScheme.error,
                      shape = MaterialTheme.shapes.large,
                  )
                  .padding(vertical = MaterialTheme.keylines.content),
      ) {
        TroubleshootUnableToStart(
            modifier = Modifier.fillMaxWidth(),
            appName = appName,
            broadcastType = broadcastType,
            isBroadcastError = isBroadcastError,
            isProxyError = isProxyError,
            throwable = errorThrowable,
        )
      }
    }
  }

  if (isEditable) {
    renderEditableItems(
        modifier = itemModifier,
        appName = appName,
        state = state,
        serverViewState = serverViewState,
        onSsidChanged = onSsidChanged,
        onPasswordChanged = onPasswordChanged,
        onHttpEnabledChanged = onHttpEnabledChanged,
        onSocksEnabledChanged = onSocksEnabledChanged,
      onPortChanged = onPortChanged,
        onTogglePasswordVisibility = onTogglePasswordVisibility,
        onEnableChangeFailed = onEnableChangeFailed,
    )
  } else {
    renderRunningItems(
        modifier = itemModifier,
        state = state,
        serverViewState = serverViewState,
        onTogglePasswordVisibility = onTogglePasswordVisibility,
        onShowQRCode = onShowQRCode,
        onRefreshConnection = onRefreshConnection,
        onShowHotspotError = onShowHotspotError,
        onShowNetworkError = onShowNetworkError,
        onJumpToHowTo = onJumpToHowTo,
        onViewSlowSpeedHelp = onViewSlowSpeedHelp,
    )
  }
}

@TestOnly
@Composable
private fun PreviewNetworkInformation(
    ssid: String = TEST_SSID,
    password: String = TEST_PASSWORD,
    server: TestServerState,
    isEditable: Boolean,
    wiDiStatus: RunningStatus,
    proxyStatus: RunningStatus,
    http: Boolean,
    socks: Boolean,
) {
  LazyColumn {
    renderNetworkInformation(
        itemModifier = Modifier.width(LANDSCAPE_MAX_WIDTH),
        wiDiStatus = wiDiStatus,
        proxyStatus = proxyStatus,
        appName = "TEST",
        isEditable = isEditable,
        serverViewState = makeTestServerState(server, http, socks),
        state =
            MutableStatusViewState().apply {
              this.ssid.value = ssid
              this.password.value = password
            },
        onShowNetworkError = {},
        onShowQRCode = {},
        onRefreshConnection = {},
        onJumpToHowTo = {},
        onHttpEnabledChanged = {},
        onSocksEnabledChanged = {},
      onPortChanged = {},
        onSsidChanged = {},
        onPasswordChanged = {},
        onShowHotspotError = {},
        onTogglePasswordVisibility = {},
        onViewSlowSpeedHelp = {},
        onEnableChangeFailed = {},
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationBlankHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlySsidHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      password = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPasswordHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPortHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyNotEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedNotEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorNotEditableHttp() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyRunningHttp() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedRunningHttp() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorRunningHttp() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationBlankSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlySsidSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      password = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPasswordSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPortSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyNotEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedNotEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorNotEditableSocks() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyRunningSocks() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedRunningSocks() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorRunningSocks() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationBlankBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlySsidBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      password = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPasswordBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationOnlyPortBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      ssid = "",
      password = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyNotEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedNotEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = true,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorNotEditableBoth() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationEmptyRunningBoth() {
  PreviewNetworkInformation(
      server = TestServerState.EMPTY,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationConnectedRunningBoth() {
  PreviewNetworkInformation(
      server = TestServerState.CONNECTED,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewNetworkInformationErrorRunningBoth() {
  PreviewNetworkInformation(
      server = TestServerState.ERROR,
      isEditable = false,
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.Running,
      http = true,
      socks = true,
  )
}
