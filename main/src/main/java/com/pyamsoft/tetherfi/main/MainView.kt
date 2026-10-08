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

import androidx.annotation.StringRes

enum class MainView(
    @param:StringRes val displayNameRes: Int,
) {
  STATUS(R.string.main_tab_name_status),
  BEHAVIOR(R.string.main_tab_name_behavior),
  INFO(R.string.main_tab_name_info),
  CONNECTIONS(R.string.main_tab_name_connections),
  SETTINGS(R.string.main_tab_name_settings),
}
