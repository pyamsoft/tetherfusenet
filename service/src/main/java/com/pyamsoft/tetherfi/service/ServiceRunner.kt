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

package com.pyamsoft.tetherfi.service

import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.core.notification.NotificationErrorLauncher
import com.pyamsoft.tetherfi.server.broadcast.BroadcastEvent
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkUpdater
import com.pyamsoft.tetherfi.server.broadcast.BroadcastObserver
import com.pyamsoft.tetherfi.service.foreground.ForegroundLauncher
import com.pyamsoft.tetherfi.service.foreground.ForegroundWatcher
import com.pyamsoft.tetherfi.service.notification.NotificationLauncher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class ServiceRunner
@Inject
internal constructor(
    private val notificationErrorLauncher: NotificationErrorLauncher,
    private val notificationLauncher: NotificationLauncher,
    private val broadcastObserver: BroadcastObserver,
    private val foregroundWatcher: ForegroundWatcher,
    private val foregroundLauncher: ForegroundLauncher,
    private val serviceLauncher: ServiceLauncher,
    private val networkUpdater: BroadcastNetworkUpdater,
    private val dispatchers: AppDispatchers,
) {
  private val runningState = MutableStateFlow(false)

  private fun CoroutineScope.startProxy() {
    val scope = this

    // Watch the Wifi Receiver for events
    // without this block, we do not properly refresh
    // CONNECTION and GROUP info and can lead to errors
    broadcastObserver.listenNetworkEvents().also { f ->
      scope.launch(context = dispatchers.default) {
        f.collect { event ->
          when (event) {
            is BroadcastEvent.ConnectionChanged -> {
              networkUpdater.updateNetworkInfo()
            }

            is BroadcastEvent.RequestPeers -> {
              networkUpdater.updateNetworkInfo()
            }

            is BroadcastEvent.Other -> {
              networkUpdater.updateNetworkInfo()
            }
          }
        }
      }
    }

    // Prepare proxy on create
    scope.launch(context = dispatchers.default) {
      foregroundWatcher.bind(
          onRefreshNotification = {
            Timber.d { "Refresh event received, start notification again" }
            notificationLauncher.update()
          },
          onShutdownService = { e ->
            Timber.d { "Shutdown event received!" }

            // Ensure we are on the main thread
            withContext(context = dispatchers.main) { serviceLauncher.stopForeground() }

            // Show an error notification
            if (e != null) {
              notificationErrorLauncher.apply {
                // Hide the existing one
                hideError()

                // Show a new one
                showError(e)
              }
            }
          },
      )
    }

    // And Start the proxy Wifi Direct and our HTTP server
    scope.launch(context = dispatchers.default) {
      Timber.d { "Starting Proxy!" }
      foregroundLauncher.startProxy()
    }
  }

  /** Start the proxy */
  fun start(scope: CoroutineScope) {
    if (runningState.compareAndSet(expect = false, update = true)) {

      // Bail out if we are already gone
      if (!scope.isActive) {
        Timber.w { "start called but CoroutineScope was already cancelled!" }
        if (runningState.compareAndSet(expect = true, update = false)) {
          Timber.d { "Stopping runner!" }
        }
        return
      }

      scope.launch(context = dispatchers.default) {
        Timber.d { "Starting runner!" }
        try {
          startProxy()
        } finally {
          withContext(context = NonCancellable) {
            if (runningState.compareAndSet(expect = true, update = false)) {
              Timber.d { "Stopping runner!" }
            }
          }
        }
      }
    }
  }
}
