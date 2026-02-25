/*
 * Copyright 2025 pyamsoft
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

package com.pyamsoft.tetherfi.status.sections.network

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.status.MutableStatusViewState
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.ServerPortTypes
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.dialog.CardDialog
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.test.TEST_PASSWORD
import com.pyamsoft.tetherfi.ui.test.TEST_PORT
import com.pyamsoft.tetherfi.ui.test.TEST_SSID
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import org.jetbrains.annotations.TestOnly

private enum class RenderEditableItemsContentTypes {
  EXPLAIN_EDIT_WIFI_DIRECT,
  EDIT_SSID,
  EDIT_PASSWD,
  EDIT_PORTS,
}

internal fun LazyListScope.renderEditableItems(
    modifier: Modifier = Modifier,
    appName: String,
    state: StatusViewState,
    serverViewState: ServerViewState,
    onSsidChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onHttpEnabledChanged: (Boolean) -> Unit,
    onHttpPortChanged: (String) -> Unit,
    onSocksEnabledChanged: (Boolean) -> Unit,
    onSocksPortChanged: (String) -> Unit,
    onEnableChangeFailed: (ServerPortTypes) -> Unit,
) {
  item(
      contentType = RenderEditableItemsContentTypes.EXPLAIN_EDIT_WIFI_DIRECT,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()

    if (broadcastType == BroadcastType.WIFI_DIRECT) {
      NetworkSetupExplainer(
          modifier = modifier,
          appName = appName,
      )
    }
  }

  item(
      contentType = RenderEditableItemsContentTypes.EDIT_SSID,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()

    if (broadcastType == BroadcastType.WIFI_DIRECT) {
      EditSsid(
          modifier = modifier.padding(bottom = MaterialTheme.keylines.baseline),
          state = state,
          onSsidChanged = onSsidChanged,
      )
    }
  }

  item(
      contentType = RenderEditableItemsContentTypes.EDIT_PASSWD,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()

    if (broadcastType == BroadcastType.WIFI_DIRECT) {
      EditPassword(
          modifier = modifier.padding(bottom = MaterialTheme.keylines.baseline),
          state = state,
          onTogglePasswordVisibility = onTogglePasswordVisibility,
          onPasswordChanged = onPasswordChanged,
      )
    }
  }

  item(
      contentType = RenderEditableItemsContentTypes.EDIT_PORTS,
  ) {
    val isHttpEnabled by serverViewState.isHttpEnabled.collectAsStateWithLifecycle()
    val isSocksEnabled by serverViewState.isSocksEnabled.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.padding(top = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      Column {
        Text(
            modifier = Modifier.padding(MaterialTheme.keylines.content),
            text = stringResource(R.string.editmode_hotspot_proxy_mode_title),
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.W700,
                    color = MaterialTheme.colorScheme.primary,
                ),
        )

        EditProxyPort(
            modifier = modifier.padding(bottom = MaterialTheme.keylines.content),
            portType = ServerPortTypes.HTTP,
            titleRes = R.string.editmode_hotspot_proxy_http_title,
            descriptionRes = R.string.editmode_hotspot_proxy_http_description,
            isEnabled = isHttpEnabled,
            isOtherEnabled = isSocksEnabled,
            onEnabledChanged = onHttpEnabledChanged,
            onEnableChangeFailed = onEnableChangeFailed,
        ) {
          EditHttpPort(
              modifier = Modifier.weight(1F).padding(end = MaterialTheme.keylines.content),
              state = state,
              onPortChanged = onHttpPortChanged,
          )
        }

        EditProxyPort(
            modifier = modifier.padding(bottom = MaterialTheme.keylines.content),
            portType = ServerPortTypes.SOCKS,
            titleRes = R.string.editmode_hotspot_proxy_socks_title,
            descriptionRes = R.string.editmode_hotspot_proxy_socks_description,
            isEnabled = isSocksEnabled,
            isOtherEnabled = isHttpEnabled,
            onEnabledChanged = onSocksEnabledChanged,
            onEnableChangeFailed = onEnableChangeFailed,
        ) {
          EditSocksPort(
              modifier = Modifier.weight(1F).padding(end = MaterialTheme.keylines.content),
              state = state,
              onPortChanged = onSocksPortChanged,
          )
        }
      }
    }
  }
}

@Composable
private fun NetworkSetupExplainer(
    modifier: Modifier = Modifier,
    appName: String,
) {
  val requiredPrefix = remember { ServerDefaults.getWifiSsidPrefix() }

  val (show, setShow) = remember { mutableStateOf(false) }

  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
          text = stringResource(R.string.hotspot_configuration_title),
          style =
              MaterialTheme.typography.bodyLarge.copy(
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.W700,
              ),
      )
      Text(
          modifier = Modifier.padding(bottom = MaterialTheme.keylines.typography),
          text = stringResource(R.string.hotspot_configuration_google_please, appName),
          style = MaterialTheme.typography.bodySmall,
      )
    }

    IconButton(
        modifier = Modifier.padding(start = MaterialTheme.keylines.baseline),
        onClick = { setShow(true) },
    ) {
      Icon(
          painter = IconPainters.info(),
          contentDescription = stringResource(R.string.hotspot_configuration_title),
      )
    }
  }

  if (show) {
    val handleHide = { setShow(false) }
    CardDialog(
        onDismiss = handleHide,
    ) {
      Column(
          modifier = Modifier.padding(MaterialTheme.keylines.content),
      ) {
        Text(
            modifier = Modifier.padding(bottom = MaterialTheme.keylines.baseline),
            text = stringResource(R.string.hotspot_config_defaults, appName),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            modifier = Modifier.padding(bottom = MaterialTheme.keylines.content),
            text = stringResource(R.string.hotspot_config_no_account, appName),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.hotspot_config_name_requirement, requiredPrefix),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.hotspot_config_password_requirement),
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
              modifier = Modifier.weight(1F),
          )
          TextButton(
              onClick = handleHide,
          ) {
            Text(
                text = stringResource(android.R.string.cancel),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EditProxyPort(
    modifier: Modifier = Modifier,
    portType: ServerPortTypes,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    isEnabled: Boolean,
    isOtherEnabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onEnableChangeFailed: (ServerPortTypes) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
  Column(
      modifier = modifier.padding(bottom = MaterialTheme.keylines.baseline),
  ) {
    Text(
        modifier = Modifier.padding(horizontal = MaterialTheme.keylines.content),
        text = stringResource(titleRes),
        style =
            MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.primary,
            ),
    )
    Text(
        modifier =
            Modifier.padding(horizontal = MaterialTheme.keylines.content)
                .padding(
                    top = MaterialTheme.keylines.content,
                    bottom = MaterialTheme.keylines.baseline,
                ),
        text = stringResource(descriptionRes),
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Switch(
          modifier =
              Modifier.padding(
                  horizontal = MaterialTheme.keylines.content,
              ),
          checked = isEnabled,
          onCheckedChange = {
            if (isOtherEnabled) {
              onEnabledChanged(it)
            } else {
              onEnableChangeFailed(portType)
            }
          },
      )

      content()
    }
  }
}

@TestOnly
@Composable
private fun PreviewEditableItems(
    ssid: String = TEST_SSID,
    password: String = TEST_PASSWORD,
    port: String = "$TEST_PORT",
    http: Boolean,
    socks: Boolean,
) {
  LazyColumn {
    renderEditableItems(
        modifier = Modifier.width(LANDSCAPE_MAX_WIDTH),
        appName = "TEST",
        state =
            MutableStatusViewState().apply {
              this.ssid.value = ssid
              this.password.value = password
              this.httpPort.value = port
            },
        onHttpEnabledChanged = {},
        onHttpPortChanged = {},
        onSocksEnabledChanged = {},
        onSocksPortChanged = {},
        onSsidChanged = {},
        onPasswordChanged = {},
        onTogglePasswordVisibility = {},
        onEnableChangeFailed = {},
        serverViewState = makeTestServerState(TestServerState.EMPTY, http, socks),
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsBlankHttp() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      port = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlySsidHttp() {
  PreviewEditableItems(
      password = "",
      port = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPasswordHttp() {
  PreviewEditableItems(
      ssid = "",
      port = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPortHttp() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsBlankSocks() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      port = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlySsidSocks() {
  PreviewEditableItems(
      password = "",
      port = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPasswordSocks() {
  PreviewEditableItems(
      ssid = "",
      port = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPortSocks() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsBlankBoth() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      port = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlySsidBoth() {
  PreviewEditableItems(
      password = "",
      port = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPasswordBoth() {
  PreviewEditableItems(
      ssid = "",
      port = "",
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewEditableItemsOnlyPortBoth() {
  PreviewEditableItems(
      ssid = "",
      password = "",
      http = true,
      socks = true,
  )
}
