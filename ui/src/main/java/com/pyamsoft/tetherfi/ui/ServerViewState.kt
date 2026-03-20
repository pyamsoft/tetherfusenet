/*
 * Copyright 2025 pyamsoft
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

package com.pyamsoft.tetherfi.ui

import androidx.compose.runtime.Stable
import com.pyamsoft.pydroid.arch.UiViewState
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.status.RunningStatus
import kotlinx.coroutines.flow.StateFlow

/** Activity global view state for server variables */
@Stable
interface ServerViewState : UiViewState {
  val group: StateFlow<BroadcastNetworkStatus.GroupInfo>
  val connection: StateFlow<BroadcastNetworkStatus.ConnectionInfo>

  val isHttpEnabled: StateFlow<Boolean>
  val httpPort: StateFlow<Int>

  val isSocksEnabled: StateFlow<Boolean>
  val socksPort: StateFlow<Int>

  val broadcastType: StateFlow<BroadcastType?>
  val preferredNetwork: StateFlow<PreferredNetwork?>

  val wiDiStatus: StateFlow<RunningStatus>
  val proxyStatus: StateFlow<RunningStatus>

  val isNewEngine: StateFlow<Boolean>
}
