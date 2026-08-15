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

import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.prereq.background.BackgroundDataGuard
import com.pyamsoft.tetherfi.server.prereq.location.LocationChecker
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuard
import com.pyamsoft.tetherfi.server.prereq.vpn.VpnChecker
import javax.inject.Inject
import kotlinx.coroutines.withContext

internal class AndroidHotspotRequirements
@Inject
internal constructor(
    private val backgroundDataGuard: BackgroundDataGuard,
    private val permissionGuard: PermissionGuard,
    private val vpnChecker: VpnChecker,
    private val locationChecker: LocationChecker,
    private val dispatchers: AppDispatchers,
) : HotspotRequirements {

  override suspend fun blockers(): Collection<HotspotStartBlocker> =
      withContext(context = dispatchers.default) {
        val blockers = mutableSetOf<HotspotStartBlocker>()

        if (!permissionGuard.canCreateNetwork()) {
          blockers.add(HotspotStartBlocker.PERMISSION)
        }

        if (!backgroundDataGuard.canCreateNetwork()) {
          blockers.add(HotspotStartBlocker.BACKGROUND_DATA)
        }

        if (vpnChecker.isUsingVpn()) {
          blockers.add(HotspotStartBlocker.VPN)
        }

        if (!locationChecker.isLocationOn()) {
          blockers.add(HotspotStartBlocker.LOCATION)
        }

        return@withContext blockers
      }
}
