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

package com.pyamsoft.tetherfi.behavior.tweaks

import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.CheckResult
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.StatusPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultScreenOnHandler
@Inject
internal constructor(
    private val enforcer: ThreadEnforcer,
    private val preferences: StatusPreferences,
    private val networkStatus: BroadcastNetworkStatus,
    private val proxy: SharedProxy,
    private val dispatchers: AppDispatchers,
) : ScreenOnHandler {

  private val mutex = Mutex()

  @CheckResult
  private fun resolveRunningFlow(): Flow<Boolean> =
      combineTransform(
              proxy.onStatusChanged(),
              networkStatus.onStatusChanged(),
              preferences.listenForKeepScreenOn(),
          ) { proxyStatus, wiDiStatus, prefEnabled ->
            enforcer.assertOffMainThread()

            // We emit TRUE only when everything is running, and as soon
            // as anything stops we emit FALSE
            val isRunning =
                wiDiStatus is RunningStatus.Running && proxyStatus is RunningStatus.Running

            // IF the pref turns off at any point, emit FALSE
            emit(isRunning && prefEnabled)
          }
          .distinctUntilChanged()

  private suspend fun markScreenOn(window: Window) {
    val isOn = mutex.withLock { isKeepScreenOnFlag(dispatchers, window) }
    if (!isOn) {
      mutex.withLock {
        Timber.d { "Marking Window as KEEP_SCREEN_ON" }
        withContext(context = dispatchers.main) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }
  }

  private suspend fun clearScreenOn(window: Window) {
    mutex.withLock {
      Timber.d { "Clearing Window KEEP_SCREEN_ON" }
      withContext(context = dispatchers.main) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }

  private suspend fun setScreenOnState(activity: ComponentActivity, enable: Boolean) {
    val window = getWindow(dispatchers, activity)
    if (window == null) {
      Timber.w { "No window! Cannot set screen on state" }
      return
    }

    if (enable) {
      markScreenOn(window)
    } else {
      clearScreenOn(window)
    }
  }

  override fun bind(activity: ComponentActivity) {
    activity.lifecycleScope.launch(context = dispatchers.default) {
      resolveRunningFlow().also { f ->
        f.collect { isEnabled ->
          setScreenOnState(
              activity = activity,
              enable = isEnabled,
          )
        }
      }
    }
  }

  companion object {

    @JvmStatic
    @CheckResult
    private fun isFlagSet(flags: Int, flag: Int): Boolean {
      return flags and flag != 0
    }

    @JvmStatic
    @CheckResult
    private suspend fun getWindow(
      dispatchers: AppDispatchers,
      activity: ComponentActivity
    ): Window? =
      withContext(context = dispatchers.main) { activity.window }

    @JvmStatic
    @CheckResult
    private suspend fun isKeepScreenOnFlag(
      dispatchers: AppDispatchers,
      window: Window
    ): Boolean {
      val attrs: WindowManager.LayoutParams? =
        withContext(context = dispatchers.main) { window.attributes }
      if (attrs == null) {
        Timber.w { "No window attrs! Cannot check screen flags" }
        return false
      }

      val flags = withContext(context = dispatchers.main) { attrs.flags }
      return isFlagSet(flags, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }
}
