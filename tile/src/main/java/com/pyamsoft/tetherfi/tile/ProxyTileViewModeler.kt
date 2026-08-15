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
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.ServiceLauncher
import com.pyamsoft.tetherfi.service.prereq.HotspotRequirements
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.service.tile.TileHandler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class ProxyTileViewModeler
@Inject
internal constructor(
    override val state: MutableProxyTileViewState,
    private val handler: TileHandler,
    private val serviceLauncher: ServiceLauncher,
    private val requirements: HotspotRequirements,
    private val appScope: AppCoroutineScope,
    private val dispatchers: AppDispatchers,
) : ProxyTileViewState by state, AbstractViewModeler<ProxyTileViewState>(state) {

  init {
    // Sync up the network state on init so that we can immediately capture it in the View
    state.status.value = handler.getOverallStatus()
  }

  @CheckResult
  private suspend fun handleProxyAction(action: ProxyTileAction): Boolean {
    when (val status = handler.getOverallStatus()) {
      is RunningStatus.NotRunning -> {
        // As long as the action is allowed to start [TOGGLE, START]
        if (action != ProxyTileAction.STOP) {
          startProxy()
          return true
        }
      }

      is RunningStatus.Running -> {
        // As long as the action is allowed to stop [TOGGLE, STOP]
        if (action != ProxyTileAction.START) {
          stopProxy()
          return true
        }
      }

      else -> {
        Timber.d { "Cannot change Proxy while we are in the middle of an operation: $status" }
      }
    }

    return false
  }

  private suspend fun startProxy() {
    val blockers = requirements.blockers()

    // If something is blocking hotspot startup we will show it in the view
    if (blockers.isNotEmpty()) {
      Timber.w { "Cannot launch Proxy until blockers are dealt with: $blockers" }
      stopProxy()

      if (blockers.contains(HotspotStartBlocker.PERMISSION)) {
        Timber.w { "Cannot launch Proxy until Permissions are granted" }
        state.status.value =
            RunningStatus.HotspotError(
                IllegalStateException("Missing required permission, cannot start Hotspot"),
            )
      }

      if (blockers.contains(HotspotStartBlocker.VPN)) {
        Timber.w { "Cannot launch Proxy until VPN is off" }
        state.status.value =
            RunningStatus.HotspotError(
                IllegalStateException("Cannot start Hotspot while VPN is connected"),
            )
      }

      return
    }

    Timber.d { "Starting Proxy..." }
    serviceLauncher.startForeground()
  }

  private fun stopProxy() {
    Timber.d { "Stopping Proxy" }
    serviceLauncher.stopForeground()
  }

  fun handleDismissed() {
    state.isShowing.value = false
  }

  fun bind(scope: CoroutineScope) {
    val s = state

    handler.bind(
        scope = scope,
        onNetworkError = { err -> s.status.value = err },
        onNetworkStarting = { s.status.value = RunningStatus.Starting },
        onNetworkStopping = { s.status.value = RunningStatus.Stopping },
        onNetworkNotRunning = { s.status.value = RunningStatus.NotRunning },
        onNetworkRunning = { s.status.value = RunningStatus.Running },
    )
  }

  fun handleToggleProxy(onNoAction: suspend () -> Unit) {
    appScope.launch(context = dispatchers.default) {
      val acted = handleProxyAction(action = ProxyTileAction.TOGGLE)
      if (!acted) {
        onNoAction()
      }
    }
  }

  fun handleStartProxy(onNoAction: suspend () -> Unit) {
    appScope.launch(context = dispatchers.default) {
      val acted = handleProxyAction(action = ProxyTileAction.START)
      if (!acted) {
        onNoAction()
      }
    }
  }

  fun handleStopProxy(onNoAction: suspend () -> Unit) {
    appScope.launch(context = dispatchers.default) {
      val acted = handleProxyAction(action = ProxyTileAction.STOP)
      if (!acted) {
        onNoAction()
      }
    }
  }
}
