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
import com.pyamsoft.tetherfi.core.Timber
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.util.concurrent.EventExecutor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

object UDP {

  private val VALID_PORT_RANGE = 1..<65535

  @CheckResult
  private fun readAddress(
      channelId: String,
      buf: ByteBuf,
      type: Socks5AddressType,
  ): String {
    try {
      when (type) {
        Socks5AddressType.IPv4 -> {
          val bytes = ByteArray(4)
          buf.readBytes(bytes)
          val addr = Inet4Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "(${channelId}) Unable to construct IPv4 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "(${channelId}) Empty address from IPv4 bytes: $addr" }
            return ""
          }

          return host
        }

        Socks5AddressType.IPv6 -> {
          val bytes = ByteArray(16)
          buf.readBytes(bytes)
          val addr = Inet6Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "(${channelId}) Unable to construct IPv6 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "(${channelId}) Empty address from IPv6 bytes: $addr" }
            return ""
          }

          return host
        }

        Socks5AddressType.DOMAIN -> {
          val addressLength = buf.readUnsignedByte().toInt()
          if (addressLength == 0) {
            // SOCKS spec says we must fall back to 0 address
            return "0.0.0.0"
          }

          val sequence = buf.readCharSequence(addressLength, StandardCharsets.US_ASCII).toString()
          if (addressLength == 1 && sequence == "0") {
            // PySocks delivers a random port with an address of "0"
            // SOCKS spec says we must fall back to 0 address
            return "0.0.0.0"
          }

          return sequence
        }

        else -> {
          Timber.w { "(${channelId}) Invalid datapacket address type $type" }
          return ""
        }
      }
    } catch (e: Throwable) {
      Timber.e(e) { "(${channelId}) Error when reading address from data type $type" }
      return ""
    }
  }

  fun unwrap(
      channelId: String,
      executor: EventExecutor,
      msg: DatagramPacket,
      onUnwrapped: (ByteBuf, InetSocketAddress) -> Unit,
      onError: () -> Unit,
  ) {
    val buf = msg.content()
    // Drop bad connection
    if (buf == null) {
      Timber.w { "(${channelId}) DROP: Null buffer in packet" }
      onError()
      return
    }

    val reservedByteOne = buf.readByte()
    if (reservedByteOne != RESERVED_BYTE) {
      Timber.w { "(${channelId}) DROP: Expected reserve byte one, but got data: $reservedByteOne" }
      onError()
      return
    }

    val reservedByteTwo = buf.readByte()
    if (reservedByteTwo != RESERVED_BYTE) {
      Timber.w { "(${channelId}) DROP: Expected reserve byte two, but got data: $reservedByteTwo" }
      onError()
      return
    }

    val fragment = buf.readByte()
    if (fragment != FRAGMENT_ZERO) {
      Timber.w { "(${channelId}) DROP: Fragments not supported: $fragment" }
      onError()
      return
    }

    val addressTypeByte = buf.readByte()
    val addrType = Socks5AddressType.valueOf(addressTypeByte)
    val destinationAddr = readAddress(channelId, buf, addrType)

    // A short max is 32767 but ports can go up to 65k
    // Sometimes the short value is negative, in that case, we
    // "fix" it by converting back to an unsigned number
    val destinationPort = buf.readUnsignedShort()

    if (destinationAddr.isBlank()) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination address: $destinationAddr" }
      onError()
      return
    }

    if (destinationPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination port: $destinationPort" }
      onError()
      return
    }

    // The rest of the packet is data
    // We must retain this slice or the underlying buffer will be cleaned up too early
    val data = buf.readRetainedSlice(buf.readableBytes())

    // Build the destination
    executor.submit {
      // NOTE(Peter): This is blocking because it resolves DNS via system
      val destination = InetSocketAddress(destinationAddr, destinationPort)

      onUnwrapped(data, destination)
    }
  }

  @CheckResult
  fun wrap(
      alloc: ByteBufAllocator,
      sender: InetSocketAddress,
      content: ByteBuf,
  ): ByteBuf {
    // May be able to initialize with 3
    return alloc.ioBuffer().apply {
      // 2 reserved
      val res = RESERVED_BYTE_INT
      writeByte(res)
      writeByte(res)

      // No fragment
      writeByte(FRAGMENT_ZERO_INT)

      // Address
      writeByte(resolveSocks5AddressType(sender).byteValue().toInt())
      writeBytes(sender.address.address)

      // Port
      writeShort(sender.port)

      // Content
      writeBytes(content)
    }
  }
}
