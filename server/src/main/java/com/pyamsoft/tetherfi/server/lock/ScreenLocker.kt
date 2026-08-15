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
import com.pyamsoft.tetherfi.server.StatusPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ScreenLocker
@Inject
internal constructor(
  context: Context,
  private val statusPreferences: StatusPreferences,
  private val dispatchers: AppDispatchers,
) : AbstractLocker() {

  private val powerManager by lazy {
    context.applicationContext.getSystemService<PowerManager>().requireNotNull()
  }

  private val tag by lazy { createTag(context.applicationContext.packageName) }

  @CheckResult
  @Suppress("DEPRECATION")
  private fun resolveValidBrightWakeLockLevel(): Int? {
    if (powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)) {
      Timber.d { "KeepScreenOn: Create new wake lock with SCREEN_BRIGHT_WAKE_LOCK" }
      return PowerManager.SCREEN_BRIGHT_WAKE_LOCK
    } else {
      Timber.w { "KeepScreenOn: SCREEN_BRIGHT_WAKE_LOCK unsupported, no-op locker" }
      return null
    }
  }

  @CheckResult
  private fun createWakeLock(): PowerManager.WakeLock? {
    val wakeLockLevel = resolveValidBrightWakeLockLevel() ?: return null
    return powerManager.newWakeLock(wakeLockLevel, tag).apply {
      // We will count our own refs
      setReferenceCounted(false)
    }
  }

  override suspend fun createLock(): Locker.Lock =
    withContext(context = dispatchers.default) {
      val isKeepScreenOn = statusPreferences.listenForKeepScreenOn().first()
      if (isKeepScreenOn) {
        // Not every device supports SCREEN_ON wakelock
        val wakeLock = createWakeLock() ?: return@withContext NoopLock

        return@withContext Lock(wakeLock, dispatchers, tag)
      }

      return@withContext NoopLock
    }

  internal class Lock(
    private val wakeLock: PowerManager.WakeLock,
    dispatchers: AppDispatchers,
    lockTag: String,
  ) : AbstractLock(
    lockType = "Screen",
    dispatchers = dispatchers,
    lockTag = lockTag
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
      return "${name}:SCREEN_WAKE_LOCK"
    }
  }
}
