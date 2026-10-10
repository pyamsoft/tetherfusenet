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

package com.pyamsoft.tetherfi.behavior.sections.tweaks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.behavior.BehaviorViewState
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.behavior.sections.ToggleSwitch
import com.pyamsoft.tetherfi.ui.Label
import com.pyamsoft.tetherfi.ui.R as R2
import com.pyamsoft.tetherfi.ui.checkable.rememberCheckableColor
import com.pyamsoft.tetherfi.ui.textAlpha

private enum class ContentTypes {
  LABEL,
  BEHAVIOR_TWEAKS,
}

@LintIgnoreLongMethod
internal fun LazyListScope.renderTweaks(
    itemModifier: Modifier = Modifier,
    appName: String,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleIgnoreVpn: () -> Unit,
    onToggleIgnoreLocation: () -> Unit,
    onToggleShutdownWithNoClients: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
) {
  item(contentType = ContentTypes.LABEL) {
    Label(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = MaterialTheme.keylines.content)
                .padding(bottom = MaterialTheme.keylines.baseline),
        text = stringResource(R.string.tweaks_title),
        textAlign = TextAlign.Center,
    )
  }

  item(
      contentType = ContentTypes.BEHAVIOR_TWEAKS,
  ) {
    Card(
        modifier = itemModifier,
        shape = MaterialTheme.shapes.large,
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
      Column(
          modifier = Modifier.padding(vertical = MaterialTheme.keylines.content),
      ) {
        Text(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = MaterialTheme.keylines.content)
                    .padding(bottom = MaterialTheme.keylines.content),
            text = stringResource(R.string.tweaks_description, appName),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = textAlpha(isEditable),
                        ),
                ),
        )

        KeepScreenOnTweak(
            modifier = Modifier.fillMaxWidth(),
            isEditable = isEditable,
            state = state,
            onToggleKeepScreenOn = onToggleKeepScreenOn,
        )

        LocationBlockerTweak(
            modifier = Modifier.fillMaxWidth(),
            appName = appName,
            isEditable = isEditable,
            state = state,
            onToggleIgnoreLocation = onToggleIgnoreLocation,
        )

        VPNBlockerTweak(
            modifier = Modifier.fillMaxWidth(),
            appName = appName,
            isEditable = isEditable,
            state = state,
            onToggleIgnoreVpn = onToggleIgnoreVpn,
        )

        StopHotspotTweak(
            modifier = Modifier.fillMaxWidth(),
            appName = appName,
            isEditable = isEditable,
            state = state,
            onToggleShutdownWithNoClients = onToggleShutdownWithNoClients,
        )
      }
    }
  }
}

@Composable
private fun LocationBlockerTweak(
    modifier: Modifier = Modifier,
    appName: String,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleIgnoreLocation: () -> Unit,
) {
  val isIgnoreLocation by state.isIgnoreLocation.collectAsStateWithLifecycle()
  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = "Ignore Location",
          condition = isIgnoreLocation,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  ToggleSwitch(
      modifier = modifier,
      isEditable = isEditable,
      color = color,
      checked = isIgnoreLocation,
      title = stringResource(R2.string.ignore_location_title),
      description = stringResource(R.string.ignore_location_description, appName),
      onClick = onToggleIgnoreLocation,
  )
}

@Composable
private fun VPNBlockerTweak(
    modifier: Modifier = Modifier,
    appName: String,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleIgnoreVpn: () -> Unit,
) {
  val isIgnoreVpn by state.isIgnoreVpn.collectAsStateWithLifecycle()
  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = "Ignore VPN",
          condition = isIgnoreVpn,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  ToggleSwitch(
      modifier = modifier,
      isEditable = isEditable,
      color = color,
      checked = isIgnoreVpn,
      title = stringResource(R2.string.ignore_vpn_title),
      description = stringResource(R.string.ignore_vpn_description, appName),
      onClick = onToggleIgnoreVpn,
  )
}

@Composable
private fun StopHotspotTweak(
    modifier: Modifier = Modifier,
    appName: String,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleShutdownWithNoClients: () -> Unit,
) {
  val isShutdownWithNoClients by state.isShutdownWithNoClients.collectAsStateWithLifecycle()
  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = "Shutdown No Clients",
          condition = isShutdownWithNoClients,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  ToggleSwitch(
      modifier = modifier,
      isEditable = isEditable,
      color = color,
      checked = isShutdownWithNoClients,
      title = stringResource(R.string.shutdown_no_client_title),
      description = stringResource(R.string.shutdown_no_client_description, appName),
      onClick = onToggleShutdownWithNoClients,
  )
}

@Composable
private fun KeepScreenOnTweak(
    modifier: Modifier = Modifier,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleKeepScreenOn: () -> Unit,
) {
  val isKeepScreenOn by state.isKeepScreenOn.collectAsStateWithLifecycle()
  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = "Keep Screen On",
          condition = isKeepScreenOn,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  ToggleSwitch(
      modifier = modifier,
      isEditable = isEditable,
      color = color,
      checked = isKeepScreenOn,
      title = stringResource(R.string.keep_screen_on_title),
      description = stringResource(R.string.keep_screen_on_description),
      onClick = onToggleKeepScreenOn,
  )
}
