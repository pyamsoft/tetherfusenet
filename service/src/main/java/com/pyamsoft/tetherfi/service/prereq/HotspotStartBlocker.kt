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

package com.pyamsoft.tetherfi.service.prereq

enum class HotspotStartBlocker(val required: Boolean) {
  /** We must have permission */
  PERMISSION(required = true),

  /** In the future, the user can "agree" to "try anyway" */
  VPN(required = false),

  /** In the future, the user can "agree" to "try anyway" */
  LOCATION(required = false),

  /** TetherFuseNet MUST be allowed to use background data. It does NOT need to be whitelisted */
  BACKGROUND_DATA(required = false),

  /** Wifi direct mode needs wifi */
  WIFI(required = true),
}
