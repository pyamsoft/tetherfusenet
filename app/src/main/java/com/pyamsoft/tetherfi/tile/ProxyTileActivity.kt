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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.CheckResult
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.R
import com.pyamsoft.tetherfi.TFTheme
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.getSystemDarkMode
import com.pyamsoft.tetherfi.main.SystemBars
import com.pyamsoft.tetherfi.main.ThemeViewModeler
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import javax.inject.Inject

class ProxyTileActivity : ComponentActivity() {

  @Inject
  @JvmField
  internal var dispatchers: AppDispatchers? = null
  @Inject @JvmField internal var viewModel: ThemeViewModeler? = null

  @CheckResult
  private fun getTileAction(): ProxyTileAction {
    val rawValue = intent.getStringExtra(ProxyTileActionKeys.KEY_ACTION)
    if (rawValue != null) {
      try {
        return ProxyTileAction.valueOf(rawValue)
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "Error parsing ProxyTileActivity.Action param: $rawValue" }
      }
    }

    // No value means TOGGLE
    return ProxyTileAction.TOGGLE
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_TFApp_Tile)
    super.onCreate(savedInstanceState)

    ObjectGraph.ApplicationScope.retrieve(this).plusTile().create().inject(this)

    val vm = viewModel.requireNotNull()
    val dis = dispatchers.requireNotNull()
    val appName = getString(R.string.app_name)

    val tileAction = getTileAction()

    setContent {
      val theme by vm.mode.collectAsStateWithLifecycle()
      val isMaterialYou by vm.isMaterialYou.collectAsStateWithLifecycle()

      SaveStateDisposableEffect(vm)

      TFTheme(
        dispatchers = dis,
          theme = theme,
          isMaterialYou = isMaterialYou,
      ) {
        SystemBars(
            isDarkMode = theme.getSystemDarkMode(),
        )
        ProxyTileEntry(
            modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
            appName = appName,
            tileAction = tileAction,
            onComplete = { finishAndRemoveTask() },
            onUpdateTile = { ProxyTileService.updateTile(this) },
        )
      }
    }

    vm.init(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    viewModel = null
    dispatchers = null
  }
}
