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

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.annotation.CheckResult
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CPULocker @Inject internal constructor(
  context: Context,
  private val dispatchers: AppDispatchers,
) : AbstractLocker() {

  private val powerManager by lazy {
    context.applicationContext.getSystemService<PowerManager>().requireNotNull()
  }

  private val tag by lazy { createTag(context.applicationContext.packageName) }

  @CheckResult
  private fun createWakeLock(): PowerManager.WakeLock {
    Timber.d { "Create new wake lock with PARTIAL_WAKE_LOCK" }
    val wakeLockLevel = PowerManager.PARTIAL_WAKE_LOCK

    return powerManager.newWakeLock(wakeLockLevel, tag).apply {
      // We will count our own refs
      setReferenceCounted(false)
    }
  }

  override suspend fun createLock(): Locker.Lock =
    withContext(context = dispatchers.default) {
      val wakeLock = createWakeLock()
      return@withContext Lock(wakeLock, dispatchers, tag)
    }

  internal class Lock(
    private val wakeLock: PowerManager.WakeLock,
    dispatchers: AppDispatchers,
    lockTag: String,
  ) :
    AbstractLock(
      dispatchers = dispatchers,
      lockType = "CPU", lockTag = lockTag
    ) {

    override suspend fun isHeld(): Boolean {
      return wakeLock.isHeld
    }

    @SuppressLint("WakelockTimeout")
    override suspend fun onAcquireLock() {
      wakeLock.acquire()
    }

    override suspend fun onReleaseLock() {
      wakeLock.release()
    }
  }

  companion object {

    @JvmStatic
    @CheckResult
    private fun createTag(name: String): String {
      return "${name}:CPU_WAKE_LOCK"
    }
  }
}
