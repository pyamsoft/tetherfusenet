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

package com.pyamsoft.tetherfi.server.prereq.vpn

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import com.pyamsoft.tetherfi.server.TweakPreferences
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class FakeTweakPreferences(
    private val ignoreVpn: Boolean,
) : TweakPreferences {
  override fun listenForStartIgnoreVpn(): Flow<Boolean> = MutableStateFlow(ignoreVpn)

  override fun setStartIgnoreVpn(ignore: Boolean) = Unit

  override fun listenForStartIgnoreLocation(): Flow<Boolean> = MutableStateFlow(false)

  override fun setStartIgnoreLocation(ignore: Boolean) = Unit

  override fun listenForShutdownWithNoClients(): Flow<Boolean> = MutableStateFlow(false)

  override fun setShutdownWithNoClients(shutdown: Boolean) = Unit

  override fun listenForWakeLock(): Flow<Boolean> = MutableStateFlow(false)

  override fun setWakeLock(wakelock: Boolean) = Unit
}

// Proves the ignore-preference short-circuit never touches ConnectivityManager: any attempt to
// fetch it fails the test outright rather than silently succeeding via Robolectric's shadow.
private class ThrowingConnectivityContext(base: Context) : ContextWrapper(base) {
  override fun getSystemService(name: String): Any? {
    check(name != CONNECTIVITY_SERVICE) {
      "ConnectivityManager should not be accessed when the VPN check is ignored"
    }
    return super.getSystemService(name)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AndroidVpnCheckerTest {

  @Test
  fun `vpn override bypasses actual vpn manager`() = runTest {
    val context = ThrowingConnectivityContext(RuntimeEnvironment.getApplication())
    val checker =
        AndroidVpnChecker(context = context, preferences = FakeTweakPreferences(ignoreVpn = true))

    assertFalse(checker.isUsingVpn())
  }

  @Test
  fun `vpn off`() = runTest {
    val checker =
        AndroidVpnChecker(
            context = RuntimeEnvironment.getApplication(),
            preferences = FakeTweakPreferences(ignoreVpn = false),
        )

    // By default robolectric is not vpn connected
    assertFalse(checker.isUsingVpn())
  }

  //  // TODO(Peter): How to connect to VPN
  //  @Test
  //  fun `vpn on`() = runTest {
  //    val checker =
  //      AndroidVpnChecker(
  //        context = RuntimeEnvironment.getApplication(),
  //        preferences = FakeTweakPreferences(ignoreVpn = false),
  //      )
  //  }
}
