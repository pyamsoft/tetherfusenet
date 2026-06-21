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

package com.pyamsoft.tetherfi.server

import androidx.annotation.CheckResult
import kotlinx.coroutines.flow.Flow

interface ProxyPreferences {

  @CheckResult fun listenForPortChanges(): Flow<Int>

  fun setPort(port: Int)

  @CheckResult fun listenForHttpEnabledChanges(): Flow<Boolean>

  fun setHttpEnabled(enabled: Boolean)

  @CheckResult fun listenForSocksEnabledChanges(): Flow<Boolean>

  fun setSocksEnabled(enabled: Boolean)
}
