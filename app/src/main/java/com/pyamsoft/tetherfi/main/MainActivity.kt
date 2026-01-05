/*
 * Copyright 2025 pyamsoft
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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.arch.SaveStateDisposableEffect
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.ui.app.PYDroidActivityDelegate
import com.pyamsoft.pydroid.ui.app.installPYDroid
import com.pyamsoft.pydroid.ui.changelog.ChangeLogProvider
import com.pyamsoft.pydroid.ui.changelog.buildChangeLog
import com.pyamsoft.pydroid.ui.util.fillUpToPortraitSize
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.R
import com.pyamsoft.tetherfi.TFTheme
import com.pyamsoft.tetherfi.behavior.tweaks.ScreenOnHandler
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.core.notification.NotificationErrorLauncher
import com.pyamsoft.tetherfi.getSystemDarkMode
import com.pyamsoft.tetherfi.service.ServiceLauncher
import com.pyamsoft.tetherfi.tile.ProxyTileService
import com.pyamsoft.tetherfi.ui.InstallPYDroidExtras
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  @Inject @JvmField internal var themeViewModeler: ThemeViewModeler? = null
  @Inject @JvmField internal var serviceLauncher: ServiceLauncher? = null
  @Inject @JvmField internal var screenOnHandler: ScreenOnHandler? = null
  @Inject @JvmField internal var mainViewModel: MainViewModeler? = null
  @Inject @JvmField internal var notificationErrorLauncher: NotificationErrorLauncher? = null

  private var pydroid: PYDroidActivityDelegate? = null

  private fun initializePYDroid() {
    pydroid =
        installPYDroid(
            provider =
                object : ChangeLogProvider {

                  override val applicationIcon = R.mipmap.ic_launcher

                  override val changelog = buildChangeLog {
                    //                    feature("Add unique Activities to turn the hotspot On or
                    // Off.")
                    change("Rename the application to TetherFuseNet")
                  }
                },
        )
  }

  private fun handleShowInAppRating() {
    pydroid?.loadInAppRating()
  }

  private fun setupActivity() {
    // Setup PYDroid first
    initializePYDroid()

    // Create and initialize the ObjectGraph
    val component = ObjectGraph.ApplicationScope.retrieve(this).plusMain().create()
    component.inject(this)
    ObjectGraph.ActivityScope.install(this, component)

    // Then register for any permissions
    PermissionManager.createAndRegister(this, component)

    // Watch the hotspot status and keep the screen on if we are allowed
    screenOnHandler.requireNotNull().bind(this)
  }

  private fun safeOpenSettingsIntent(action: String) {
    // Try specific first, may fail on some devices
    try {
      val intent = Intent(action, "package:${packageName}".toUri())
      startActivity(intent)
    } catch (e: Throwable) {
      Timber.e(e) { "Failed specific intent for $action" }
      val intent = Intent(action)
      startActivity(intent)
    }
  }

  private fun handleOpenedWithIntent(intent: Intent) {
    if (intent.action === Intent.ACTION_APPLICATION_PREFERENCES) {
      mainViewModel.requireNotNull().handleOpenDialog(MainViewDialogs.SETTINGS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupActivity()

    val vm = themeViewModeler.requireNotNull()
    val appName = getString(R.string.app_name)

    setContent {
      val theme by vm.mode.collectAsStateWithLifecycle()
      val isMaterialYou by vm.isMaterialYou.collectAsStateWithLifecycle()

      SaveStateDisposableEffect(vm)

      TFTheme(
          theme = theme,
          isMaterialYou = isMaterialYou,
      ) {
        SystemBars(
            isDarkMode = theme.getSystemDarkMode(),
        )
        InstallPYDroidExtras(
            modifier = Modifier.fillUpToPortraitSize().widthIn(max = LANDSCAPE_MAX_WIDTH),
            appName = appName,
        )
        MainEntry(
            modifier = Modifier.fillMaxSize(),
            appName = appName,
            onShowInAppRating = { handleShowInAppRating() },
            onUpdateTile = { ProxyTileService.updateTile(this) },
            onLaunchIntent = { safeOpenSettingsIntent(it) },
        )
      }
    }

    vm.init(this)
    handleOpenedWithIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleOpenedWithIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    reportFullyDrawn()

    // Cancel any old notifications
    notificationErrorLauncher?.also { l ->
      lifecycleScope.launch(context = Dispatchers.Default) { l.hideError() }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    pydroid = null
    themeViewModeler = null
    serviceLauncher = null
    screenOnHandler = null
    mainViewModel = null
    notificationErrorLauncher = null
  }
}
