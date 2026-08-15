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

package com.pyamsoft.tetherfi.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults
import com.pyamsoft.tetherfi.core.AppDevEnvironment

private enum class SettingsContentTypes {
  DEBUG_YOLO_ERROR,
  DEBUG_BROADCAST_ERROR,
  DEBUG_PROXY_ERROR,
  DEBUG_GROUP_EMPTY,
  DEBUG_GROUP_GOOD,
  DEBUG_GROUP_ERROR,
  DEBUG_CONN_EMPTY,
  DEBUG_CONN_GOOD,
  DEBUG_CONN_ERROR,
  DEBUG_SOCKET_OOM,
  DEBUG_SOCKET_ERROR,
  BOTTOM_SPACER,
  EXPERIMENT_EXPLAIN,
}

@Composable
private fun DebugItem(
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
        modifier = Modifier.weight(1F),
    ) {
      Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
      )
      Text(
          text = description,
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
    Checkbox(
        enabled = isEnabled,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
  }
}

internal fun LazyListScope.renderExperiments(
    itemModifier: Modifier = Modifier,
) {
  item(
      contentType = SettingsContentTypes.EXPERIMENT_EXPLAIN,
  ) {
    Text(
        modifier = itemModifier,
        text = stringResource(R.string.experimental_flags_title),
        style =
            MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
    )
    Text(
        modifier = itemModifier.padding(bottom = MaterialTheme.keylines.baseline),
        text = stringResource(R.string.experimental_flags_description),
        style =
            MaterialTheme.typography.bodySmall.copy(
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = TypographyDefaults.ALPHA_DISABLED,
                    ),
            ),
    )
  }

  item(
      contentType = SettingsContentTypes.BOTTOM_SPACER,
  ) {
    Spacer(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
    )
  }
}

@LintIgnoreLongMethod
internal fun LazyListScope.renderExtraDebugContent(
    itemModifier: Modifier = Modifier,
    appEnvironment: AppDevEnvironment,
) {
  item(
      contentType = SettingsContentTypes.DEBUG_YOLO_ERROR,
  ) {
    val isYoloError by
        appEnvironment.isYoloError.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_YOLO_FAKE_ERROR_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.yolo_title),
        description = stringResource(R.string.yolo_explain),
        checked = isYoloError,
        onCheckedChange = { appEnvironment.updateYolo(it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_BROADCAST_ERROR,
  ) {
    val isBroadcastFakeError by
        appEnvironment.isBroadcastFakeError.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_BROADCAST_FAKE_ERROR_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.broadcast_title),
        description = stringResource(R.string.broadcast_explain),
        checked = isBroadcastFakeError,
        onCheckedChange = { appEnvironment.updateBroadcast(it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_PROXY_ERROR,
  ) {
    val isProxyFakeError by
        appEnvironment.isProxyFakeError.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_PROXY_FAKE_ERROR_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.proxy_title),
        description = stringResource(R.string.proxy_explain),
        checked = isProxyFakeError,
        onCheckedChange = { appEnvironment.updateProxy(it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_GROUP_EMPTY,
  ) {
    val isGroupFakeEmpty by
        appEnvironment.fakeGroup.isEmpty.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_GROUP_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.empty_group_title),
        description = stringResource(R.string.empty_group_explain),
        checked = isGroupFakeEmpty,
        onCheckedChange = { appEnvironment.updateGroup(isEmpty = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_GROUP_GOOD,
  ) {
    val isGroupFakeConnected by
        appEnvironment.fakeGroup.isConnected.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_GROUP_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.connected_group_title),
        description = stringResource(R.string.connected_group_explain),
        checked = isGroupFakeConnected,
        onCheckedChange = { appEnvironment.updateGroup(isConnected = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_GROUP_ERROR,
  ) {
    val isGroupFakeError by
        appEnvironment.fakeGroup.isError.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_GROUP_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.error_group_title),
        description = stringResource(R.string.error_group_explain),
        checked = isGroupFakeError,
        onCheckedChange = { appEnvironment.updateGroup(isError = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_CONN_EMPTY,
  ) {
    val isConnectionFakeEmpty by
        appEnvironment.fakeConnection.isEmpty.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_CONNECTION_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.empty_connection_title),
        description = stringResource(R.string.empty_connection_explain),
        checked = isConnectionFakeEmpty,
        onCheckedChange = { appEnvironment.updateConnection(isEmpty = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_CONN_GOOD,
  ) {
    val isConnectionFakeConnected by
        appEnvironment.fakeConnection.isConnected.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_CONNECTION_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.connected_connection_title),
        description = stringResource(R.string.connected_connection_explain),
        checked = isConnectionFakeConnected,
        onCheckedChange = { appEnvironment.updateConnection(isConnected = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_CONN_ERROR,
  ) {
    val isConnectionFakeError by
        appEnvironment.fakeConnection.isError.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_CONNECTION_FIELD_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.error_connection_title),
        description = stringResource(R.string.error_connection_explain),
        checked = isConnectionFakeError,
        onCheckedChange = { appEnvironment.updateConnection(isError = it) },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_SOCKET_OOM,
  ) {
    val isSocketOOM by
        appEnvironment.isSocketBuilderOOMServer.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_SOCKET_FAKE_OOM_SERVER_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.error_socket_oom_server_title),
        description = stringResource(R.string.error_socket_oom_server_explain),
        checked = isSocketOOM,
        onCheckedChange = { appEnvironment.handleToggleSocketBuilderOOMServerEnabled() },
    )
  }

  item(
      contentType = SettingsContentTypes.DEBUG_SOCKET_ERROR,
  ) {
    val isSocketFake by
        appEnvironment.isSocketBuilderOOMClient.collectAsStateWithLifecycle(
            AppDevEnvironment.Defaults.IS_SOCKET_FAKE_OOM_CLIENT_INITIAL_STATE
        )
    DebugItem(
        modifier = itemModifier,
        title = stringResource(R.string.error_socket_oom_client_title),
        description = stringResource(R.string.error_socket_oom_client_explain),
        checked = isSocketFake,
        onCheckedChange = { appEnvironment.handleToggleSocketBuilderOOMClientEnabled() },
    )
  }

  item(
      contentType = SettingsContentTypes.BOTTOM_SPACER,
  ) {
    Spacer(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
    )
  }
}
