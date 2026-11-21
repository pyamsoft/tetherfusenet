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

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun StatusTile(
    modifier: Modifier = Modifier,
    show: Boolean,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
  CapturedAnimatedVisibility(
      modifier = modifier,
      visible = show,
  ) {
    Card(
        border =
            BorderStroke(
                width = 2.dp,
                color = if (enabled) borderColor else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      content()
    }
  }
}
