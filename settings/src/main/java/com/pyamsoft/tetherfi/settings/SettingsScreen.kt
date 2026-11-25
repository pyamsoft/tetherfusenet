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

package com.pyamsoft.tetherfi.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.settings.SettingsPage
import com.pyamsoft.pydroid.util.isDebugMode
import com.pyamsoft.tetherfi.core.AppDevEnvironment
import com.pyamsoft.tetherfi.ui.BottomPadding
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH

private enum class SettingsItems {
  EXTRA_BOTTOM_PADDING,
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    appEnvironment: AppDevEnvironment,
) {
  val context = LocalContext.current

  val baselinePadding = MaterialTheme.keylines.baseline
  val extraDebugModifier =
      remember(baselinePadding) { Modifier.fillMaxWidth().padding(bottom = baselinePadding) }

  SettingsPage(
      modifier = modifier,
      contentMaxWidth = LANDSCAPE_MAX_WIDTH,
      listState = listState,
      extraDebugContent = {
        renderExperiments(
            itemModifier = extraDebugModifier,
        )

        if (context.isDebugMode()) {
          renderExtraDebugContent(
              itemModifier = extraDebugModifier,
              appEnvironment = appEnvironment,
          )
        }
      },
      extraBottomContent = {
        item(contentType = SettingsItems.EXTRA_BOTTOM_PADDING) {
          BottomPadding()
        }
      },
  )
}
