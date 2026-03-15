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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.ensure
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.util.AttributeKey
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope

internal class UdpRelayHandler
private constructor(
    isDebug: Boolean,
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    private val clientResolver: ClientResolver,
) :
    ProxyHandler(
        isDebug = isDebug,
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  @CheckResult
  private fun getBackToClientAddress(ctx: ChannelHandlerContext): InetSocketAddress? {
    return ctx.channel().attr(BACK_TO_CLIENT_ADDRESS).get()
  }

  private fun setBackToClientAddress(ctx: ChannelHandlerContext, address: InetSocketAddress) {
    return ctx.channel().attr(BACK_TO_CLIENT_ADDRESS).set(address)
  }

  @CheckResult
  private fun getChannelTag(ctx: ChannelHandlerContext): String? {
    return ctx.channel().attr(TAG).get()
  }

  private fun setChannelTag(ctx: ChannelHandlerContext, tag: String) {
    ctx.channel().attr(TAG).set(tag)
  }

  @CheckResult
  private fun getTcpControlAddress(ctx: ChannelHandlerContext): InetSocketAddress? {
    return ctx.channel().attr(TCP_CONTROL_ADDRESS).get()
  }

  private fun unwrapUdpResponse(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: DatagramPacket,
      sender: InetSocketAddress,
  ) {
    UDP.unwrap(
        channelId = channelId,
        ctx = ctx,
        msg = msg,
        onError = { sendErrorAndClose(ctx, msg) },
        onUnwrapped = { data, destination ->
          val tag = "UDP-RELAY-${destination.address}:${destination.port}"
          setChannelTag(ctx, tag)
          setChannelId(tag)

          val client = clientResolver.ensure(sender)
          // TODO Record amount consumed (without header)
          //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
          // TODO bandwidth limit enforcement
          // TODO client scope sideeffect mark seen

          val packet = DatagramPacket(data, destination)
          ctx.writeAndFlush(packet).addListener { packet.release() }
        },
    )
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val id = getChannelTag(ctx)
      if (id == null) {
        val local = ctx.channel().localAddress()
        return@applyChannelId "UDP-RELAY-${local.address}:${local.port}"
      }

      return@applyChannelId id
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    ctx.flushAndClose()

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(TCP_CONTROL_ADDRESS).set(null)
      attr(BACK_TO_CLIENT_ADDRESS).set(null)
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
    ensureChannelTag(ctx)
    val channelId = getChannelId()

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

      val tcpControlClient = getTcpControlAddress(ctx)
      if (tcpControlClient == null) {
        Timber.w { "(${channelId}) DROP: No TCP control client for destination: $sender" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val backToClient = getBackToClientAddress(ctx)
      if (backToClient == null || sender == backToClient) {
        // We had no client so this traffic is our client sending -> destination
        // Or this is continuing traffic from the same sender
        setBackToClientAddress(ctx, sender)

        // Validate that the IP ADDRESS of the client and sender are the same
        if (tcpControlClient.address != sender.address) {
          Timber.w {
            "(${channelId}) DROP: Sender did not match expected=${tcpControlClient.address} sender=${sender.address}"
          }
          sendErrorAndClose(ctx, msg)
          return
        }

        unwrapUdpResponse(ctx, channelId, msg, sender)
      } else {
        val content = msg.retain().content()
        val response = UDP.wrap(alloc = ctx.alloc(), sender = sender, content = content)

        val packet = DatagramPacket(response, backToClient)

        val client = clientResolver.ensure(backToClient)
        // TODO Record amount consumed (without header)
        //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
        // TODO bandwidth limit enforcement
        // TODO client scope sideeffect mark seen

        ctx.writeAndFlush(packet).addListener { msg.release() }
      }
    } else {
      Timber.w { "(${channelId}): Invalid message seen: $msg" }
      super.channelRead(ctx, msg)
    }
  }

  companion object {
    @JvmStatic
    private val TAG: AttributeKey<String> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-ID")

    @JvmStatic
    private val BACK_TO_CLIENT_ADDRESS: AttributeKey<InetSocketAddress> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-BACK_TO_CLIENT_ADDRESS")

    @JvmStatic
    private val TCP_CONTROL_ADDRESS: AttributeKey<InetSocketAddress> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-TCP_CONTROL_ADDRESS")

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        clientResolver: ClientResolver,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        UdpRelayHandler(
            isDebug = isDebug,
            scope = scope,
            clientResolver = clientResolver,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        tcpControlAddress: InetSocketAddress,
    ) {
      channel.apply { attr(TCP_CONTROL_ADDRESS).set(tcpControlAddress) }
    }
  }
}
