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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks

import androidx.annotation.CheckResult
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import java.net.InetSocketAddress

@CheckResult
internal fun resolveSocks5AddressType(localAddr: InetSocketAddress): Socks5AddressType {
  return if (localAddr.address.address.size == 4) {
    Socks5AddressType.IPv4
  } else {
    Socks5AddressType.IPv6
  }
}

internal const val RESERVED_BYTE: Byte = 0
internal const val RESERVED_BYTE_INT: Int = RESERVED_BYTE.toInt()

// We do NOT support fragments, anything other than the 0 byte should be dropped
internal const val FRAGMENT_ZERO: Byte = 0
internal const val FRAGMENT_ZERO_INT: Int = FRAGMENT_ZERO.toInt()
