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

package com.pyamsoft.tetherfi.service.tile

import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TileHandler
@Inject
@VisibleForTesting
constructor(
    private val enforcer: ThreadEnforcer,
    private val networkStatus: BroadcastNetworkStatus,
    private val proxy: SharedProxy,
    private val dispatchers: AppDispatchers,
) {

  private fun CoroutineScope.watchStatusUpdates(
      onNetworkError: (RunningStatus.Error) -> Unit,
      onNetworkNotRunning: () -> Unit,
      onNetworkStarting: () -> Unit,
      onNetworkRunning: () -> Unit,
      onNetworkStopping: () -> Unit,
  ) {
    launch(context = dispatchers.default) {
      enforcer.assertOffMainThread()

      proxy.onStatusChanged().also { f ->
        launch(context = dispatchers.default) {
          enforcer.assertOffMainThread()

          f.collect { status ->
            when (status) {
              is RunningStatus.Error -> {
                Timber.e(status.throwable) { "Error running Proxy" }
                onNetworkError(status)
              }
              else -> Timber.d { "Unhandled Proxy status event $status" }
            }
          }
        }
      }

      networkStatus.onStatusChanged().also { f ->
        launch(context = dispatchers.default) {
          enforcer.assertOffMainThread()

          f.collect { status ->
            when (status) {
              is RunningStatus.Error -> {
                Timber.e(status.throwable) { "Error running WiDi network" }
                onNetworkError(status)
              }
              is RunningStatus.NotRunning -> onNetworkNotRunning()
              is RunningStatus.Running -> onNetworkRunning()
              is RunningStatus.Starting -> onNetworkStarting()
              is RunningStatus.Stopping -> onNetworkStopping()
            }
          }
        }
      }
    }
  }

  @CheckResult
  fun getOverallStatus(): RunningStatus {
    val broadcastStatus = networkStatus.getCurrentStatus()
    val proxyStatus = proxy.getCurrentStatus()

    // If either piece has a specific error state, return it
    if (broadcastStatus is RunningStatus.Error) {
      return broadcastStatus
    }
    if (proxyStatus is RunningStatus.Error) {
      return proxyStatus
    }

    // Otherwise we only care about Wifi direct
    return broadcastStatus
  }

  fun bind(
      scope: CoroutineScope,
      onNetworkError: (RunningStatus.Error) -> Unit,
      onNetworkNotRunning: () -> Unit,
      onNetworkStarting: () -> Unit,
      onNetworkRunning: () -> Unit,
      onNetworkStopping: () -> Unit,
  ) =
      scope.watchStatusUpdates(
          onNetworkError = onNetworkError,
          onNetworkNotRunning = onNetworkNotRunning,
          onNetworkStarting = onNetworkStarting,
          onNetworkRunning = onNetworkRunning,
          onNetworkStopping = onNetworkStopping,
      )
}
