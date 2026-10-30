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

package com.pyamsoft.tetherfi.server.broadcast

import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.pyamsoft.tetherfi.server.Server
import kotlinx.coroutines.flow.Flow

interface BroadcastNetworkStatus : Server {

  @CheckResult fun onGroupInfoChanged(): Flow<GroupInfo>

  @CheckResult fun onConnectionInfoChanged(): Flow<ConnectionInfo>

  @Stable
  @Immutable
  sealed interface GroupInfo {

    data object Unchanged : GroupInfo

    data class Connected
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    constructor(
        val ssid: String,
        val password: String,
        val clients: Collection<Device>,
    ) : GroupInfo {

      override fun toString(): String {
        return "Connected(ssid='$ssid')"
      }

      /** A Connected client device */
      data class Device(
          val name: String,
          val ipAddress: String,
      )
    }

    data object Empty : GroupInfo

    data class Error
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    constructor(val error: Throwable) : GroupInfo

    @CheckResult
    fun update(onUpdate: (Connected) -> Connected): GroupInfo {
      return when (this) {
        is Connected -> onUpdate(this)
        is Empty -> this
        is Error -> this
        is Unchanged -> this
      }
    }
  }

  @Stable
  @Immutable
  sealed interface ConnectionInfo {
    data object Unchanged : ConnectionInfo

    data class Connected
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    constructor(
        val hostName: String,
    ) : ConnectionInfo

    data object Empty : ConnectionInfo

    data class Error
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    constructor(
        val error: Throwable,
    ) : ConnectionInfo

    @CheckResult
    fun update(onUpdate: (Connected) -> Connected): ConnectionInfo {
      return when (this) {
        is Connected -> onUpdate(this)
        is Empty -> this
        is Error -> this
        is Unchanged -> this
      }
    }
  }
}
