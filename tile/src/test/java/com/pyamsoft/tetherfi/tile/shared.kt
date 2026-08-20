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

package com.pyamsoft.tetherfi.tile

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.util.AppDispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

@CheckResult
internal fun TestScope.testAppDispatchers(): AppDispatchers {
  val dispatcher = StandardTestDispatcher(testScheduler)
  return object : AppDispatchers {
    override val default = dispatcher
    override val main = dispatcher
    override val io = dispatcher
  }
}
