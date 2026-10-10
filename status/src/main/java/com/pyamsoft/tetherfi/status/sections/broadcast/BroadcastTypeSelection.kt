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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.ui.ServerViewState

private val BROADCAST_WIFI_STRINGS =
    Strings(
        title = R.string.expert_broadcast_type_wifi_direct_title,
        description = R.string.expert_broadcast_type_wifi_direct_description,
    )

private val BROADCAST_RNDIS_STRINGS =
    Strings(
        title = R.string.expert_broadcast_type_rndis_title,
        description = R.string.expert_broadcast_type_rndis_description,
    )

@Composable
internal fun BroadcastTypeSelection(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
    isEditable: Boolean,
    onSelectBroadcastType: (BroadcastType) -> Unit,
) {
  val currentBroadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()
  val allBroadcastTypes = remember { BroadcastType.entries.toTypedArray() }

  val handleResolveStrings by rememberUpdatedState { type: BroadcastType ->
    when (type) {
      BroadcastType.WIFI_DIRECT -> BROADCAST_WIFI_STRINGS
      BroadcastType.RNDIS -> BROADCAST_RNDIS_STRINGS
    }
  }

  BroadcastSelection(
      modifier = modifier,
      isEditable = isEditable,
      onSelect = onSelectBroadcastType,
      currentSelection = currentBroadcastType,
      allSelections = allBroadcastTypes,
      title = R.string.expert_broadcast_title,
      onResolveStrings = { handleResolveStrings(it) },
  )
}
