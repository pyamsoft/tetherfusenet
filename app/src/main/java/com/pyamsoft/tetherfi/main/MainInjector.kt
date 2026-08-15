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

import androidx.activity.ComponentActivity
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.pydroid.ui.inject.ComposableInjector
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.core.ExperimentalRuntimeFlags
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import javax.inject.Inject

internal class MainInjector @Inject internal constructor() : ComposableInjector() {

  @JvmField @Inject internal var viewModel: MainViewModeler? = null
  @JvmField @Inject internal var permissionRequestBus: EventBus<PermissionRequests>? = null
  @JvmField @Inject internal var permissionResponseBus: EventConsumer<PermissionResponse>? = null
  @JvmField @Inject internal var experimentalRuntimeFlags: ExperimentalRuntimeFlags? = null

  @JvmField
  @Inject
  internal var dispatchers: AppDispatchers? = null

  override fun onInject(activity: ComponentActivity) {
    ObjectGraph.ActivityScope.retrieve(activity).inject(this)
  }

  override fun onDispose() {
    viewModel = null
    permissionRequestBus = null
    permissionResponseBus = null
    experimentalRuntimeFlags = null
    dispatchers = null
  }
}
