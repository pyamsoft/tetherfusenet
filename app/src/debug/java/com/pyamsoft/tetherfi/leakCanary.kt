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

package com.pyamsoft.tetherfi

import leakcanary.LeakCanary
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePattern

internal fun configureLeakCanary() {
  val original = LeakCanary.config

  val tileServiceBinderLeak =
      LibraryLeakReferenceMatcher(
          pattern =
              ReferencePattern.InstanceFieldPattern(
                  className = $$"android.service.quicksettings.TileService$2",
                  fieldName = $$"this$0",
              ),
          description = "The System holds this around but it's not our leak.",
      )

  LeakCanary.config =
      original.copy(
          referenceMatchers = original.referenceMatchers + tileServiceBinderLeak,
      )
}
