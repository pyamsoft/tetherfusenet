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
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.netty.withLogging
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp.UDP
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.DefaultEventLoop
import io.netty.channel.local.LocalChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import io.netty.util.ReferenceCounted
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

class UdpPacketTest {

  private sealed interface Outcome {
    data class Unwrapped(
        val destination: InetSocketAddress,
        val payload: ByteArray,
    ) : Outcome {
      override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Unwrapped

        if (destination != other.destination) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
      }

      override fun hashCode(): Int {
        var result = destination.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
      }
    }

    data object Error : Outcome
  }

  private class Harness(
      val eventLoop: DefaultEventLoop,
      val channel: Channel,
      val ctx: ChannelHandlerContext,
  )

  private fun withHarness(block: suspend CoroutineScope.(Harness) -> Unit) = runBlockingWithDelays {
    withLogging {
      val eventLoop = DefaultEventLoop()
      val channel = LocalChannel()
      try {
        eventLoop.register(channel)

        val channelId = UUID.randomUUID().toString()
        channel.pipeline().addLast(channelId, ChannelInboundHandlerAdapter())
        val ctx = channel.pipeline().context(channelId)

        block(Harness(eventLoop = eventLoop, channel = channel, ctx = ctx))
      } finally {
        channel.flushAndClose()
        eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
      }
    }
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
  private fun Harness.unwrap(
      scope: CoroutineScope,
      msg: DatagramPacket,
      dispatchers: AppDispatchers = AppDispatchers.create(),
  ): CompletableDeferred<Outcome> {
    val outcome = CompletableDeferred<Outcome>()
    UDP.unwrap(
        channelId = "TEST",
        scope = scope,
        dispatchers = dispatchers,
        ctx = ctx,
        msg = msg,
        onError = { failed: ReferenceCounted ->
          ReferenceCountUtil.release(failed)
          outcome.complete(Outcome.Error)
        },
        onUnwrapped = { data: ByteBuf, destination: InetSocketAddress ->
          try {
            outcome.complete(Outcome.Unwrapped(destination, ByteBufUtil.getBytes(data)))
          } finally {
            data.release()
          }
        },
    )
    return outcome
  }

  private val inlineDispatchers =
      object : AppDispatchers {
        override val default = Dispatchers.Unconfined
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
      }

  private fun assertDropped(vararg bytes: Int) = withHarness { h ->
    val msg = packet(*bytes)
    val outcome = h.unwrap(scope = this, msg)

    assertEquals(Outcome.Error, outcome.await())
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
  fun `test UDP unwraps an IPv4 packet`() = withHarness { h ->
    val msg = packet(0, 0, 0, 1, 8, 8, 4, 4, 0, 53, payload = "query")
    val outcome = h.unwrap(scope = this, msg)

    val result = outcome.await()
    assertTrue(result is Outcome.Unwrapped)
    assertEquals(InetSocketAddress("8.8.4.4", 53), result.destination)
    assertEquals("query", result.payload.toString(Charsets.US_ASCII))
    assertEquals(0, msg.refCnt())
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
  fun `test UDP wrap then unwrap round trips`() = withHarness { h ->
    val sender = InetSocketAddress("9.9.9.9", 853)
    val content = Unpooled.copiedBuffer("round trip", Charsets.US_ASCII)
    val wrapped = UDP.wrap(alloc = ByteBufAllocator.DEFAULT, sender = sender, content = content)
    content.release()

    val result = h.unwrap(scope = this, DatagramPacket(wrapped, sender)).await()
    assertTrue(result is Outcome.Unwrapped)
    assertEquals(sender, result.destination)
    assertEquals("round trip", result.payload.toString(Charsets.US_ASCII))
  }

  @Test
  fun `test UDP releases data when the scope is already cancelled`() = withHarness { h ->
    val msg = packet(0, 0, 0, 1, 8, 8, 4, 4, 0, 53, payload = "query")
    val content = msg.content()

    val dead = CoroutineScope(SupervisorJob()).apply { cancel() }
    val outcome = h.unwrap(scope = dead, msg, dispatchers = inlineDispatchers)

    assertEquals(0, content.refCnt())
    assertFalse(outcome.isCompleted)
  }

  @Test
  fun `test UDP releases data when the event loop rejects the hop`() = withHarness { h ->
    val msg = packet(0, 0, 0, 1, 8, 8, 4, 4, 0, 53, payload = "query")
    val content = msg.content()

    h.channel.close().sync()
    h.eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync()
    val outcome = h.unwrap(scope = this, msg, dispatchers = inlineDispatchers)

    assertEquals(0, content.refCnt())
    assertFalse(outcome.isCompleted)
  }

  companion object {
    private const val PROXY_HOST = "127.0.0.1"
  }
}
