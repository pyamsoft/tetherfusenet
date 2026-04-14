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

import com.pyamsoft.tetherfi.server.HOSTNAME
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProtocolDelegatingHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.TcpChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.UdpChannelCreator
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.handler.codec.socksx.SocksVersion
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

private class TestEmbeddedChannel(
    vararg handlers: ChannelHandler,
) : EmbeddedChannel(*handlers) {

  private val server = InetSocketAddress(HOSTNAME, 8228)
  private val remote = InetSocketAddress(HOSTNAME, 12345)

  override fun localAddress0(): SocketAddress {
    return server
  }

  override fun remoteAddress0(): SocketAddress {
    return remote
  }
}

class DelegatingHandlerTest {

  private suspend fun withHandler(
      scope: CoroutineScope,
      isHttpEnabled: Boolean,
      isSocksEnabled: Boolean,
  ): TestEmbeddedChannel {
    val allowed =
        object : AllowedClients {
          override fun listenForClients(): Flow<List<TetherClient>> {
            return flowOf(emptyList())
          }

          override fun seen(client: TetherClient) {}

          override fun reportTransfer(client: TetherClient, report: ByteTransferReport) {}
        }

    val resolver =
        object : ClientResolver {

          private val clients = mutableMapOf<String, TetherClient>()

          override fun ensure(hostNameOrIp: String): TetherClient {
            return clients.getOrPut(hostNameOrIp) {
              TetherClient.create(
                  hostNameOrIp,
                  clock = Clock.systemDefaultZone(),
              )
            }
          }
        }

    val socketTagger = SocketTagger {}

    val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

    val tcpSocketCreator =
        TcpChannelCreator(
            eventLoop = workerGroup,
            socketTagger = socketTagger,
            androidPreferredNetwork = null,
        )

    val udpSocketCreator =
        UdpChannelCreator(
            eventLoop = workerGroup,
            socketTagger = socketTagger,
            androidPreferredNetwork = null,
        )

    val params =
        ProtocolDelegatingHandler.Params(
            scope = scope,
            tcp = tcpSocketCreator,
            udp = if (isSocksEnabled) udpSocketCreator else null,
        )

    val factory =
        ProtocolDelegatingHandler.factory(
            isHttpEnabled = isHttpEnabled,
            isDebug = true,
            serverSocketTimeout = ServerSocketTimeout.Defaults.BALANCED,
            allowedClients = allowed,
            clientResolver = resolver,
        )

    val handler = factory.create(params)
    return TestEmbeddedChannel(handler)
  }

  @Test
  fun `test Netty server handler does not intercept connections when completely disabled`(): Unit =
      runBlockingWithDelays {
        withLogging {
          val channel =
              withHandler(
                  scope = this,
                  isHttpEnabled = false,
                  isSocksEnabled = false,
              )

          val httpCommand = "CONNECT https://google.com HTTP/1.1"
          val buf = Unpooled.buffer()
          buf.writeCharSequence(httpCommand, StandardCharsets.UTF_8)
          channel.writeInbound(buf)

          // This has NOT been read by a delegated handler, the buffer is still here
          val read = channel.readInbound<ByteBuf>()
          assertNotNull(read)

          val data = read.toString(StandardCharsets.UTF_8)
          assert(data == httpCommand)
        }
      }

  @Test
  fun `test Netty server intercepts HTTP(S) connections`(): Unit = runBlockingWithDelays {
    withLogging {
      val channel =
          withHandler(
              scope = this,
              isHttpEnabled = true,
              isSocksEnabled = false,
          )

      val httpCommand = "CONNECT https://google.com HTTP/1.1"
      val buf = Unpooled.buffer()
      buf.writeCharSequence(httpCommand, StandardCharsets.UTF_8)
      channel.writeInbound(buf)
    }
  }

  @Test
  fun `test Netty server intercepts SOCKS connections`(): Unit = runBlockingWithDelays {
    withLogging {
      val channel =
          withHandler(
              scope = this,
              isHttpEnabled = false,
              isSocksEnabled = true,
          )

      val socks4Buf = Unpooled.buffer()
      socks4Buf.writeByte(SocksVersion.SOCKS4a.byteValue().toInt())
      channel.writeInbound(socks4Buf)

      // This has been read by the handler
      val readSocks4 = channel.readInbound<Byte>()
      assertNull(readSocks4)

      val socks5Buf = Unpooled.buffer()
      socks5Buf.writeByte(SocksVersion.SOCKS5.byteValue().toInt())
      channel.writeInbound(socks5Buf)

      // This has been read by the handler
      val readSocks5 = channel.readInbound<Byte>()
      assertNull(readSocks5)
    }
  }
}
