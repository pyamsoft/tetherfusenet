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

package com.pyamsoft.tetherfi.behavior.sections.expert

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.tetherfi.behavior.BehaviorViewState
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.behavior.sections.ToggleSwitch
import com.pyamsoft.tetherfi.ui.checkable.rememberCheckableColor

@Composable
internal fun WakeLockTweak(
    modifier: Modifier = Modifier,
    appName: String,
    isEditable: Boolean,
    state: BehaviorViewState,
    onToggleWakeLock: () -> Unit,
) {
  val isHoldWakelock by state.isHoldWakelock.collectAsStateWithLifecycle()
  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = "Use Wakelock",
          condition = isHoldWakelock,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  ToggleSwitch(
      modifier = modifier,
      isEditable = isEditable,
      color = color,
      checked = isHoldWakelock,
      title = stringResource(R.string.hold_wakelock_title),
      description = stringResource(R.string.hold_wakelock_description, appName),
      onClick = onToggleWakeLock,
  )
}
