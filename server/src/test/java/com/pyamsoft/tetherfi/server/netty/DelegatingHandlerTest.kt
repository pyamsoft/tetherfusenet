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

package com.pyamsoft.tetherfi.server.netty

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProtocolDelegatingHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks4ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.socksx.SocksVersion
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import org.junit.Test

class DelegatingHandlerTest {

  @CheckResult
  private fun CoroutineScope.delegatingHandlerFactory(
      factoryParams: TestSetup.FactoryParams
  ): ChannelInboundHandler {
    val params =
        ProtocolDelegatingHandler.Params(
            scope = this,
            tcp = factoryParams.provideTcpChannelCreator(),
            udp =
                if (factoryParams.isSocksEnabled) factoryParams.provideUdpChannelCreator()
                else null,
        )

    val factory =
        ProtocolDelegatingHandler.factory(
            isDebug = true,
            isHttpEnabled = factoryParams.isHttpEnabled,
            serverSocketTimeout = factoryParams.serverSocketTimeout,
            allowedClients = factoryParams.allowed,
            clientResolver = factoryParams.resolver,
        )

    return factory.create(params)
  }

  @Test
  fun `test Netty server handler does not intercept connections when completely disabled`(): Unit =
      runBlockingWithDelays {
        val scope = this

        withLogging {
          val context =
              TestSetup.withHandler(
                  isHttpEnabled = false,
                  isSocksEnabled = false,
                  factory = { delegatingHandlerFactory(it) },
              )
          val channel = context.channel

          val httpCommand = "CONNECT https://google.com HTTP/1.1"
          val buf = Unpooled.wrappedBuffer(httpCommand.toByteArray())

          channel.apply {
            writeInbound(buf)
            flushInbound()
            runPendingTasks()
          }

          // This has NOT been read by a delegated handler, the buffer is still here
          val read = channel.readInbound<ByteBuf>()
          assertNotNull(read)

          val data = read.toString(Charsets.UTF_8)
          assert(data == httpCommand)

          assertNull(channel.pipeline().get(Http1ProxyHandler::class.java))
          assertNull(channel.pipeline().get(Socks4ProxyHandler::class.java))
          assertNull(channel.pipeline().get(Socks5ProxyHandler::class.java))
        }
      }

  @Test
  fun `test Netty server intercepts HTTP(S) connections`(): Unit = runBlockingWithDelays {
    withLogging {
      val context =
          TestSetup.withHandler(
              isHttpEnabled = true,
              isSocksEnabled = false,
              factory = { delegatingHandlerFactory(it) },
          )
      val channel = context.channel

      val httpCommand = "CONNECT https://google.com HTTP/1.1"
      val buf = Unpooled.wrappedBuffer(httpCommand.toByteArray())

      channel.apply {
        writeInbound(buf)
        flushInbound()
        runPendingTasks()
      }

      // This has been read by the handler
      val readHttp = channel.readInbound<ByteBuf>()
      assertNull(readHttp)

      // Specifically, the HTTP handler which has been added to the pipeline
      assertNotNull(channel.pipeline().get(Http1ProxyHandler::class.java))
    }
  }

  @Test
  fun `test Netty server intercepts SOCKS4A connections`(): Unit = runBlockingWithDelays {
    withLogging {
      val context =
          TestSetup.withHandler(
              isHttpEnabled = false,
              isSocksEnabled = true,
              factory = { delegatingHandlerFactory(it) },
          )
      val channel = context.channel

      val buf = Unpooled.wrappedBuffer(byteArrayOf(SocksVersion.SOCKS4a.byteValue()))

      channel.apply {
        writeInbound(buf)
        flushInbound()
        runPendingTasks()
      }

      // This has been read by the handler
      val readSocks4 = channel.readInbound<Byte>()
      assertNull(readSocks4)

      // Specifically, the SOCKS4 handler which has been added to the pipeline
      assertNotNull(channel.pipeline().get(Socks4ProxyHandler::class.java))
    }
  }

  @Test
  fun `test Netty server intercepts SOCKS5 connections`(): Unit = runBlockingWithDelays {
    withLogging {
      val context =
          TestSetup.withHandler(
              isHttpEnabled = false,
              isSocksEnabled = true,
              factory = { delegatingHandlerFactory(it) },
          )
      val channel = context.channel

      val buf = Unpooled.wrappedBuffer(byteArrayOf(SocksVersion.SOCKS5.byteValue()))

      channel.apply {
        writeInbound(buf)
        flushInbound()
        runPendingTasks()
      }

      // This has been read by the handler
      val readSocks5 = channel.readInbound<Byte>()
      assertNull(readSocks5)

      // Specifically, the SOCKS5 handler which has been added to the pipeline
      assertNotNull(channel.pipeline().get(Socks5ProxyHandler::class.java))
    }
  }
}
