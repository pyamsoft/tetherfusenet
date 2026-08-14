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

package com.pyamsoft.tetherfi.service.prereq

import com.pyamsoft.tetherfi.server.prereq.background.BackgroundDataGuard
import com.pyamsoft.tetherfi.server.prereq.location.LocationChecker
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuard
import com.pyamsoft.tetherfi.server.prereq.vpn.VpnChecker
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakePermissionGuard(private val granted: Boolean) : PermissionGuard {
  override val requiredPermissions: List<String> = emptyList()

  override suspend fun canCreateNetwork(): Boolean = granted
}

private class FakeBackgroundDataGuard(private val allowed: Boolean) : BackgroundDataGuard {
  override suspend fun canCreateNetwork(): Boolean = allowed
}

private class FakeVpnChecker(private val usingVpn: Boolean) : VpnChecker {
  override suspend fun isUsingVpn(): Boolean = usingVpn
}

private class FakeLocationChecker(private val locationOn: Boolean) : LocationChecker {
  override suspend fun isLocationOn(): Boolean = locationOn
}

private fun newRequirements(
    permissionGranted: Boolean = true,
    backgroundDataAllowed: Boolean = true,
    usingVpn: Boolean = false,
    locationOn: Boolean = true,
): AndroidHotspotRequirements =
    AndroidHotspotRequirements(
        backgroundDataGuard = FakeBackgroundDataGuard(backgroundDataAllowed),
        permissionGuard = FakePermissionGuard(permissionGranted),
        vpnChecker = FakeVpnChecker(usingVpn),
        locationChecker = FakeLocationChecker(locationOn),
    )

class AndroidHotspotRequirementsTest {

  @Test
  fun `no blockers when everything is clear`() = runTest {
    val blockers = newRequirements().blockers()
    assertEquals(emptySet(), blockers)
  }

  @Test
  fun `missing permission is reported as a blocker`() = runTest {
    val blockers = newRequirements(permissionGranted = false).blockers()
    assertEquals(setOf(HotspotStartBlocker.PERMISSION), blockers)
  }

  @Test
  fun `restricted background data is reported as a blocker`() = runTest {
    val blockers = newRequirements(backgroundDataAllowed = false).blockers()
    assertEquals(setOf(HotspotStartBlocker.BACKGROUND_DATA), blockers)
  }

  @Test
  fun `active vpn is reported as a blocker`() = runTest {
    val blockers = newRequirements(usingVpn = true).blockers()
    assertEquals(setOf(HotspotStartBlocker.VPN), blockers)
  }

  @Test
  fun `location disabled is reported as a blocker`() = runTest {
    val blockers = newRequirements(locationOn = false).blockers()
    assertEquals(setOf(HotspotStartBlocker.LOCATION), blockers)
  }

  @Test
  fun `all blockers reported when everything is blocked`() = runTest {
    val blockers =
        newRequirements(
                permissionGranted = false,
                backgroundDataAllowed = false,
                usingVpn = true,
                locationOn = false,
            )
            .blockers()

    assertEquals(
        setOf(
            HotspotStartBlocker.PERMISSION,
            HotspotStartBlocker.BACKGROUND_DATA,
            HotspotStartBlocker.VPN,
            HotspotStartBlocker.LOCATION,
        ),
        blockers,
    )
  }
}
