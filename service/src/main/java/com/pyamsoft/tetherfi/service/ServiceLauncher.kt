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
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.broadcast.BroadcastStatus
import com.pyamsoft.tetherfi.server.proxy.ProxyStatus
import com.pyamsoft.tetherfi.server.status.RunningStatus
import org.jetbrains.annotations.VisibleForTesting
import javax.inject.Inject

class ServiceLauncher
@Inject
@VisibleForTesting
constructor(
    private val context: Context,
    private val foregroundServiceClass: Class<out Service>,
    private val wiDiStatus: BroadcastStatus,
    private val proxyStatus: ProxyStatus,
) {

  private val foregroundService by lazy { Intent(context, foregroundServiceClass) }

  /** Start the service */
  fun startForeground() {
    Timber.d { "Start Foreground Service!" }
    ContextCompat.startForegroundService(context, foregroundService)
  }

  /** Stop the service */
  fun stopForeground() {
    Timber.d { "Stop Foreground Service!" }
    context.stopService(foregroundService)
  }

  /** If the hotspot is in error state, we reset it so that it can start again */
  fun resetError() {
    stopForeground()

    // Reset status after shutdown
    Timber.d { "Resetting network Status" }
    wiDiStatus.set(RunningStatus.NotRunning, clearError = true)
    proxyStatus.set(RunningStatus.NotRunning, clearError = true)
  }
}
