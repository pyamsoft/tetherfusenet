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

import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.handleIdleState
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

internal class UdpRelayHandler
internal constructor(
    serverSocketTimeout: ServerSocketTimeout,
    private val getTcpControl: AtomicReference<InetSocketAddress>,
    private val backToClient: AtomicReference<InetSocketAddress>,
    private val unregister: () -> Unit,
) :
    ProxyHandler(
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private fun unwrapUdpResponse(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
  ) {
    UDP.unwrap(
        channelId = channelId,
        msg = msg,
        executor = ctx.executor(),
        onError = { sendErrorAndClose(ctx, msg) },
        onUnwrapped = { data, destination ->
          val packet = DatagramPacket(data, destination)
          Timber.d {
            "Send upstream: ${
            ctx.channel().localAddress()
          } -> $destination ${ByteBufUtil.hexDump(data)}"
          }
          ctx.writeAndFlush(packet).addListener { packet.release() }
        },
    )
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    setChannelId("UDP-RELAY-${addr.address}:${addr.port}")
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    try {
      ctx.handleIdleState(evt) { unregister() }
    } finally {
      super.userEventTriggered(ctx, evt)
    }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Write a "0" response back to the UDP control channel
    val response =
        DefaultSocks5CommandResponse(
            Socks5CommandStatus.FAILURE,
            Socks5AddressType.IPv4,
            "0.0.0.0",
            0,
        )

    ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is DatagramPacket) {
      val serverChannel = ctx.channel()
      val serverAddress = serverChannel.localAddress().cast<InetSocketAddress>()
      if (serverAddress == null) {
        Timber.w { "(${channelId}) DROP: No server address" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val sender = msg.sender()
      if (sender == null) {
        Timber.w { "(${channelId}) DROP: Null sender in packet" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val tcpControlClient = getTcpControl.get()
      if (tcpControlClient == null) {
        Timber.w { "(${channelId}) DROP: No TCP control client for destination: $sender" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val client = backToClient.get()
      if (client == null || sender == client) {
        // We had no client so this traffic is our client sending -> destination
        // Or this is continuing traffic from the same sender
        backToClient.set(sender)

        // Validate that the IP ADDRESS of the client and sender are the same
        if (tcpControlClient.address != sender.address) {
          Timber.w {
            "(${channelId}) DROP: Sender did not match expected=${tcpControlClient.address} sender=${sender.address}"
          }
          sendErrorAndClose(ctx, msg)
          return
        }

        unwrapUdpResponse(ctx, msg)
      } else {
        val content = msg.retain().content()
        val response = UDP.wrap(alloc = ctx.alloc(), sender = sender, content = content)

        Timber.d { "Back to client: $client -> ${ByteBufUtil.hexDump(response)}" }
        val packet = DatagramPacket(response, client)
        ctx.writeAndFlush(packet).addListener { msg.release() }
      }
    } else {
      Timber.w { "(${channelId}): Invalid message seen: $msg" }
      super.channelRead(ctx, msg)
    }
  }
}
