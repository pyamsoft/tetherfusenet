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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty

import androidx.annotation.CheckResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

abstract class SuspendingNettyProxy internal constructor() {

  private val started = MutableStateFlow(false)

  private val proxy by lazy { provideProxy() }

  private suspend fun startProxy() {
    var stopper: NettyServerStopper? = null
    try {
      stopper = proxy.start()
      awaitCancellation()
    } finally {
      withContext(context = NonCancellable) { stopper?.also { s -> s.stop() } }
    }
  }

  suspend fun start() {
    if (started.compareAndSet(expect = false, update = true)) {
      try {
        startProxy()
      } finally {
        withContext(context = NonCancellable) {
          started.compareAndSet(expect = true, update = false)
        }
      }
    }
  }

  @CheckResult protected abstract fun provideProxy(): NettyProxy
}
