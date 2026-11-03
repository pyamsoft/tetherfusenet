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
import com.pyamsoft.tetherfi.server.netty.TestSetup
import com.pyamsoft.tetherfi.server.netty.withLogging
import com.pyamsoft.tetherfi.server.proxy.session.address
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpServerCodec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

class Http1ChunkedForwardTest {

  private data class Proxy(
      val server: EmbeddedChannel,
      val outbound: CompletableDeferred<EmbeddedChannel>,
  )

  @CheckResult
  private fun createProxy(scope: CoroutineScope): Proxy {
    val outbound = CompletableDeferred<EmbeddedChannel>()
    val creator =
        object : ChannelCreator {
          override fun bind(onChannelInitialized: (Channel) -> Unit): ChannelFuture {
            throw UnsupportedOperationException()
          }

          override fun connect(
              hostName: String,
              port: Int,
              onChannelInitialized: (Channel) -> Unit,
          ): ChannelFuture {
            val ch = EmbeddedChannel()
            onChannelInitialized(ch)
            outbound.complete(ch)
            return ch.newSucceededFuture()
          }
        }

    val context =
        TestSetup.withHandler(
            scope = scope,
            isHttpEnabled = true,
            isSocksEnabled = false,
            dispatchers = AppDispatchers.create(),
            factory = { params ->
              Http1ProxyHandler.factory(
                      scope = scope,
                      isDebug = true,
                      serverSocketTimeout = params.serverSocketTimeout,
                      allowedClients = params.allowed,
                      blockedClients = params.blocked,
                      dispatchers = params.dispatchers,
                      tcpSocketCreator = creator,
                  )
                  .create(Unit)
            },
        )

    val server = context.channel
    Http1ProxyHandler.applyChannelAttributes(
        channel = server,
        client = context.resolver.ensure(server.remoteAddress().address),
    )

    server.pipeline().addFirst(HttpServerCodec())

    return Proxy(
        server = server,
        outbound = outbound,
    )
  }

  @CheckResult
  private fun String.ascii(): ByteBuf {
    return Unpooled.copiedBuffer(this, Charsets.US_ASCII)
  }

  private fun EmbeddedChannel.send(s: String) {
    writeInbound(s.ascii())
    runPendingTasks()
    checkException()
  }

  @CheckResult
  private fun EmbeddedChannel.drainOutbound(): ByteBuf {
    val all = Unpooled.buffer()
    while (true) {
      val b = readOutbound<ByteBuf>() ?: break
      try {
        all.writeBytes(b)
      } finally {
        b.release()
      }
    }
    return all
  }

  @CheckResult
  private fun decodeRequest(bytes: ByteBuf): FullHttpRequest {
    val decoder = EmbeddedChannel(HttpRequestDecoder(), HttpObjectAggregator(1024))
    try {
      decoder.writeInbound(bytes)
      return decoder.readInbound()
    } finally {
      decoder.finishAndReleaseAll()
    }
  }

  private fun withProxy(block: suspend (Proxy) -> Unit) = runBlockingWithDelays {
    withLogging {
      val scope = CoroutineScope(SupervisorJob())
      try {
        val proxy = createProxy(scope)
        try {
          block(proxy)
        } finally {
          proxy.server.finishAndReleaseAll()
          proxy.outbound.await().finishAndReleaseAll()
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @CheckResult
  private fun List<String>.rawNetwork(): String {
    return this.joinToString(separator = "") { "$it\r\n" }
  }

  @Test
  fun `test HTTP1 forwards a chunked request body intact`() = withProxy { proxy ->
    proxy.server.send(
        listOf(
                "POST http://example.com/upload HTTP/1.1",
                "Host: example.com",
                "Transfer-Encoding: chunked",
                "",
                "5",
                "hello",
            )
            .rawNetwork(),
    )
    proxy.server.send(listOf("6", " world", "0", "").rawNetwork())

    val outbound = proxy.outbound.await()
    val req = decodeRequest(outbound.drainOutbound())
    try {
      assertEquals("/upload", req.uri())
      assertEquals("hello world", req.content().toString(Charsets.US_ASCII))
    } finally {
      req.release()
    }

    // Once the request is complete, both codecs are gone and bytes are relayed raw
    assertNull(proxy.server.pipeline().get(HttpServerCodec::class.java))
    proxy.server.send("raw content after pipeline")
    val raw = outbound.drainOutbound()
    try {
      assertEquals("raw content after pipeline", raw.toString(Charsets.US_ASCII))
    } finally {
      raw.release()
    }
  }

  @Test
  fun `test HTTP1 relays an early response while the request body is still streaming`() =
      withProxy { proxy ->
        proxy.server.send(
            listOf(
                    "POST http://example.com/upload HTTP/1.1",
                    "Host: example.com",
                    "Transfer-Encoding: chunked",
                    "Expect: 100-continue",
                    "",
                )
                .rawNetwork(),
        )
        val early = listOf("HTTP/1.1 100 Continue", "").rawNetwork()
        val outbound = proxy.outbound.await()
        outbound.send(early)

        val relayed = proxy.server.drainOutbound()
        try {
          assertEquals(early, relayed.toString(Charsets.US_ASCII))
        } finally {
          relayed.release()
        }

        proxy.server.send(listOf("5", "hello", "6", " world", "0", "").rawNetwork())

        val req = decodeRequest(outbound.drainOutbound())
        try {
          assertEquals("hello world", req.content().toString(Charsets.US_ASCII))
        } finally {
          req.release()
        }
      }
}
