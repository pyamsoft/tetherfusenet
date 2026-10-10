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
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.ui.ServerViewState

private val NETWORK_STRINGS =
    Strings(
        title = R.string.expert_preferred_network_title,
        description = R.string.expert_preferred_network_description,
    )

private val NETWORK_NONE_STRINGS =
    Strings(
        title = R.string.expert_preferred_network_none_title,
        description = 0,
    )

private val NETWORK_WIFI_STRINGS =
    Strings(
        title = R.string.expert_preferred_network_wifi_title,
        description = 0,
    )

private val NETWORK_CELLULAR_STRINGS =
    Strings(
        title = R.string.expert_preferred_network_cellular_title,
        description = 0,
    )

@Composable
internal fun PreferredNetworkSelection(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
    appName: String,
    isEditable: Boolean,
    onSelectPreferredNetwork: (PreferredNetwork) -> Unit,
) {
  val currentPreferredNetwork by serverViewState.preferredNetwork.collectAsStateWithLifecycle()
  val allPreferredNetworks = remember { PreferredNetwork.entries.toTypedArray() }

  val handleResolveStrings by rememberUpdatedState { network: PreferredNetwork ->
    when (network) {
      PreferredNetwork.NONE -> NETWORK_NONE_STRINGS
      PreferredNetwork.WIFI -> NETWORK_WIFI_STRINGS
      PreferredNetwork.CELLULAR -> NETWORK_CELLULAR_STRINGS
    }
  }

  BroadcastSelection(
      modifier = modifier,
      appName = appName,
      isEditable = isEditable,
      onSelect = onSelectPreferredNetwork,
      currentSelection = currentPreferredNetwork,
      allSelections = allPreferredNetworks,
      strings = NETWORK_STRINGS,
      onResolveStrings = { handleResolveStrings(it) },
  )
}
