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
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.DefaultProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress

internal class UdpRelayUpstreamHandler
internal constructor(
    private val udpControlChannel: Channel,
    private val client: InetSocketAddress,
    serverSocketTimeout: ServerSocketTimeout,
) :
    DefaultProxyHandler(
        serverSocketTimeout = serverSocketTimeout,
    ) {

  @CheckResult
  private fun wrapUdpResponse(
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

  private fun handleReply(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
  ) {
    val sender = msg.sender()
    if (sender == null) {
      Timber.w { "(${channelId}) Remote UDP packet had NULL sender. Drop!" }
      return
    }

    val content = msg.retain().content()
    val response = wrapUdpResponse(alloc = ctx.alloc(), sender = sender, content = content)
    val packet = DatagramPacket(response, client)

    udpControlChannel.writeAndFlush(packet).addListener { msg.release() }
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    setChannelId("UDP-UPSTREAM-${addr.address}:${addr.port}")
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Do not send any unexpected traffic over this pipe
    ctx.flushAndClose()
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is DatagramPacket) {
      handleReply(ctx, msg)
    } else {
      Timber.w { "(${channelId}) Invalid message received: $msg" }
      super.channelRead(ctx, msg)
    }
  }
}
