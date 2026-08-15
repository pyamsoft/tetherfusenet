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
import com.pyamsoft.pydroid.ui.debug.InAppDebugLogger.Companion.createInAppDebugLogger
import com.pyamsoft.pydroid.ui.debug.InAppDebugStatus
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.PYDroidLogger
import com.pyamsoft.pydroid.util.isDebugMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

fun Application.installLogger(
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
    inAppDebugStatus: InAppDebugStatus,
) {
  if (isDebugMode()) {
    // For debug logcat
    Timber.plant(
        object : Timber.DebugTree() {
          override fun createStackElementTag(element: StackTraceElement): String {
            return element.run { "($fileName:$lineNumber)" }
          }
        },
    )
  }

  observeInAppDebugLogger(
      scope = scope,
      dispatchers = dispatchers,
      inAppDebugStatus = inAppDebugStatus,
  )
}

private fun Application.observeInAppDebugLogger(
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
    inAppDebugStatus: InAppDebugStatus,
) {
  val self = this

  val tree =
      object : Timber.Tree() {

        private val logger by lazy { self.createInAppDebugLogger() }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
          logger.log(priority, tag, message, t)
        }
      }

  val isPlanted = MutableStateFlow(false)
  inAppDebugStatus.listenForInAppDebuggingEnabled().also { f ->
    scope.launch(context = dispatchers.default) {
      f.collect { enabled ->
        if (enabled) {
          if (isPlanted.compareAndSet(expect = false, update = true)) {
            withContext(context = dispatchers.main) { Timber.plant(tree) }
          }
        } else {
          if (isPlanted.compareAndSet(expect = true, update = false)) {
            withContext(context = dispatchers.main) { Timber.uproot(tree) }
          }
        }
      }
    }
  }
}

@CheckResult
fun createLogger(): PYDroidLogger {
  return object : PYDroidLogger {

    override fun d(tag: String, message: () -> String) {
      Timber.tag(tag).d(message())
    }

    override fun w(tag: String, message: () -> String) {
      Timber.tag(tag).w(message())
    }

    override fun e(tag: String, throwable: Throwable, message: () -> String) {
      Timber.tag(tag).e(throwable, message())
    }
  }
}
