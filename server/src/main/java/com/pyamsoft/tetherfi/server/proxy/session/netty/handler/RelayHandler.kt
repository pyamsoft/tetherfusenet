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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler

import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit

internal class RelayHandler
internal constructor(
    private val id: String,
    private val clientChannel: Channel,
    private val serverSocketTimeout: ServerSocketTimeout,
) : ChannelInboundHandlerAdapter() {

  override fun channelRegistered(ctx: ChannelHandlerContext) {
    val timeout = serverSocketTimeout.timeoutDuration
    if (timeout.isInfinite()) {
      Timber.d { "Add idle timeout handler $timeout" }
      ctx.pipeline()
          .addFirst(IdleStateHandler(0, 0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    } else {
      Timber.d { "Not adding idle timeout, infinite timeout configured!" }
    }
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    if (evt is IdleStateEvent) {
      if (evt.state() == IdleState.ALL_IDLE) {
        Timber.d { "Closing idle connection: $ctx $evt" }
        flushAndClose(ctx.channel())
      }
    }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    Timber.d { "READ: $msg" }
    if (!clientChannel.isActive) {
      return
    }

    if (msg is ByteBuf) {
      msg.readableBytes().toLong()
      // TODO Record amount consumed
      //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
      // TODO bandwidth limit enforcement
    }

    clientChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      val isWritable = ctx.channel().isWritable
      Timber.d { "($id) Relay write changed: $ctx $isWritable" }
      clientChannel.config().isAutoRead = isWritable
    } finally {
      ctx.fireChannelWritabilityChanged()
    }
  }

  override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      Timber.d { "($id) Close inactive relay channel: $ctx" }
    } finally {
      flushAndClose(ctx.channel())
      flushAndClose(clientChannel)
    }
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      Timber.e(cause) { "($id) RelayChannel exception caught $ctx" }
    } finally {
      flushAndClose(ctx.channel())
    }
  }
}
