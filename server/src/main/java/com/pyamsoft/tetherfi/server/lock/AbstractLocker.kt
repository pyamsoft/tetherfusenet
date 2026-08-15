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

package com.pyamsoft.tetherfi.server.lock

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import kotlin.math.max
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal abstract class AbstractLocker protected constructor() : Locker {

  internal abstract class AbstractLock
  protected constructor(
      protected val dispatchers: AppDispatchers,
      private val lockType: String,
      private val lockTag: String,
  ) : Locker.Lock {

    private val mutex = Mutex()
    private val alive = MutableStateFlow(true)
    private val refCount = MutableStateFlow(0)

    private suspend fun withMutexAcquireLock() {
      if (!isHeld()) {
        Timber.d { "####################################" }
        Timber.d { "Acquire wakelock ($lockType): $lockTag" }
        Timber.d { "####################################" }
        try {
          onAcquireLock()
        } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
          Timber.e(e) { "Unable to acquire wakelock (${lockType}) $lockTag" }
        }
      }

      val updated = refCount.updateAndGet { it + 1 }
      Timber.d { "Wakelock acquire ref ($lockType): $updated" }
    }

    private suspend fun withMutexReleaseLock() {
      if (isHeld()) {
        Timber.d { "####################################" }
        Timber.d { "Release wakelock ($lockType): $lockTag" }
        Timber.d { "####################################" }
        try {
          onReleaseLock()
        } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
          Timber.e(e) { "Unable to release wakelock (${lockType}) $lockTag" }
        }
      }
    }

    private suspend fun acquireLock() =
        withContext(context = dispatchers.default) {
          mutex.withLock {
            if (alive.value) {
              withMutexAcquireLock()
            } else {
              Timber.w { "Unable to acquire dead wakelock ($lockType) $lockTag" }
            }
          }
        }

    private suspend fun releaseLock(destroy: Boolean) =
        withContext(context = dispatchers.default) {
          mutex.withLock {
            val updated = refCount.updateAndGet { max(it - 1, 0) }

            Timber.d { "Wakelock release ref ($lockType): $updated" }

            if (destroy || updated <= 0) {
              // Destroy the wakelock and prevent it from acquiring again
              val isAlive = if (destroy) alive.getAndUpdate { false } else alive.value

              if (isAlive) {
                withMutexReleaseLock()
              } else {
                Timber.w { "Destroy called on already dead wakelock ($lockType) $lockTag" }
              }
            }
          }
        }

    final override suspend fun acquire(): Locker.Lock.Releaser =
        withContext(context = dispatchers.default) {
          acquireLock()

          return@withContext Locker.Lock.Releaser {
            withContext(context = dispatchers.default + NonCancellable) {
              releaseLock(destroy = false)
            }
          }
        }

    final override suspend fun release() =
        withContext(context = dispatchers.default + NonCancellable) { releaseLock(destroy = true) }

    @CheckResult protected abstract suspend fun isHeld(): Boolean

    protected abstract suspend fun onAcquireLock()

    protected abstract suspend fun onReleaseLock()
  }
}
