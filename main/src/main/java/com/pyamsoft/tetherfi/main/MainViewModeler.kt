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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.main

import androidx.annotation.CheckResult
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastEvent
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkUpdater
import com.pyamsoft.tetherfi.server.broadcast.BroadcastObserver
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.ServiceLauncher
import com.pyamsoft.tetherfi.service.prereq.HotspotRequirements
import com.pyamsoft.tetherfi.service.prereq.HotspotStartBlocker
import com.pyamsoft.tetherfi.ui.ServerPortTypes
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModeler
@Inject
internal constructor(
    override val state: MutableMainViewState,
    private val proxy: SharedProxy,
    private val enforcer: ThreadEnforcer,
    private val requirements: HotspotRequirements,
    private val networkStatus: BroadcastNetworkStatus,
    private val networkUpdater: BroadcastNetworkUpdater,
    private val broadcastObserver: BroadcastObserver,
    private val inAppRatingPreferences: InAppRatingPreferences,
    private val proxyPreferences: ProxyPreferences,
    private val expertPreferences: ExpertPreferences,
    private val serviceLauncher: ServiceLauncher,
    private val appScope: AppCoroutineScope,
) : MainViewState by state, AbstractViewModeler<MainViewState>(state) {

  private val isNetworkCurrentlyRunning =
      MutableStateFlow(networkStatus.getCurrentStatus() == RunningStatus.Running)

  private fun listenServerUpdates(scope: CoroutineScope) {
    val s = state

    // Watch group info
    networkStatus.onGroupInfoChanged().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.group.value = it } }
    }

    // Watch connection info
    networkStatus.onConnectionInfoChanged().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.connection.value = it } }
    }
  }

  private fun listenConfigUpdates(scope: CoroutineScope) {
    val s = state

    // Enabled state is its own thing, not part of group info
    proxyPreferences.listenForHttpEnabledChanges().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.isHttpEnabled.value = it } }
    }

    proxyPreferences.listenForSocksEnabledChanges().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.isSocksEnabled.value = it } }
    }

    // Port is its own thing, not part of group info
    proxyPreferences.listenForPortChanges().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.port.value = it } }
    }

    // Broadcast type
    expertPreferences.listenForBroadcastType().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.broadcastType.value = it } }
    }

    // Preferred Network
    expertPreferences.listenForPreferredNetwork().also { f ->
      scope.launch(context = Dispatchers.Default) { f.collect { s.preferredNetwork.value = it } }
    }
  }

  private fun listenServerStatus(scope: CoroutineScope) {
    proxy.onStatusChanged().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { status ->
          Timber.d { "Proxy Status Changed: $status" }
          state.proxyStatus.value = status
        }
      }
    }

    networkStatus.onStatusChanged().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { status ->
          Timber.d { "WiDi Status Changed: $status" }
          state.wiDiStatus.value = status
        }
      }
    }
  }

  private fun listenProxySetupError(scope: CoroutineScope) {
    // If either of these sets an error state, we will mark the error dialog as shown
    // Need this or we run on the main thread
    resolveErrorFlow().flowOn(context = Dispatchers.Default).also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { show ->
          enforcer.assertOffMainThread()
          state.isShowingSetupError.value = show
        }
      }
    }
  }

  private fun listenBroadcastUpdates(scope: CoroutineScope) {
    val s = state

    // Watch the server status and update if it is running
    networkStatus.onStatusChanged().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { s ->
          val wasRunning = isNetworkCurrentlyRunning.value
          val currentlyRunning = s == RunningStatus.Running
          isNetworkCurrentlyRunning.value = currentlyRunning

          // If the network was switched off, clear everything
          if (wasRunning && !currentlyRunning) {
            Timber.d { "Hotspot was turned OFF, refresh network settings to clear" }

            // Refresh connection info, should blank out
            handleRefreshConnectionInfo(this)

            // Explicitly close the QR code
            handleCloseDialog(MainViewDialogs.QR_CODE)
          } else if (!wasRunning && currentlyRunning) {
            Timber.d { "Hotspot was turned ON, refresh network settings to update" }

            // Refresh connection info, should populate
            handleRefreshConnectionInfo(this)
          }
        }
      }
    }

    // But then once we are done editing and we start getting events from the receiver,
    // take them instead
    broadcastObserver.listenNetworkEvents().also { f ->
      scope.launch(context = Dispatchers.Default) {
        f.collect { event ->
          when (event) {
            is BroadcastEvent.ConnectionChanged -> {
              s.connection.update { info -> info.update { it.copy(hostName = event.hostName) } }
            }
            is BroadcastEvent.Other -> {
              // Unhandled event
            }
            is BroadcastEvent.RequestPeers -> {
              // Unhandled event
            }
          }
        }
      }
    }
  }

  private fun listenInAppRatingPrompt(
      scope: CoroutineScope,
      onShowInAppRating: () -> Unit,
  ) {
    inAppRatingPreferences
        .listenShowInAppRating()
        .filter { it }
        .distinctUntilChanged()
        .also { f ->
          scope.launch(context = Dispatchers.Default) {
            f.collect { show ->
              if (show) {
                Timber.d { "Show in-app rating" }
                withContext(context = Dispatchers.Main) { onShowInAppRating() }
              }
            }
          }
        }
  }

  @CheckResult
  private fun resolveErrorFlow(): Flow<Boolean> =
      combineTransform(
              state.wiDiStatus,
              state.proxyStatus,
          ) { wifi, proxy ->
            enforcer.assertOffMainThread()

            emit(wifi is RunningStatus.Error || proxy is RunningStatus.Error)
          }
          // Distinct so that
          // Upon ON -> if any ERROR, fire dialog and in page
          // Once dialog dismissed, if OFF and error, don't dialog again because still TRUE
          // otherwise if OFF and no error, no dialog
          .distinctUntilChanged()

  private suspend fun startProxy() {
    val blockers = requirements.blockers()
    // If something is blocking hotspot startup we will show it in the view
    state.startBlockers.value = blockers
    if (blockers.isNotEmpty()) {
      Timber.w { "Cannot launch Proxy until blockers are dealt with: $blockers" }
      stopProxy()
      return
    }

    Timber.d { "Starting Proxy..." }
    serviceLauncher.startForeground()
  }

  private fun stopProxy() {
    Timber.d { "Stopping Proxy" }
    serviceLauncher.stopForeground()
  }

  private fun resetError() {
    Timber.d { "Resetting Proxy from Error state" }
    serviceLauncher.resetError()
  }

  private fun closeAllDialogs() {
    handleCloseDialog(MainViewDialogs.PROXY_ERROR)
    handleCloseDialog(MainViewDialogs.BROADCAST_ERROR)
    handleCloseDialog(MainViewDialogs.HOTSPOT_ERROR)
    handleCloseDialog(MainViewDialogs.SETUP_ERROR)
    handleCloseDialog(MainViewDialogs.NETWORK_ERROR)
  }

  override fun registerSaveState(
      registry: SaveableStateRegistry
  ): List<SaveableStateRegistry.Entry> =
      mutableListOf<SaveableStateRegistry.Entry>().apply {
        val s = state

        registry
            .registerProvider(KEY_IS_SHOWING_QR) { s.isShowingQRCodeDialog.value }
            .also { add(it) }

        registry
            .registerProvider(KEY_SHOW_SLOW_SPEED_HELP) { state.isShowingSlowSpeedHelp.value }
            .also { add(it) }

        registry
            .registerProvider(KEY_SHOW_HOTSPOT_ERROR) { state.isShowingHotspotError.value }
            .also { add(it) }

        registry
            .registerProvider(KEY_SHOW_NETWORK_ERROR) { state.isShowingNetworkError.value }
            .also { add(it) }

        registry
            .registerProvider(KEY_SHOW_SETUP_ERROR) { state.isShowingSetupError.value }
            .also { add(it) }
      }

  override fun consumeRestoredState(registry: SaveableStateRegistry) {
    val s = state

    registry.consumeRestored(KEY_IS_SHOWING_QR)?.cast<Boolean>()?.also {
      s.isShowingQRCodeDialog.value = it
    }

    registry.consumeRestored(KEY_SHOW_SLOW_SPEED_HELP)?.cast<Boolean>()?.also {
      state.isShowingSlowSpeedHelp.value = it
    }

    registry.consumeRestored(KEY_SHOW_NETWORK_ERROR)?.cast<Boolean>()?.also {
      state.isShowingNetworkError.value = it
    }

    registry.consumeRestored(KEY_SHOW_HOTSPOT_ERROR)?.cast<Boolean>()?.also {
      state.isShowingHotspotError.value = it
    }

    registry.consumeRestored(KEY_SHOW_SETUP_ERROR)?.cast<Boolean>()?.also {
      state.isShowingSetupError.value = it
    }
  }

  fun bind(
      scope: CoroutineScope,
      onShowInAppRating: () -> Unit,
  ) {
    listenConfigUpdates(scope = scope)
    listenServerUpdates(scope = scope)
    listenBroadcastUpdates(scope = scope)
    listenProxySetupError(scope = scope)
    listenServerStatus(scope = scope)
    listenInAppRatingPrompt(
        scope = scope,
        onShowInAppRating = onShowInAppRating,
    )
  }

  fun handleRefreshConnectionInfo(scope: CoroutineScope) {
    scope.launch(context = Dispatchers.Default) { networkUpdater.updateNetworkInfo() }
  }

  fun handleOpenDialog(dialog: MainViewDialogs) =
      when (dialog) {
        MainViewDialogs.QR_CODE -> {
          // If the hotspot is valid, we will have this from the group
          val isHotspotDataValid = state.group.value is BroadcastNetworkStatus.GroupInfo.Connected
          state.isShowingQRCodeDialog.value = isHotspotDataValid && isNetworkCurrentlyRunning.value
        }
        MainViewDialogs.SLOW_SPEED_HELP -> {
          state.isShowingSlowSpeedHelp.value = true
        }
        MainViewDialogs.SETUP_ERROR -> {
          state.isShowingSetupError.value = true
        }
        MainViewDialogs.NETWORK_ERROR -> {
          state.isShowingNetworkError.value = true
        }
        MainViewDialogs.HOTSPOT_ERROR -> {
          state.isShowingHotspotError.value = true
        }
        MainViewDialogs.BROADCAST_ERROR -> {
          state.isShowingBroadcastError.value = true
        }
        MainViewDialogs.PROXY_ERROR -> {
          state.isShowingProxyError.value = true
        }
      }

  fun handleCloseDialog(dialog: MainViewDialogs) =
      when (dialog) {
        MainViewDialogs.QR_CODE -> {
          state.isShowingQRCodeDialog.value = false
        }
        MainViewDialogs.SLOW_SPEED_HELP -> {
          state.isShowingSlowSpeedHelp.value = false
        }
        MainViewDialogs.SETUP_ERROR -> {
          state.isShowingSetupError.value = false
        }
        MainViewDialogs.NETWORK_ERROR -> {
          state.isShowingNetworkError.value = false
        }
        MainViewDialogs.HOTSPOT_ERROR -> {
          state.isShowingHotspotError.value = false
        }
        MainViewDialogs.BROADCAST_ERROR -> {
          state.isShowingBroadcastError.value = false
        }
        MainViewDialogs.PROXY_ERROR -> {
          state.isShowingProxyError.value = false
        }
      }

  fun handleAnalyticsMarkOpened() {
    inAppRatingPreferences.markAppOpened()
  }

  fun handleDismissBlocker(blocker: HotspotStartBlocker) {
    state.startBlockers.update { it - blocker }
  }

  fun handleToggleProxy() {
    // Close any open dialogs
    closeAllDialogs()

    appScope.launch(context = Dispatchers.Default) {
      val broadcastStatus = networkStatus.getCurrentStatus()
      val proxyStatus = proxy.getCurrentStatus()

      if (broadcastStatus is RunningStatus.Error || proxyStatus is RunningStatus.Error) {
        // If either is in error, reset network and restart
        resetError()
      } else {
        // Otherwise just go by Wifi direct
        when (broadcastStatus) {
          is RunningStatus.NotRunning -> {
            startProxy()
          }
          is RunningStatus.Running -> {
            stopProxy()
          }
          else -> {
            Timber.d {
              "Cannot toggle while we are in the middle of an operation: $broadcastStatus"
            }
          }
        }
      }
    }
  }

  fun handlePortChanged(port: Int) {
    state.port.value = port
    proxyPreferences.setPort(port)
  }

  fun handleEnabledChanged(enabled: Boolean, type: ServerPortTypes) =
      when (type) {
        ServerPortTypes.HTTP -> {
          state.isHttpEnabled.value = enabled
          proxyPreferences.setHttpEnabled(enabled)
        }

        ServerPortTypes.SOCKS -> {
          state.isSocksEnabled.value = enabled
          proxyPreferences.setSocksEnabled(enabled)
        }
      }

  companion object {

    private const val KEY_IS_SHOWING_QR = "show_qr"
    private const val KEY_SHOW_SLOW_SPEED_HELP = "key_show_slow_speed_help"

    private const val KEY_SHOW_SETUP_ERROR = "key_show_setup_error"
    private const val KEY_SHOW_HOTSPOT_ERROR = "key_show_hotspot_error"
    private const val KEY_SHOW_NETWORK_ERROR = "key_show_network_error"
  }
}
