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

package com.pyamsoft.tetherfi.server.battery

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class BatteryOptimizerImpl
@Inject
internal constructor(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : BatteryOptimizer {

  private val powerManager by lazy { context.getSystemService<PowerManager>().requireNotNull() }

  override suspend fun isOptimizationsIgnored(): Boolean =
      // Don't use proxyDispatcher since this is used from UI
    withContext(context = dispatchers.default) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
      }
}
