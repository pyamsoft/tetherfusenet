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
import kotlinx.coroutines.CoroutineScope

interface NotificationLauncher {

  /**
   * Start the Foreground notification
   *
   * You should call this method IMMEDIATELY upon starting a service
   *
   * This API is different from the other launcher/runner style APIs because we want to call it
   * immediately instead of waiting for coroutine launches
   *
   * Once the notification is started, it returns a [Watcher] interface which can then be coroutine
   * launched in a non-time-sensitive way to subscribe and watch for notification events
   */
  @CheckResult fun startForeground(service: Service): Watcher

  suspend fun update()

  enum class Actions {
    STOP,
  }

  fun interface Watcher {

    fun watch(scope: CoroutineScope)
  }

  companion object {

    @JvmField
    val INTENT_EXTRA_SERVICE_ACTION = "${NotificationLauncher::class.java.name}::Service_Action"
  }
}
