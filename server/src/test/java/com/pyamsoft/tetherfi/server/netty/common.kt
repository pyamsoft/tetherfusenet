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

package com.pyamsoft.tetherfi.server.netty

import timber.log.Timber

suspend fun withLogging(
    isLoggingEnabled: Boolean = true,
    block: suspend () -> Unit,
) {
  var tree: Timber.Tree? = null
  if (isLoggingEnabled) {
    val t =
        object : Timber.Tree() {
          override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            t?.printStackTrace()
            println(message)
          }
        }
    tree = t
    Timber.plant(t)
  }

  try {
    block()
  } finally {
    tree?.also { Timber.uproot(it) }
  }
}
