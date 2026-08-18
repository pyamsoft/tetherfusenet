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

package com.pyamsoft.tetherfi

import android.app.Application
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bootstrap.libraries.OssLibraries
import com.pyamsoft.pydroid.bootstrap.libraries.OssLicenses
import com.pyamsoft.pydroid.core.LintIgnoreMaxLineLength
import com.pyamsoft.pydroid.ui.ModuleProvider
import com.pyamsoft.pydroid.ui.PYDroid
import com.pyamsoft.pydroid.ui.debug.InAppDebugStatus
import com.pyamsoft.pydroid.ui.installPYDroid
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.isDebugMode
import com.pyamsoft.tetherfi.core.GITHUB_URL
import com.pyamsoft.tetherfi.core.PRIVACY_POLICY_URL
import com.pyamsoft.tetherfi.core.TERMS_CONDITIONS_URL
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class TFApp : Application() {

  @CheckResult
  private fun initPYDroid(dispatchers: AppDispatchers): ModuleProvider {
    return installPYDroid(
        PYDroid.Parameters(
            viewSourceUrl = GITHUB_URL,
            bugReportUrl = "${GITHUB_URL}/issues",
            privacyPolicyUrl = PRIVACY_POLICY_URL,
            termsConditionsUrl = TERMS_CONDITIONS_URL,
            version = BuildConfig.VERSION_CODE,
            logger = createLogger(),
            dispatchers = dispatchers,
            billing = { provideBillingModule(it) },
        ),
    )
  }

  private fun installObjectGraph(component: TFAppComponent) {
    ObjectGraph.ApplicationScope.install(this, component)
  }

  private fun installComponent(
      scope: CoroutineScope,
      moduleProvider: ModuleProvider,
      inAppDebugStatus: InAppDebugStatus,
  ) {
    val mods = moduleProvider.get()
    val component =
        DaggerTFAppComponent.factory()
            .create(
                debug = isDebugMode(),
                inAppDebug = inAppDebugStatus.listenForInAppDebuggingEnabled(),
                scope = scope,
                application = this,
                imageLoader = mods.imageLoader(),
                theming = mods.theming(),
                enforcer = mods.enforcer(),
                dispatchers = mods.dispatchers(),
            )

    installObjectGraph(component)
  }

  override fun onCreate() {
    super.onCreate()
    val dispatchers = AppDispatchers.create()
    val modules = initPYDroid(dispatchers = dispatchers)

    val scope =
        CoroutineScope(
            context = SupervisorJob() + dispatchers.default + CoroutineName(this::class.java.name),
        )

    val inAppDebugStatus = modules.get().inAppDebugStatus()
    installLogger(
        scope = scope,
        dispatchers = dispatchers,
        inAppDebugStatus = inAppDebugStatus,
    )

    installComponent(
        scope = scope,
        moduleProvider = modules,
        inAppDebugStatus = inAppDebugStatus,
    )
    addLibraries()
  }

  companion object {

    @JvmStatic
    private fun addLibraries() {
      OssLibraries.apply {
        usingNotify = true
        usingArch = true
        usingUi = true
      }

      OssLibraries.add(
          "Dagger",
          "https://github.com/google/dagger",
          "A fast dependency injector for Android and Java.",
      )

      OssLibraries.add(
          "LeakCanary",
          "https://github.com/square/leakcanary",
          "A memory leak detection library for Android.",
      )

      OssLibraries.add(
          "Timber",
          "https://github.com/JakeWharton/timber",
          "A logger with a small, extensible API which provides utility on top of Android's normal Log class.",
      )

      OssLibraries.add(
          "KSP",
          "https://github.com/google/ksp",
          "Kotlin Symbol Processing API",
      )

      @LintIgnoreMaxLineLength
      OssLibraries.add(
          "AndroidX Activity Compose",
          "https://android.googlesource.com/platform/frameworks/support/+/androidx-master-dev/activity/activity-compose",
          "Jetpack Compose bridge for AndroidX Activity",
      )

      OssLibraries.add(
          "QRCode-Kotlin",
          "https://github.com/g0dkar/qrcode-kotlin#installation",
          "QRCode Generator implemented in pure Kotlin",
          license = OssLicenses.MIT,
      )

      @LintIgnoreMaxLineLength
      OssLibraries.add(
          "Netty",
          "https://netty.io",
          "An asynchronous event-driven network application framework for rapid development of maintainable high performance protocol servers & clients.",
      )
    }
  }
}
