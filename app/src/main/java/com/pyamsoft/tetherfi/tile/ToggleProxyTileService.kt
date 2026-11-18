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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.tile

import android.app.Application
import android.content.Context
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.tetherfi.ObjectGraph

internal class ToggleProxyTileService internal constructor() : AbstractProxyTileService() {

  override val proxyTileAction: ProxyTileAction = ProxyTileAction.TOGGLE

  override fun ensureObjectGraphInjected() {
    ObjectGraph.ApplicationScope.retrieve(this)
        .plusTileService()
        .create(
            service = this,
        )
        .inject(this)
  }

  override fun onUpdateTile(application: Application) {
    StartProxyTileService.updateTile(application)
  }

  companion object {

    @JvmStatic
    fun updateTile(context: Context) {
      updateTile(context, ToggleProxyTileService::class.java)
    }
  }
}
