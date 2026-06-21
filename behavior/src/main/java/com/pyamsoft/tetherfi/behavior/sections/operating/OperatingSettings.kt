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

package com.pyamsoft.tetherfi.behavior.sections.operating

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.behavior.BehaviorViewState
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.ui.Label
import com.pyamsoft.tetherfi.ui.ServerViewState

private enum class OperatingSettingsContentTypes {
  LABEL,
  BATTERY_OPTIMIZATION,
  NOTIFICATIONS,
}

internal fun LazyListScope.renderOperatingSettings(
    itemModifier: Modifier = Modifier,
    isEditable: Boolean,
    appName: String,
    state: BehaviorViewState,

    // Battery optimization
    onDisableBatteryOptimizations: () -> Unit,

    // Notifications
    showNotificationSettings: Boolean,
    onNotificationPermissionRequest: () -> Unit,
) {
  item(
      contentType = OperatingSettingsContentTypes.LABEL,
  ) {
    Label(
        modifier =
            itemModifier
                .padding(top = MaterialTheme.keylines.content)
                .padding(bottom = MaterialTheme.keylines.baseline),
        text = stringResource(R.string.operating_settings),
    )
  }

  item(
      contentType = OperatingSettingsContentTypes.BATTERY_OPTIMIZATION,
  ) {
    BatteryOptimization(
        modifier = itemModifier,
        isEditable = isEditable,
        appName = appName,
        state = state,
        onDisableBatteryOptimizations = onDisableBatteryOptimizations,
    )
  }

  if (showNotificationSettings) {
    item(
        contentType = OperatingSettingsContentTypes.NOTIFICATIONS,
    ) {
      NotificationPerms(
          modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
          state = state,
          isEditable = isEditable,
          onRequest = onNotificationPermissionRequest,
      )
    }
  }
}
