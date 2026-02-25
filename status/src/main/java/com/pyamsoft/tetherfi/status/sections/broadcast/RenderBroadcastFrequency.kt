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

package com.pyamsoft.tetherfi.status.sections.broadcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.ImageDefaults
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.checkable.rememberCheckableColor
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.rememberCheckableIconColor
import com.pyamsoft.tetherfi.ui.surfaceAlpha
import com.pyamsoft.tetherfi.ui.textAlpha

private enum class RenderBroadcastFrequencyContentTypes {
  BANDS
}

internal fun LazyListScope.renderBroadcastFrequency(
    itemModifier: Modifier = Modifier,
    state: StatusViewState,
    serverViewState: ServerViewState,
    isEditable: Boolean,
    onSelectBand: (ServerNetworkBand) -> Unit,
) {
  item(
      contentType = RenderBroadcastFrequencyContentTypes.BANDS,
  ) {
    val band by state.band.collectAsStateWithLifecycle()
    val canUseCustomConfig = remember { ServerDefaults.canUseCustomConfig() }
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()

    // Render only if Wifi Direct
    if (broadcastType != BroadcastType.WIFI_DIRECT) {
      return@item
    }

    Card(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      Column {
        // Small label above
        Text(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content),
            text = stringResource(R.string.broadcast_frequency),
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.W700,
                    color =
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = textAlpha(isEditable),
                        ),
                ),
        )

        if (canUseCustomConfig) {
          // Filter out options which are above our API level
          val bands = remember { ServerNetworkBand.entries.filter { it.enabled } }

          // Then the buttons
          for (b in bands) {
            SelectableNetworkBand(
                isEditable = isEditable,
                band = b,
                currentSelectedBand = band,
                onSelectBand = onSelectBand,
            )
          }

          Spacer(
              modifier = Modifier.height(MaterialTheme.keylines.content),
          )
        } else {
          Text(
              modifier = Modifier.padding(MaterialTheme.keylines.content),
              text = stringResource(R.string.network_bands_system_defined),
              style =
                  MaterialTheme.typography.bodyLarge.copy(
                      fontWeight = FontWeight.W700,
                      color =
                          MaterialTheme.colorScheme.onSecondaryContainer.copy(
                              alpha = surfaceAlpha(isEditable),
                          ),
                  ),
          )
        }
      }
    }
  }
}

@Composable
private fun SelectableNetworkBand(
    modifier: Modifier = Modifier,
    isEditable: Boolean,
    band: ServerNetworkBand,
    currentSelectedBand: ServerNetworkBand?,
    onSelectBand: (ServerNetworkBand) -> Unit,
) {
  val hapticManager = LocalHapticManager.current
  val isSelected = remember(band, currentSelectedBand) { band == currentSelectedBand }

  val titleRes =
      remember(band) {
        when (band) {
          ServerNetworkBand.LEGACY -> R.string.network_bands_legacy_title
          ServerNetworkBand.MODERN -> R.string.network_bands_modern_title
          ServerNetworkBand.MODERN_6 -> R.string.network_bands_modern_6_title
        }
      }

  val descriptionRes =
      remember(band) {
        when (band) {
          ServerNetworkBand.LEGACY -> R.string.network_bands_legacy_description
          ServerNetworkBand.MODERN -> R.string.network_bands_modern_description
          ServerNetworkBand.MODERN_6 -> R.string.network_bands_modern_6_description
        }
      }

  val title = stringResource(titleRes)
  val description = stringResource(descriptionRes)
  val iconColor = rememberCheckableIconColor(isEditable, isSelected)

  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = title,
          condition = isSelected,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  Column(
      modifier =
          modifier
              .clickable(enabled = isEditable) {
                hapticManager?.toggleOn()
                onSelectBand(band)
              }
              .padding(horizontal = MaterialTheme.keylines.content)
              .padding(top = MaterialTheme.keylines.content),
  ) {
    Row(
        verticalAlignment = Alignment.Top,
    ) {
      Text(
          modifier = Modifier.weight(1F).padding(bottom = MaterialTheme.keylines.baseline),
          text = title,
          style =
              MaterialTheme.typography.bodyLarge.copy(
                  fontWeight = FontWeight.W700,
                  color =
                      color.copy(
                          alpha = textAlpha(isEditable),
                      ),
              ),
      )

      Icon(
          modifier = Modifier.size(ImageDefaults.IconSize),
          painter =
              if (isSelected) IconPainters.checkCircle() else IconPainters.radioButtonUnchecked(),
          contentDescription = title,
          tint = iconColor,
      )
    }

    Text(
        text = description,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = textAlpha(isEditable),
                    ),
            ),
    )
  }
}
