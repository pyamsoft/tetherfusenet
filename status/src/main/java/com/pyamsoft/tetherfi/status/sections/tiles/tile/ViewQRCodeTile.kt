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

package com.pyamsoft.tetherfi.status.sections.tiles.tile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.ui.icons.IconPainters

@Composable
internal fun ViewQRCodeTile(
    modifier: Modifier = Modifier,
    isQREnabled: Boolean,
    onShowQRCode: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  StatusTile(
      modifier = modifier,
      show = true,
      enabled = isQREnabled,
  ) {
    Row(
        modifier =
            Modifier.fillMaxWidth().clickable(enabled = isQREnabled) {
              hapticManager?.actionButtonPress()
              onShowQRCode()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
          onClick = {
            hapticManager?.actionButtonPress()
            onShowQRCode()
          },
          enabled = isQREnabled,
      ) {
        Icon(
            painter = IconPainters.qrCode(),
            contentDescription = stringResource(R.string.tile_qr_code),
            tint =
                MaterialTheme.colorScheme.run {
                  if (isQREnabled) {
                    primary
                  } else {
                    onSurfaceVariant
                  }
                },
        )
      }

      Text(
          text = stringResource(R.string.tile_qr_code),
          style =
              MaterialTheme.typography.bodySmall.copy(
                  color =
                      MaterialTheme.colorScheme.run {
                        if (isQREnabled) {
                          onSurface
                        } else {
                          onSurfaceVariant
                        }
                      },
              ),
      )
    }
  }
}
