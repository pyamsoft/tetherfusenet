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

package com.pyamsoft.tetherfi.server.prereq.location

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.TweakPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

private class FakeTweakPreferences(
    private val ignoreLocation: Boolean,
) : TweakPreferences {
  override fun listenForStartIgnoreVpn(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreVpn(ignore: Boolean) = Unit

  override fun listenForStartIgnoreLocation(): Flow<Boolean> = MutableStateFlow(ignoreLocation)

  override fun setStartIgnoreLocation(ignore: Boolean) = Unit

  override fun listenForShutdownWithNoClients(): Flow<Boolean> = MutableStateFlow(false)

  override fun setShutdownWithNoClients(shutdown: Boolean) = Unit

  override fun listenForWakeLock(): Flow<Boolean> = MutableStateFlow(false)

  override fun setWakeLock(wakelock: Boolean) = Unit
}

private class ThrowingLocationContext(base: Context) : ContextWrapper(base) {
  override fun getSystemService(name: String): Any? {
    check(name != LOCATION_SERVICE) {
      "LocationManager should not be accessed when the location check is ignored"
    }
    return super.getSystemService(name)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    // Need this here since Robolectric does not yet support API 37 (which is default otherwise)
    minSdk = Build.VERSION_CODES.O,
    maxSdk = Build.VERSION_CODES.BAKLAVA,
)
class AndroidLocationCheckerTest {

  @Test
  fun `location check override bypasses actual location manager`() = runTest {
    val context = ThrowingLocationContext(RuntimeEnvironment.getApplication())
    val checker =
        AndroidLocationChecker(
            context = context,
            preferences = FakeTweakPreferences(ignoreLocation = true),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    assertTrue(checker.isLocationOn())
  }

  @Test
  fun `location check`() = runTest {
    val checker =
        AndroidLocationChecker(
            context = RuntimeEnvironment.getApplication(),
            preferences = FakeTweakPreferences(ignoreLocation = false),
          // TODO(Peter): Do we need test dispatchers?
          dispatchers = AppDispatchers.create(),
        )

    assertTrue(checker.isLocationOn())
  }
}
