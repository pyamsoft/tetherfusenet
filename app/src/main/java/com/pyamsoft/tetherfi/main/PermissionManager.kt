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

package com.pyamsoft.tetherfi.main

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.PermissionRequester
import com.pyamsoft.pydroid.util.doOnCreate
import com.pyamsoft.pydroid.util.doOnDestroy
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuard
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class PermissionManager private constructor() {

  @Inject
  @JvmField
  internal var dispatchers: AppDispatchers? = null

  @Inject
  @JvmField
  internal var permissionRequestBus: EventConsumer<PermissionRequests>? = null

  @Inject
  @JvmField
  internal var permissionResponseBus: EventBus<PermissionResponse>? = null

  @Inject
  @JvmField
  internal var permissionGuard: PermissionGuard? = null

  private fun handleServerPermissionsGranted(
    dispatchers: AppDispatchers,
    activity: ComponentActivity
  ) {
    activity.lifecycleScope.launch(context = dispatchers.default) {
      Timber.d { "Toggle Proxy service!" }
      permissionResponseBus.requireNotNull().emit(PermissionResponse.ToggleProxy)
    }
  }

  private fun handleNotificationPermissionGranted(
    dispatchers: AppDispatchers,
    activity: ComponentActivity
  ) {
    activity.lifecycleScope.launch(context = dispatchers.default) {
      Timber.d { "Notification permission granted!" }
      permissionResponseBus.requireNotNull().emit(PermissionResponse.RefreshNotification)
    }
  }

  private fun registerPermissionListeners(
    dispatchers: AppDispatchers,
    activity: ComponentActivity,
    serverPermissionRequester: PermissionRequester.Launcher,
    notificationPermissionRequester: PermissionRequester.Launcher,
  ) {
    permissionRequestBus.requireNotNull().also { f ->
      activity.lifecycleScope.launch(context = dispatchers.default) {
        f.collect { req ->
          when (req) {
            is PermissionRequests.Notification -> {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionRequester.launch(
                  android.Manifest.permission.POST_NOTIFICATIONS
                )
              } else {
                Timber.w { "Notification permission not needed old API: ${Build.VERSION.SDK_INT}" }
              }
            }

            is PermissionRequests.Server -> {
              serverPermissionRequester.launch(permissionGuard.requireNotNull().requiredPermissions)
            }
          }
        }
      }
    }
  }

  private fun handleDestroy() {
    permissionGuard = null
    permissionRequestBus = null
    permissionResponseBus = null
    dispatchers = null
  }

  fun create(
    activity: ComponentActivity,
    component: MainComponent,
  ) {
    component.inject(this)

    val dis = dispatchers.requireNotNull()
    val serverPermissionRequester =
      PermissionRequester.createAndRegister(activity) {
        if (it) {
          handleServerPermissionsGranted(dis, activity)
        }
      }

    val notificationPermissionRequester =
      PermissionRequester.createAndRegister(activity) {
        if (it) {
          handleNotificationPermissionGranted(dis, activity)
        }
      }

    activity.doOnCreate {
      registerPermissionListeners(
        dispatchers = dis,
        activity = activity,
        serverPermissionRequester = serverPermissionRequester,
        notificationPermissionRequester = notificationPermissionRequester,
      )
    }

    activity.doOnDestroy { handleDestroy() }
  }

  companion object {

    @JvmStatic
    internal fun createAndRegister(activity: ComponentActivity, component: MainComponent) {
      PermissionManager()
        .create(
          activity = activity,
          component = component,
        )
    }
  }
}
