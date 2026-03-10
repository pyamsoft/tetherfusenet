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

internal class RelayHandler
internal constructor(
    serverSocketTimeout: ServerSocketTimeout,
    id: String,
    private val writeToChannel: Channel,
) :
    DefaultProxyHandler(
        serverSocketTimeout = serverSocketTimeout,
    ) {

  init {
    setChannelId(id)
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {}

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Can't do as this is a bytes based implementation
    Timber.w { "(${channelId}) Can't send generic error on RelayHandler" }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (!writeToChannel.isActive) {
      return
    }

    if (msg is ByteBuf) {
      msg.readableBytes().toLong()
      // TODO Record amount consumed
      //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
      // TODO bandwidth limit enforcement
    }

    writeToChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      val isWritable = ctx.channel().isWritable
      Timber.d { "($channelId) Relay write changed: $ctx $isWritable" }
      writeToChannel.config().isAutoRead = isWritable
    } finally {
      super.channelWritabilityChanged(ctx)
    }
  }
}
