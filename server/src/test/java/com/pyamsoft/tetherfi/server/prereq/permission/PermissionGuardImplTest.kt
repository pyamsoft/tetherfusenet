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

package com.pyamsoft.tetherfi.server.prereq.permission

import android.os.Build
import com.pyamsoft.pydroid.core.createThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private fun newGuard(
    dispatchers: AppDispatchers,
): PermissionGuardImpl =
    PermissionGuardImpl(
        context = RuntimeEnvironment.getApplication(),
        enforcer = createThreadEnforcer(debug = false),
        dispatchers = dispatchers,
    )

@RunWith(RobolectricTestRunner::class)
// No test wide config since each test goes against a different API level
class PermissionGuardImplTest {

  @Test
  @Config(sdk = [Build.VERSION_CODES.S_V2])
  fun `below S(2) requires legacy location permissions and no local network permission`() {
    val guard =
        newGuard(
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    assertEquals(
        listOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ),
        guard.requiredPermissions,
    )
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun `T requires nearby wifi devices and no local network permission`() {
    val guard =
        newGuard(
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    assertEquals(
        listOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
        ),
        guard.requiredPermissions,
    )
  }

  // TODO(Peter): Robolectric needs API 37 support
  //  @Test
  //  @Config(sdk = [Build.VERSION_CODES.CINNAMON_BUN])
  //  fun `CINNAMON_BUN and above additionally requires local network permission`() {
  //    val guard = newGuard()
  //
  //    assertEquals(
  //        listOf(
  //            android.Manifest.permission.ACCESS_WIFI_STATE,
  //            android.Manifest.permission.CHANGE_WIFI_STATE,
  //            android.Manifest.permission.NEARBY_WIFI_DEVICES,
  //            android.Manifest.permission.ACCESS_LOCAL_NETWORK,
  //        ),
  //        guard.requiredPermissions,
  //    )
  //  }
}
