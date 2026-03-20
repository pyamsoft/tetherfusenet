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

package com.pyamsoft.tetherfi.main

import androidx.compose.runtime.Stable
import com.pyamsoft.tetherfi.core.ActivityScope
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.server.network.PreferredNetwork
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.ui.ServerViewState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MainViewDialogs {
  SETTINGS,
  QR_CODE,
  SLOW_SPEED_HELP,
  SETUP_ERROR,
  NETWORK_ERROR,
  HOTSPOT_ERROR,
  BROADCAST_ERROR,
  PROXY_ERROR,
}

@Stable
interface MainViewState : ServerViewState {
  // Dialogs
  val isSettingsOpen: StateFlow<Boolean>
  val isShowingQRCodeDialog: StateFlow<Boolean>
  val isShowingSlowSpeedHelp: StateFlow<Boolean>

  // Errors
  val isShowingSetupError: StateFlow<Boolean>
  val isShowingNetworkError: StateFlow<Boolean>
  val isShowingHotspotError: StateFlow<Boolean>
  val isShowingBroadcastError: StateFlow<Boolean>
  val isShowingProxyError: StateFlow<Boolean>

  // Hotspot
  val startBlockers: StateFlow<Collection<HotspotStartBlocker>>
}

@Stable
@ActivityScope
class MutableMainViewState @Inject internal constructor() : MainViewState {
  override val group =
      MutableStateFlow<BroadcastNetworkStatus.GroupInfo>(BroadcastNetworkStatus.GroupInfo.Empty)
  override val connection =
      MutableStateFlow<BroadcastNetworkStatus.ConnectionInfo>(
          BroadcastNetworkStatus.ConnectionInfo.Empty
      )

  override val isNewEngine = MutableStateFlow(false)

  override val isHttpEnabled = MutableStateFlow(false)
  override val httpPort = MutableStateFlow(0)

  override val isSocksEnabled = MutableStateFlow(false)
  override val socksPort = MutableStateFlow(0)

  override val broadcastType = MutableStateFlow<BroadcastType?>(null)
  override val preferredNetwork = MutableStateFlow<PreferredNetwork?>(null)

  override val isSettingsOpen = MutableStateFlow(false)
  override val isShowingQRCodeDialog = MutableStateFlow(false)
  override val isShowingSlowSpeedHelp = MutableStateFlow(false)

  override val wiDiStatus = MutableStateFlow<RunningStatus>(RunningStatus.NotRunning)
  override val proxyStatus = MutableStateFlow<RunningStatus>(RunningStatus.NotRunning)
  override val startBlockers = MutableStateFlow<Collection<HotspotStartBlocker>>(emptySet())

  override val isShowingSetupError = MutableStateFlow(false)
  override val isShowingNetworkError = MutableStateFlow(false)
  override val isShowingHotspotError = MutableStateFlow(false)
  override val isShowingBroadcastError = MutableStateFlow(false)
  override val isShowingProxyError = MutableStateFlow(false)
}
