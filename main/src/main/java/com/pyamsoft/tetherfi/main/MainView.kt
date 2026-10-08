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

package com.pyamsoft.tetherfi.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.pyamsoft.tetherfi.service.R as R2
import com.pyamsoft.tetherfi.ui.R as R3

enum class MainView(
    @param:StringRes val displayNameRes: Int,
    @param:DrawableRes val icon: Int,
) {
  STATUS(R.string.main_tab_name_status, R2.drawable.ic_wifi_tethering_24),
  BEHAVIOR(R.string.main_tab_name_behavior, R.drawable.psychology_24px),
  INFO(R.string.main_tab_name_info, R.drawable.quick_reference_24px),
  CONNECTIONS(R.string.main_tab_name_connections, R.drawable.hub_24px),
  SETTINGS(R.string.main_tab_name_settings, R3.drawable.settings_24px),
}
