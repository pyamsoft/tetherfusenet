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
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

internal class UdpRelayUpstreamHandler
internal constructor(
    private val udpControlChannel: Channel,
    private val client: InetSocketAddress,
    private val serverSocketTimeout: ServerSocketTimeout,
) : ChannelInboundHandlerAdapter() {

  private var id: String = "UDP-UPSTREAM-UNKNOWN"

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
      Timber.w { "Remote UDP packet had NULL sender. Drop!" }
      return
    }

    val content = msg.retain().content()
    val response = wrapUdpResponse(alloc = ctx.alloc(), sender = sender, content = content)
    udpControlChannel.writeAndFlush(DatagramPacket(response, client)).addListener { msg.release() }
  }

  private fun closeChannels(ctx: ChannelHandlerContext) {
    if (udpControlChannel.isActive) {
      Timber.d { "close control channel $udpControlChannel" }
      flushAndClose(udpControlChannel)
    }

    val channel = ctx.channel()
    if (channel.isActive) {
      Timber.d { "close owner channel $channel" }
      flushAndClose(channel)
    }
  }

  override fun channelRegistered(ctx: ChannelHandlerContext) {
    val timeout = serverSocketTimeout.timeoutDuration
    if (timeout.isInfinite()) {
      Timber.d { "Not adding idle timeout, infinite timeout configured!" }
    } else {
      Timber.d { "Add idle timeout handler $timeout" }
      ctx.pipeline()
          .addFirst(IdleStateHandler(0, 0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    }
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    if (evt is IdleStateEvent) {
      if (evt.state() == IdleState.ALL_IDLE) {
        Timber.d { "Closing idle connection: $ctx $evt" }
        closeChannels(ctx)
      }
    }
  }

  override fun channelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    id = "UDP-UPSTREAM-${addr.address}:${addr.port}"
    Timber.d { "Active outbound UDP channel $id" }

    // Close UDP relay when control socket closes
    udpControlChannel.closeFuture().addListener {
      Timber.d { "Closing UDP upstream relay because UDP control closed" }
      closeChannels(ctx)
    }
  }

  override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      Timber.d { "($id) Close inactive relay channel: $ctx" }
    } finally {
      closeChannels(ctx)
    }
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      Timber.e(cause) { "($id) exception caught $ctx" }
    } finally {
      closeChannels(ctx)
    }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is DatagramPacket) {
      handleReply(ctx, msg)
    } else {
      Timber.w { "Invalid message received: $msg" }
    }
  }
}
