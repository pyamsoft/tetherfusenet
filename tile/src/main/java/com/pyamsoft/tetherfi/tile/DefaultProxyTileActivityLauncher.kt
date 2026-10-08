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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.CheckResult
import androidx.annotation.RequiresApi
import com.pyamsoft.tetherfi.core.Timber
import javax.inject.Inject
import javax.inject.Named

internal class DefaultProxyTileActivityLauncher
@Inject
internal constructor(
    @param:Named("tile_activity") private val tileActivityClass: Class<out Activity>,
    context: Context,
    service: TileService,
) : ProxyTileActivityLauncher {

  private val launchMethod by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      LaunchMethod.NewWay(tileActivityClass, context, service)
    } else {
      LaunchMethod.OldWay(tileActivityClass, context, service)
    }
  }

  override fun launchTileActivity(action: ProxyTileAction) {
    launchMethod.launchTileActivity(action)
  }

  internal interface LaunchMethod {

    /** Open the tile activity */
    fun launchTileActivity(action: ProxyTileAction)

    abstract class Base(
        private val tileActivityClass: Class<out Activity>,
        protected val context: Context,
        private val service: TileService,
    ) : LaunchMethod {

      @CheckResult
      protected fun createNewTileActivityIntent(action: ProxyTileAction): Intent {
        return Intent(context, tileActivityClass).apply {
          flags =
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
                  Intent.FLAG_ACTIVITY_CLEAR_TOP or
                  Intent.FLAG_ACTIVITY_NEW_TASK
          putExtra(ProxyTileActionKeys.KEY_ACTION, action.name)
        }
      }

      private inline fun ensureUnlocked(crossinline block: () -> Unit) {
        if (service.isLocked) {
          service.unlockAndRun {
            Timber.d { "Unlock device before running launcher" }
            block()
          }
        } else {
          Timber.d { "Device is unlocked, run launcher" }
          block()
        }
      }

      final override fun launchTileActivity(action: ProxyTileAction) {
        ensureUnlocked {
          Timber.d { "Start TileActivity: $action" }
          onLaunchTileActivity(service, action)
        }
      }

      protected abstract fun onLaunchTileActivity(
          service: TileService,
          action: ProxyTileAction,
      )
    }

    class OldWay(
        tileActivityClass: Class<out Activity>,
        context: Context,
        service: TileService,
    ) : Base(tileActivityClass, context, service) {

      @SuppressLint("StartActivityAndCollapseDeprecated")
      override fun onLaunchTileActivity(service: TileService, action: ProxyTileAction) {
        val intent = createNewTileActivityIntent(action)
        @Suppress("DEPRECATION") service.startActivityAndCollapse(intent)
      }
    }

    class NewWay(
        tileActivityClass: Class<out Activity>,
        context: Context,
        service: TileService,
    ) : Base(tileActivityClass, context, service) {

      private var requestCode = BASE_REQUEST_CODE_ACTIVITY

      @CheckResult
      private fun generateRequestCode(): Int {
        return requestCode++
      }

      @CheckResult
      private fun createNewPendingIntent(action: ProxyTileAction): PendingIntent {
        return PendingIntent.getActivity(
            context,
            generateRequestCode(),
            createNewTileActivityIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }

      @RequiresApi(value = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      override fun onLaunchTileActivity(service: TileService, action: ProxyTileAction) {
        val pendingIntent = createNewPendingIntent(action)
        service.startActivityAndCollapse(pendingIntent)
      }

      companion object {
        private const val BASE_REQUEST_CODE_ACTIVITY = 42069
      }
    }
  }
}
