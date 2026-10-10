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

package com.pyamsoft.tetherfi.behavior.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.ui.textAlpha

@Composable
internal fun ToggleSwitch(
    modifier: Modifier = Modifier,
    isEditable: Boolean,
    color: Color,
    checked: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  Column(
      modifier =
          modifier
              .clickable(enabled = isEditable) {
                if (checked) {
                  hapticManager?.toggleOff()
                } else {
                  hapticManager?.toggleOn()
                }
                onClick()
              }
              .padding(vertical = MaterialTheme.keylines.baseline),
  ) {
    Row(
        modifier =
            Modifier.padding(bottom = MaterialTheme.keylines.baseline)
                .padding(horizontal = MaterialTheme.keylines.content),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Switch(
          modifier = Modifier.padding(end = MaterialTheme.keylines.content),
          enabled = isEditable,
          checked = checked,
          onCheckedChange = null,
      )

      Text(
          modifier = Modifier.weight(1F),
          text = title,
          style =
              MaterialTheme.typography.bodyLarge.copy(
                  fontWeight = FontWeight.W700,
                  color = color,
              ),
      )
    }

    Text(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.keylines.content),
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
