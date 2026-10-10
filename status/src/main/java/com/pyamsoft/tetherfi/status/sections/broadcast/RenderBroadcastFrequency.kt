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

package com.pyamsoft.tetherfi.status.sections.broadcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.surfaceAlpha
import com.pyamsoft.tetherfi.ui.textAlpha

private enum class RenderBroadcastFrequencyContentTypes {
  BANDS
}

private val BAND_24GHZ_STRINGS =
    Strings(
        title = R.string.network_bands_legacy_title,
        description = R.string.network_bands_legacy_description,
    )

private val BAND_5GHZ_STRINGS =
    Strings(
        title = R.string.network_bands_modern_title,
        description = R.string.network_bands_modern_description,
    )

private val BAND_6GHZ_STRINGS =
    Strings(
        title = R.string.network_bands_modern_6_title,
        description = R.string.network_bands_modern_6_description,
    )

@LintIgnoreLongMethod
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
        if (canUseCustomConfig) {
          val currentBand by state.band.collectAsStateWithLifecycle()
          val allBands = remember { ServerNetworkBand.entries.filter { it.enabled } }
          // Default to legacy since we must show someting
          val displayBand = remember(currentBand) { currentBand ?: ServerNetworkBand.LEGACY }

          val handleResolveStrings by rememberUpdatedState { band: ServerNetworkBand ->
            when (band) {
              ServerNetworkBand.LEGACY -> BAND_24GHZ_STRINGS
              ServerNetworkBand.MODERN -> BAND_5GHZ_STRINGS
              ServerNetworkBand.MODERN_6 -> BAND_6GHZ_STRINGS
            }
          }

          BroadcastSelection(
              modifier = Modifier.padding(top = MaterialTheme.keylines.content),
              isEditable = isEditable,
              onSelect = onSelectBand,
              currentSelection = displayBand,
              allSelections = allBands,
              title = R.string.broadcast_frequency,
              onResolveStrings = { handleResolveStrings(it) },
          )

          Spacer(
              modifier = Modifier.height(MaterialTheme.keylines.content),
          )
        } else {
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

          Text(
              modifier =
                  Modifier.padding(horizontal = MaterialTheme.keylines.content)
                      .padding(bottom = MaterialTheme.keylines.content)
                      .padding(top = MaterialTheme.keylines.baseline),
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
