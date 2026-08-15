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

import android.app.Service
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.AppCoroutineScope
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerStopBroadcaster
import com.pyamsoft.tetherfi.service.notification.NotificationLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceScope
@Inject
internal constructor(
    private val appScope: AppCoroutineScope,
    private val notificationLauncher: NotificationLauncher,
    private val stopper: ServerStopBroadcaster,
    private val runner: ServiceRunner,
    private val dispatchers: AppDispatchers,
) {

  // Use the MSF so that operations are thread safe
  private val job = MutableStateFlow<Job?>(null)

  fun cancel() = job.update { j ->
    if (j == null) {
      Timber.w { "cancel() called but no Job exists!" }
    } else {
      Timber.d { "Execute prepareStop() before cancelling service scope" }
      stopper.prepareStop {
        Timber.d { "Cancel Service Scope" }
        j.cancel()
      }
    }
    return@update null
  }

  fun start(service: Service) = job.update { j ->
    if (j != null) {
      Timber.w { "start() called but runner Job already exists!" }
      return@update j
    }

    // Start the notification immediately
    val notificationWatcher = notificationLauncher.startForeground(service)

    return@update appScope.launch(context = dispatchers.default) {
      val scope = this

      Timber.d { "Service scope start() launched!" }
      notificationWatcher.watch(scope = scope)
      runner.start(scope = scope)
    }
  }
}
