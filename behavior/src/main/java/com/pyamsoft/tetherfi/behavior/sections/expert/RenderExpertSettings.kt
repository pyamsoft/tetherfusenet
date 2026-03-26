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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.ui.ServerViewState

private enum class ExpertSettingsContentTypes {
  SETTINGS,
  POWER_BALANCE,
  SOCKET_TIMEOUT,
}

internal fun LazyListScope.renderExpertSettings(
    itemModifier: Modifier = Modifier,
    isEditable: Boolean,
    appName: String,
    serverViewState: ServerViewState,
    onShowPowerBalance: () -> Unit,
    onShowSocketTimeout: () -> Unit,
) {
  item(
      contentType = ExpertSettingsContentTypes.SETTINGS,
  ) {
    ExpertSettings(
        modifier = itemModifier.padding(vertical = MaterialTheme.keylines.content),
        isEditable = isEditable,
        appName = appName,
    )
  }

  item(
      contentType = ExpertSettingsContentTypes.POWER_BALANCE,
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
      PowerBalance(
          modifier = Modifier.padding(MaterialTheme.keylines.content),
          serverViewState = serverViewState,
          isEditable = isEditable,
          appName = appName,
          onShowPowerBalance = onShowPowerBalance,
      )
    }
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
      SocketTimeout(
          modifier = Modifier.padding(MaterialTheme.keylines.content),
          isEditable = isEditable,
          appName = appName,
          onShowSocketTimeout = onShowSocketTimeout,
      )
    }
  }
}