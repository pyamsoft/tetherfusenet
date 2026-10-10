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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.behavior.BehaviorViewState
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.ui.Label
import com.pyamsoft.tetherfi.ui.textAlpha

private enum class ExpertSettingsContentTypes {
  SETTINGS,
  SOCKET_TIMEOUT,
}

internal fun LazyListScope.renderExpertSettings(
    itemModifier: Modifier = Modifier,
    state: BehaviorViewState,
    isEditable: Boolean,
    appName: String,
    onShowSocketTimeout: () -> Unit,
    onToggleWakeLock: () -> Unit,
) {
  item(
      contentType = ExpertSettingsContentTypes.SETTINGS,
  ) {
    Label(
        modifier =
            Modifier.padding(horizontal = MaterialTheme.keylines.content)
                .padding(
                    top = MaterialTheme.keylines.content,
                    bottom = MaterialTheme.keylines.typography,
                ),
        text = stringResource(R.string.expert_title),
        textAlign = TextAlign.Center,
    )
  }

  item(
      contentType = ExpertSettingsContentTypes.SOCKET_TIMEOUT,
  ) {
    Card(
        modifier = itemModifier.padding(bottom = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      Text(
          modifier = Modifier.padding(all = MaterialTheme.keylines.content),
          text = stringResource(R.string.expert_description, appName),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color =
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(
                          alpha = textAlpha(isEditable),
                      ),
              ),
      )

      WakeLockTweak(
          modifier = Modifier.fillMaxWidth(),
          appName = appName,
          isEditable = isEditable,
          state = state,
          onToggleWakeLock = onToggleWakeLock,
      )

      SocketTimeout(
          modifier = Modifier.padding(MaterialTheme.keylines.content),
          isEditable = isEditable,
          appName = appName,
          onShowSocketTimeout = onShowSocketTimeout,
      )
    }
  }
}
