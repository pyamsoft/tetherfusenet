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

package com.pyamsoft.tetherfi.behavior

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.pyamsoft.pydroid.arch.UiViewState
import com.pyamsoft.tetherfi.server.ServerPerformanceLimit
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class BehaviorViewDialogs {
  SOCKET_TIMEOUT,
}

enum class BehaviorViewTweaks {
  IGNORE_VPN,
  IGNORE_LOCATION,
  KEEP_SCREEN_ON,
  SHUTDOWN_NO_CLIENTS,
  USE_WAKELOCK,
}

@Stable
interface BehaviorViewState : UiViewState {
  val loadingState: StateFlow<LoadingState>

  // Operating Settings
  val hasNotificationPermission: StateFlow<Boolean>
  val isBatteryOptimizationsIgnored: StateFlow<Boolean>

  // Tweaks
  val isIgnoreVpn: StateFlow<Boolean>
  val isIgnoreLocation: StateFlow<Boolean>
  val isShutdownWithNoClients: StateFlow<Boolean>
  val isKeepScreenOn: StateFlow<Boolean>
  val isHoldWakelock: StateFlow<Boolean>

  // Expert
  val socketTimeout: StateFlow<ServerSocketTimeout>

  // Dialogs
  val isShowingSocketTimeout: StateFlow<Boolean>

  @Stable
  @Immutable
  enum class LoadingState {
    NONE,
    LOADING,
    DONE,
  }
}

@Stable
class MutableBehaviorViewState @Inject internal constructor() : BehaviorViewState {
  override val loadingState = MutableStateFlow(BehaviorViewState.LoadingState.NONE)

  override val hasNotificationPermission = MutableStateFlow(false)

  override val isBatteryOptimizationsIgnored = MutableStateFlow(false)
  override val socketTimeout =
      MutableStateFlow<ServerSocketTimeout>(ServerSocketTimeout.Defaults.BALANCED)

  override val isShowingSocketTimeout = MutableStateFlow(false)

  override val isHoldWakelock = MutableStateFlow(false)
  override val isIgnoreVpn = MutableStateFlow(false)
  override val isIgnoreLocation = MutableStateFlow(false)
  override val isShutdownWithNoClients = MutableStateFlow(false)
  override val isKeepScreenOn = MutableStateFlow(false)
}
