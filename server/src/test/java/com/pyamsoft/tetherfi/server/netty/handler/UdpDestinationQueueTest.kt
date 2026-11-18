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
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp.UdpDestinationQueue
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.DefaultEventLoop
import io.netty.channel.local.LocalChannel
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Test

class UdpDestinationQueueTest {

  private class Harness(
      val eventLoop: DefaultEventLoop,
      val ctx: ChannelHandlerContext,
      val delivered: MutableList<String>,
      val failed: MutableList<String>,
  )

  private val inlineDispatchers =
      object : AppDispatchers {
        override val default = Dispatchers.Unconfined
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
      }

  private fun withHarness(block: suspend CoroutineScope.(Harness) -> Unit) = runBlockingWithDelays {
    withLogging {
      val eventLoop = DefaultEventLoop()
      val channel = LocalChannel()
      try {
        eventLoop.register(channel).sync()
        channel.pipeline().addLast("queue-test", ChannelInboundHandlerAdapter())
        val ctx = channel.pipeline().context("queue-test")

        block(
            Harness(
                eventLoop = eventLoop,
                ctx = ctx,
                delivered = Collections.synchronizedList(mutableListOf()),
                failed = Collections.synchronizedList(mutableListOf()),
            ),
        )
      } finally {
        channel.flushAndClose()
        eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
      }
    }
  }

  private fun payload(text: String): ByteBuf = Unpooled.copiedBuffer(text, Charsets.US_ASCII)

  private fun Harness.enqueue(
      queue: UdpDestinationQueue,
      data: ByteBuf,
      hostName: String,
  ) {
    eventLoop
        .submit {
          queue.enqueue(
              ctx = ctx,
              channelId = "TEST",
              data = data,
              hostName = hostName,
              port = 53,
              onReady = { d, destination ->
                try {
                  delivered.add("${d.toString(Charsets.US_ASCII)}@${destination.hostString}")
                } finally {
                  d.release()
                }
              },
              onFailed = { d ->
                try {
                  failed.add(d.toString(Charsets.US_ASCII))
                } finally {
                  d.release()
                }
              },
          )
        }
        .sync()
  }

  private suspend fun waitFor(condition: () -> Boolean) {
    while (!condition()) {
      delay(10.milliseconds)
    }
  }

  @CheckResult
  private fun CoroutineScope.gatedQueue(
      gates: Map<String, CompletableDeferred<Unit>>,
      lookups: MutableList<String> = Collections.synchronizedList(mutableListOf()),
  ): UdpDestinationQueue =
      UdpDestinationQueue.createForTest(
          scope = this,
          dispatchers = AppDispatchers.create(),
          resolveDnsAddress = { _, hostName, port ->
            lookups.add(hostName)
            gates[hostName]?.await()
            InetSocketAddress.createUnresolved(hostName, port)
          },
      )

  @Test
  fun `test IP destination is delivered immediately without resolving`() = withHarness { h ->
    val queue =
        UdpDestinationQueue.createForTest(
            scope = this,
            dispatchers = inlineDispatchers,
            resolveDnsAddress = { _, _, _ -> error("IP destinations must not be resolved") },
        )

    val data = payload("a")
    h.enqueue(queue, data, "8.8.8.8")

    assertEquals(listOf("a@8.8.8.8"), h.delivered)
    assertEquals(0, data.refCnt())
  }

  @Test
  fun `test IP destination is not held up by a pending hostname`() = withHarness { h ->
    val gate = CompletableDeferred<Unit>()
    val queue = gatedQueue(mapOf("slow.com" to gate))

    h.enqueue(queue, payload("first"), "slow.com")
    h.enqueue(queue, payload("second"), "1.1.1.1")

    assertEquals(listOf("second@1.1.1.1"), h.delivered)

    gate.complete(Unit)
    waitFor { h.delivered.size == 2 }

    assertEquals(listOf("second@1.1.1.1", "first@slow.com"), h.delivered)
  }

  @Test
  fun `test packets to the same hostname stay ordered and share one lookup`() = withHarness { h ->
    val gate = CompletableDeferred<Unit>()
    val lookups = Collections.synchronizedList(mutableListOf<String>())
    val queue = gatedQueue(mapOf("example.com" to gate), lookups)

    h.enqueue(queue, payload("first"), "example.com")
    h.enqueue(queue, payload("second"), "example.com")
    h.enqueue(queue, payload("third"), "example.com")

    assertTrue(h.delivered.isEmpty())

    gate.complete(Unit)
    waitFor { h.delivered.size == 3 }

    assertEquals(
        listOf("first@example.com", "second@example.com", "third@example.com"),
        h.delivered,
    )
    assertEquals(listOf("example.com"), lookups)
  }

  @Test
  fun `test slow hostname does not hold up a different hostname`() = withHarness { h ->
    val slow = CompletableDeferred<Unit>()
    val queue = gatedQueue(mapOf("slow.com" to slow))

    h.enqueue(queue, payload("first"), "slow.com")
    h.enqueue(queue, payload("second"), "fast.com")

    waitFor { h.delivered.size == 1 }
    assertEquals(listOf("second@fast.com"), h.delivered)

    slow.complete(Unit)
    waitFor { h.delivered.size == 2 }

    assertEquals(listOf("second@fast.com", "first@slow.com"), h.delivered)
  }

  @Test
  fun `test hostname is looked up again once the previous lookup finished`() = withHarness { h ->
    val lookups = Collections.synchronizedList(mutableListOf<String>())
    val queue = gatedQueue(emptyMap(), lookups)

    h.enqueue(queue, payload("first"), "example.com")
    waitFor { h.delivered.size == 1 }

    h.enqueue(queue, payload("second"), "example.com")
    waitFor { h.delivered.size == 2 }

    assertEquals(listOf("example.com", "example.com"), lookups)
  }

  @Test
  fun `test failed resolve fails every waiting packet in order`() = withHarness { h ->
    val gate = CompletableDeferred<Unit>()
    val queue =
        UdpDestinationQueue.createForTest(
            scope = this,
            dispatchers = AppDispatchers.create(),
            resolveDnsAddress = { _, _, _ ->
              gate.await()
              null
            },
        )

    h.enqueue(queue, payload("first"), "nope.invalid")
    h.enqueue(queue, payload("second"), "nope.invalid")

    gate.complete(Unit)
    waitFor { h.failed.size == 2 }

    assertEquals(listOf("first", "second"), h.failed)
    assertTrue(h.delivered.isEmpty())
  }

  @Test
  fun `test clear releases pending data`() = withHarness { h ->
    val gate = CompletableDeferred<Unit>()
    val queue = gatedQueue(mapOf("example.com" to gate, "other.com" to gate))

    val first = payload("first")
    val second = payload("second")
    val third = payload("third")
    h.enqueue(queue, first, "example.com")
    h.enqueue(queue, second, "example.com")
    h.enqueue(queue, third, "other.com")

    h.eventLoop.submit { queue.clear() }.sync()
    assertEquals(0, first.refCnt())
    assertEquals(0, second.refCnt())
    assertEquals(0, third.refCnt())

    gate.complete(Unit)
    delay(100.milliseconds)
    assertTrue(h.delivered.isEmpty())
    assertTrue(h.failed.isEmpty())
  }

  @Test
  fun `test data waits for clear when the scope is already cancelled`() = withHarness { h ->
    val dead = CoroutineScope(SupervisorJob()).apply { cancel() }
    val queue =
        UdpDestinationQueue.createForTest(
            scope = dead,
            dispatchers = inlineDispatchers,
            resolveDnsAddress = { _, hostName, port ->
              InetSocketAddress.createUnresolved(hostName, port)
            },
        )

    val data = payload("x")
    h.enqueue(queue, data, "example.com")
    assertEquals(1, data.refCnt())

    h.eventLoop.submit { queue.clear() }.sync()
    assertEquals(0, data.refCnt())
    assertTrue(h.delivered.isEmpty())
  }
}
