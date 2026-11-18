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

package com.pyamsoft.tetherfi.server.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp.UDP
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import io.netty.util.ReferenceCounted
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class UdpPacketTest {

  private sealed interface Outcome {
    data class Unwrapped(
        val hostName: String,
        val port: Int,
        val payload: ByteArray,
    ) : Outcome {
      override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Unwrapped

        if (hostName != other.hostName) return false
        if (port != other.port) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
      }

      override fun hashCode(): Int {
        var result = hostName.hashCode()
        result = 31 * result + port
        result = 31 * result + payload.contentHashCode()
        return result
      }
    }

    data object Error : Outcome
  }

  @CheckResult
  private fun packet(vararg header: Int, payload: String = ""): DatagramPacket {
    val buf =
        Unpooled.buffer().apply {
          header.forEach { writeByte(it) }
          writeBytes(payload.toByteArray(Charsets.US_ASCII))
        }
    val randomPort = (1..65000).random()
    return DatagramPacket(buf, InetSocketAddress(PROXY_HOST, randomPort))
  }

  @CheckResult
  private fun unwrap(msg: DatagramPacket): Outcome {
    var outcome: Outcome? = null
    UDP.unwrap(
        channelId = "TEST",
        msg = msg,
        onError = { failed: ReferenceCounted ->
          ReferenceCountUtil.release(failed)
          outcome = Outcome.Error
        },
        onUnwrapped = { data: ByteBuf, hostName: String, port: Int ->
          try {
            outcome = Outcome.Unwrapped(hostName, port, ByteBufUtil.getBytes(data))
          } finally {
            data.release()
          }
        },
    )
    return requireNotNull(outcome)
  }

  private fun assertDropped(vararg bytes: Int) {
    val msg = packet(*bytes)
    assertEquals(Outcome.Error, unwrap(msg))
    assertEquals(0, msg.refCnt())
  }

  @Test fun `test UDP drops a packet shorter than the fixed header`() = assertDropped(0, 0, 0)

  @Test
  fun `test UDP drops a packet with a bad reserved byte`() =
      assertDropped(0, 1, 0, 1, 1, 2, 3, 4, 0, 53)

  @Test fun `test UDP drops a fragmented packet`() = assertDropped(0, 0, 1, 1, 1, 2, 3, 4, 0, 53)

  @Test fun `test UDP drops a zero length domain`() = assertDropped(0, 0, 0, 3, 0, 0, 53)

  @Test fun `test UDP drops a packet missing its port`() = assertDropped(0, 0, 0, 1, 1, 2, 3, 4, 0)

  @Test fun `test UDP drops a packet with port zero`() = assertDropped(0, 0, 0, 1, 1, 2, 3, 4, 0, 0)

  @Test
  fun `test UDP unwraps an IPv4 packet`() {
    val msg = packet(0, 0, 0, 1, 8, 8, 4, 4, 0, 53, payload = "query")

    val result = unwrap(msg)
    assertTrue(result is Outcome.Unwrapped)
    assertEquals("8.8.4.4", result.hostName)
    assertEquals(53, result.port)
    assertEquals("query", result.payload.toString(Charsets.US_ASCII))
    assertEquals(0, msg.refCnt())
  }

  @Test
  fun `test UDP unwraps a DOMAIN packet without resolving`() {
    val msg = packet(0, 0, 0, 3, 7, *"a.b.com".map { it.code }.toIntArray(), 0, 53, payload = "q")

    val result = unwrap(msg)
    assertTrue(result is Outcome.Unwrapped)
    assertEquals("a.b.com", result.hostName)
    assertEquals(53, result.port)
  }

  @Test
  fun `test UDP wrap writes the SOCKS5 header`() {
    val content = Unpooled.copiedBuffer("xy", Charsets.US_ASCII)
    val wrapped =
        UDP.wrap(
            alloc = ByteBufAllocator.DEFAULT,
            sender = InetSocketAddress("1.2.3.4", 5353),
            content = content,
        )
    try {
      assertContentEquals(
          byteArrayOf(
              0,
              0,
              0,
              1,
              1,
              2,
              3,
              4,
              0x14,
              0xE9.toByte(),
              'x'.code.toByte(),
              'y'.code.toByte(),
          ),
          ByteBufUtil.getBytes(wrapped),
      )
    } finally {
      wrapped.release()
      content.release()
    }
  }

  @Test
  fun `test UDP wrap then unwrap round trips`() {
    val sender = InetSocketAddress("9.9.9.9", 853)
    val content = Unpooled.copiedBuffer("round trip", Charsets.US_ASCII)
    val wrapped = UDP.wrap(alloc = ByteBufAllocator.DEFAULT, sender = sender, content = content)
    content.release()

    val result = unwrap(DatagramPacket(wrapped, sender))
    assertTrue(result is Outcome.Unwrapped)
    assertEquals("9.9.9.9", result.hostName)
    assertEquals(853, result.port)
    assertEquals("round trip", result.payload.toString(Charsets.US_ASCII))
  }

  companion object {
    private const val PROXY_HOST = "127.0.0.1"
  }
}
