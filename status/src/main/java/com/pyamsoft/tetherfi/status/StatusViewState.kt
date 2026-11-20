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

package com.pyamsoft.tetherfi.status

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.pyamsoft.pydroid.arch.UiViewState
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable
interface StatusViewState : UiViewState {

  val loadingState: StateFlow<LoadingState>

  // For editing, at proxy runtime we pull from ServerViewState
  val ssid: TextFieldState
  val password: TextFieldState

  val isPasswordVisible: StateFlow<Boolean>
  val band: StateFlow<ServerNetworkBand?>

  @Stable
  @Immutable
  enum class LoadingState {
    NONE,
    LOADING,
    DONE,
  }
}

@Stable
class MutableStatusViewState @Inject internal constructor() : StatusViewState {
  override val loadingState = MutableStateFlow(StatusViewState.LoadingState.NONE)

  override val ssid = TextFieldState("")
  override val password = TextFieldState("")
  override val isPasswordVisible = MutableStateFlow(false)
  override val band = MutableStateFlow<ServerNetworkBand?>(null)
}
