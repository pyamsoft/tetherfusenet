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
import com.pyamsoft.pydroid.core.LintIgnoreMagicNumber
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.IPV4_ADDRESS_REGEX
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
    ) : ConnectionInfo {

      private data class NotAnIpAddressException(val hostName: String) :
          IllegalStateException("Not an IP address: $hostName")

      // Is this Connection info from the server an IP address or a DNS hostname?
      // It's almost always an IP address
      val isIpv4Address by lazy { IPV4_ADDRESS_REGEX.matches(hostName) }

      /**
       * Split up an IP address
       *
       * 192.168.49.1 -> [ 192, 168, 49, 1 ]
       */
      private val splitUpIp by lazy {
        ensureIpAddress()
        hostName.split(".")
      }

      /**
       * IP first block
       *
       * 192.168.49.1 -> 192
       */
      private val ipFirstBlock by lazy {
        ensureIpAddress()
        splitUpIp[0]
      }

      /**
       * IP second block
       *
       * 192.168.49.1 -> 168
       */
      private val ipSecondBlock by lazy {
        ensureIpAddress()
        splitUpIp[1]
      }

      /**
       * IP third block
       *
       * 192.168.49.1 -> 49
       */
      private val ipThirdBlock by lazy {
        ensureIpAddress()
        splitUpIp[2]
      }

      /** If this is not an IP address, you've done something wrong */
      private fun ensureIpAddress() {
        if (!isIpv4Address) {
          throw NotAnIpAddressException(hostName)
        }
      }

      /**
       * If the server is an IP address, then we should check that this client is also IP
       * addressable.
       *
       * If the client is a hostname client (do we have any of these ever?), then this method does
       * not apply
       */
      @CheckResult
      fun isClientWithinAddressableIpRange(ip: String): Boolean {
        val splitUpAddress = ip.split(".")

        // Needs to be 4 sections
        @LintIgnoreMagicNumber
        if (splitUpAddress.size != 4) {
          Timber.w { "Split up IP address was wrong format: $ip $splitUpAddress" }
          return false
        }

        val first = splitUpAddress[0]
        val second = splitUpAddress[1]
        val third = splitUpAddress[2]

        if (first != ipFirstBlock) {
          Timber.w { "Mismatch IP first block: $ip $hostName" }
          return false
        }

        if (second != ipSecondBlock) {
          Timber.w { "Mismatch IP second block: $ip $hostName" }
          return false
        }

        if (third != ipThirdBlock) {
          Timber.w { "Mismatch IP third block: $ip $hostName" }
          return false
        }

        // This is a "matching" IP of
        // XXX.YYY.ZZZ.???
        // which is good enough for us
        return true
      }
    }

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
