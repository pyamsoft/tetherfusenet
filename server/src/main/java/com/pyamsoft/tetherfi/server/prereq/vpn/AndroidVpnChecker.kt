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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.TweakPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AndroidVpnChecker
@Inject
internal constructor(
    private val context: Context,
    private val preferences: TweakPreferences,
    private val dispatchers: AppDispatchers,
) : VpnChecker {

  private val manager by lazy {
    context.applicationContext.getSystemService<ConnectivityManager>().requireNotNull()
  }

  override suspend fun isUsingVpn(): Boolean =
    withContext(context = dispatchers.default) {
        if (preferences.listenForStartIgnoreVpn().first()) {
          Timber.w { "Ignore VPN start blocker" }
          return@withContext false
        }

        val network = manager.activeNetwork
        val capabilities = manager.getNetworkCapabilities(network)

        if (capabilities == null) {
          Timber.w { "Could not retrieve NetworkCapabilities" }
          return@withContext false
        }

        return@withContext capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
      }
}
