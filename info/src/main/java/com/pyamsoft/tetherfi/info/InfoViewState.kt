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

package com.pyamsoft.tetherfi.info

import androidx.compose.runtime.Stable
import com.pyamsoft.pydroid.arch.UiViewState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class InfoViewOptionsType {
  HTTP,
  SOCKS,
}

@Stable
interface InfoViewState : UiViewState {
  val isPasswordVisible: StateFlow<Boolean>
  val showHttpOptions: StateFlow<Boolean>
  val showSocksOptions: StateFlow<Boolean>
}

@Stable
class MutableInfoViewState @Inject internal constructor() : InfoViewState {
  override val isPasswordVisible = MutableStateFlow(false)
  override val showHttpOptions = MutableStateFlow(false)
  override val showSocksOptions = MutableStateFlow(false)
}
