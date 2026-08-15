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

package com.pyamsoft.tetherfi.service.notification

import android.app.Service
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.notify.Notifier
import com.pyamsoft.pydroid.notify.NotifyChannelInfo
import com.pyamsoft.pydroid.notify.toNotifyId
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.status.RunningStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
internal class NotificationLauncherImpl
@Inject
internal constructor(
    private val notifier: Notifier,
    private val enforcer: ThreadEnforcer,
    private val broadcastStatus: BroadcastNetworkStatus,
    private val allowedClients: AllowedClients,
    private val blockedClients: BlockedClients,
    private val dispatchers: AppDispatchers,
) : NotificationLauncher {

  private val showing = MutableStateFlow(false)

  private val clientCount = MutableStateFlow(0)
  private val blockCount = MutableStateFlow(0)

  private val runningBroadcastStatus = MutableStateFlow<RunningStatus>(RunningStatus.NotRunning)

  private val runningProxyGroup =
      MutableStateFlow<BroadcastNetworkStatus.GroupInfo>(BroadcastNetworkStatus.GroupInfo.Unchanged)
  private val runningProxyConnection =
      MutableStateFlow<BroadcastNetworkStatus.ConnectionInfo>(
          BroadcastNetworkStatus.ConnectionInfo.Unchanged
      )

  @CheckResult
  private fun <T> MutableStateFlow<T>.compareCurrent(update: T): Boolean {
    val current = this.value
    if (current != update) {
      this.value = update
      return true
    }

    return false
  }

  private suspend fun updateNotification() {
    if (!showing.value) {
      Timber.w { "Do not update notification, no longer showing!" }
      return
    }

    withContext(context = dispatchers.main) {
      val data =
          ServerNotificationData(
              broadcastStatus = runningBroadcastStatus.value,
              connection = runningProxyConnection.value,
              group = runningProxyGroup.value,
              clientCount = clientCount.value,
              blockCount = blockCount.value,
          )

      notifier
          .show(id = LONG_RUNNING_ID, channelInfo = LONG_RUNNING_CHANNEL_INFO, notification = data)
          .also { Timber.d { "Updated foreground notification: $it: $data" } }
    }
  }

  private fun CoroutineScope.watchBroadcastStatus() {
    val scope = this

    // Supervisor job will cancel all children
    scope.launch {
      enforcer.assertOffMainThread()

      // Listen for notification updates
      broadcastStatus.onStatusChanged().also { f ->
        launch {
          enforcer.assertOffMainThread()

          f.collect { s ->
            if (runningBroadcastStatus.compareCurrent(s)) {
              updateNotification()
            }
          }
        }
      }
      broadcastStatus.onGroupInfoChanged().also { f ->
        launch {
          enforcer.assertOffMainThread()

          f.collect { g ->
            if (runningProxyGroup.compareCurrent(g)) {
              updateNotification()
            }
          }
        }
      }
      broadcastStatus.onConnectionInfoChanged().also { f ->
        launch {
          enforcer.assertOffMainThread()

          f.collect { c ->
            if (runningProxyConnection.compareCurrent(c)) {
              updateNotification()
            }
          }
        }
      }
    }
  }

  private fun CoroutineScope.watchNotification() {
    val scope = this

    scope.watchBroadcastStatus()

    scope.launch {
      // Listen for client updates
      allowedClients
          .listenForClients()
          .map { it.size }
          .also { f ->
            launch {
              enforcer.assertOffMainThread()

              f.collect { c ->
                if (clientCount.compareCurrent(c)) {
                  updateNotification()
                }
              }
            }
          }

      // Listen for block updates
      blockedClients
          .listenForBlocked()
          .map { it.size }
          .also { f ->
            launch {
              enforcer.assertOffMainThread()

              f.collect { b ->
                if (blockCount.compareCurrent(b)) {
                  updateNotification()
                }
              }
            }
          }
    }
  }

  private fun reset() {
    clientCount.value = 0
    blockCount.value = 0

    runningBroadcastStatus.value = RunningStatus.NotRunning
    runningProxyGroup.value = BroadcastNetworkStatus.GroupInfo.Unchanged
    runningProxyConnection.value = BroadcastNetworkStatus.ConnectionInfo.Unchanged
  }

  private fun stop(service: Service) {
    enforcer.assertOnMainThread()

    Timber.d { "Stop foreground notification" }
    notifier.stopForeground(service, LONG_RUNNING_ID)

    reset()
  }

  private fun start(service: Service) {
    // Hold this here until the coroutine is cancelled
    reset()

    // Initialize with blank data first
    val data = LONG_RUNNING_DEFAULT_DATA
    notifier
        .startForeground(
            service = service,
            id = LONG_RUNNING_ID,
            channelInfo = LONG_RUNNING_CHANNEL_INFO,
            notification = data,
        )
        .also { Timber.d { "Started foreground notification: $it: $data" } }
  }

  override suspend fun update() =
      withContext(context = dispatchers.default) {
        if (!showing.value) {
          Timber.w { "Cannot update notification since not showing" }
          return@withContext
        }

        updateNotification()
      }

  override fun startForeground(service: Service): NotificationLauncher.Watcher {
    if (showing.compareAndSet(expect = false, update = true)) {
      start(service)

      return NotificationLauncher.Watcher { scope: CoroutineScope ->
        scope.launch(context = dispatchers.default) {
          try {
            Timber.d { "Launch notification watcher" }
            // Then immediately open a channel to update
            watchNotification()

            // And suspend until we are done
            Timber.d { "Await notification cancellation..." }
            awaitCancellation()
          } finally {
            withContext(context = NonCancellable) {
              Timber.d { "Notification scope is done, cancel notification!" }
              if (showing.compareAndSet(expect = true, update = false)) {
                withContext(context = dispatchers.main) { stop(service) }
              }
            }
          }
        }
      }
    } else {
      Timber.w { "Notification is already started, return EMPTY_WATCHER" }
      return EMPTY_WATCHER
    }
  }

  companion object {

    private val LONG_RUNNING_ID = 42069.toNotifyId()
    private val LONG_RUNNING_DEFAULT_DATA =
        ServerNotificationData(
            group = BroadcastNetworkStatus.GroupInfo.Unchanged,
            connection = BroadcastNetworkStatus.ConnectionInfo.Unchanged,
            broadcastStatus = RunningStatus.NotRunning,
            clientCount = 0,
            blockCount = 0,
        )
    private val LONG_RUNNING_CHANNEL_INFO =
        NotifyChannelInfo(
            id = "channel_tetherfi_service_1",
            title = "TetherFuseNet Proxy",
            description = "TetherFuseNet Proxy Service",
        )

    private val EMPTY_WATCHER = NotificationLauncher.Watcher {}
  }
}
