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

package com.pyamsoft.tetherfi.behavior.sections.expert

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.textAlpha

@Composable
internal fun PowerBalance(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
    appName: String,
    isEditable: Boolean,
    onShowPowerBalance: () -> Unit,
) {
  val isNewEngine by serverViewState.isNewEngine.collectAsStateWithLifecycle()

  val descriptionId = remember(isNewEngine) {
    if (isNewEngine) R.string.expert_power_balance_new_description else R.string.expert_power_balance_old_description
  }

  val buttonId = remember(isNewEngine) {
    if (isNewEngine) R.string.expert_power_balance_new_button else R.string.expert_power_balance_old_button
  }

  val isButtonEnabled = remember(isEditable, isNewEngine) {
    if (isNewEngine) {
      // Never enabled for new engine
      return@remember false
    }

    return@remember isEditable
  }

  Column(
      modifier = modifier,
  ) {
    Text(
        text = stringResource(R.string.expert_power_balance_title),
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
        modifier = Modifier.padding(bottom = MaterialTheme.keylines.content),
        text = stringResource(descriptionId, appName),
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = textAlpha(isEditable),
                    ),
            ),
    )

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onShowPowerBalance,
        enabled = isButtonEnabled,
    ) {
      Text(
          text = stringResource(buttonId),
      )
    }
  }
}