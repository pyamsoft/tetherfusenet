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

package com.pyamsoft.tetherfi.status.sections.tiles.tile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.ui.R as R2
import com.pyamsoft.tetherfi.ui.dialog.ServerErrorTile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
internal fun GroupErrorTile(
    modifier: Modifier = Modifier,
    group: BroadcastNetworkStatus.GroupInfo,
    onShowGroupError: () -> Unit,
) {
  val (showErrorTile, setShowErrorTile) = rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(group) {
    when (group) {
      is BroadcastNetworkStatus.GroupInfo.Error -> {
        // Debounce just a little bit, just incase we are just starting up normally
        delay(1.seconds)
        setShowErrorTile(true)
      }
      is BroadcastNetworkStatus.GroupInfo.Empty -> {
        // If this is empty for a while, show it as error
        delay(5.seconds)
        setShowErrorTile(true)
      }
      else -> {
        // Not a problem
        setShowErrorTile(false)
      }
    }
  }

  StatusTile(
      modifier = modifier,
      show = showErrorTile,
      borderColor = MaterialTheme.colorScheme.error,
  ) {
    ServerErrorTile(
        onShowError = onShowGroupError,
    ) { modifier, renderIcon ->
      Row(
          modifier = Modifier.fillMaxWidth().then(modifier),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        val color = LocalContentColor.current

        renderIcon()

        Text(
            text = stringResource(R2.string.hotspot_group_removed),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = color,
                ),
        )
      }
    }
  }
}
