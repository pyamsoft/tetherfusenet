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

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.TetherClient
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import kotlinx.coroutines.CoroutineScope

internal class RelayHandler
private constructor(
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    isDebug: Boolean,
) :
    ProxyHandler(
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
        isDebug = isDebug,
    ) {

  @CheckResult
  private fun getWritebackChannel(ctx: ChannelHandlerContext): Channel? {
    return ctx.channel().attr(WRITE_BACK_CHANNEL).get()
  }

  @CheckResult
  private fun getDirection(ctx: ChannelHandlerContext): Direction? {
    return ctx.channel().attr(DIRECTION).get()
  }

  @CheckResult
  private fun getChannelTag(ctx: ChannelHandlerContext): String? {
    return ctx.channel().attr(TAG).get()
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val tag = getChannelTag(ctx)
      if (tag == null) {
        val local = ctx.channel().localAddress()
        return@applyChannelId "RELAY-${local.address}:${local.port}"
      } else {
        return@applyChannelId tag
      }
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    ctx.flushAndClose()
    getWritebackChannel(ctx)?.flushAndClose()

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(WRITE_BACK_CHANNEL).set(null)
      attr(DIRECTION).set(null)
    }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    val channelId = getChannelId()

    // Can't do as this is a bytes based implementation
    Timber.w { "(${channelId}) Can't send generic error on RelayHandler" }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    ensureChannelTag(ctx)
    val channelId = getChannelId()

    val writeToChannel = getWritebackChannel(ctx)
    if (writeToChannel == null) {
      Timber.w { "($channelId): channelRead writeToChannel is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (!writeToChannel.isActive) {
      Timber.w { "($channelId): channelRead writeToChannel is not active" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (msg is ByteBuf) {
      val direction = getDirection(ctx)
      if (direction != null) {
        msg.readableBytes().toLong()
        // TODO Record amount consumed in direction
        //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
        // TODO bandwidth limit enforcement
        // TODO client scope sideeffect mark seen
      }
    }

    writeToChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      ensureChannelTag(ctx)
      val channelId = getChannelId()

      val writeToChannel = getWritebackChannel(ctx)
      if (writeToChannel == null) {
        Timber.w { "($channelId): channelWritabilityChanged writeToChannel is NULL" }
        return
      }

      val isWritable = ctx.channel().isWritable
      Timber.d { "($channelId) Relay write changed: $ctx $isWritable" }
      writeToChannel.config().isAutoRead = isWritable
    } finally {
      super.channelWritabilityChanged(ctx)
    }
  }

  internal enum class Direction {
    INBOUND,
    OUTBOUND,
  }

  companion object {

    @JvmStatic
    private val WRITE_BACK_CHANNEL: AttributeKey<Channel> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-WRITE_BACK_CHANNEL")

    @JvmStatic
    private val TAG: AttributeKey<String> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-ID")

    @JvmStatic
    private val DIRECTION: AttributeKey<Direction> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-DIRECTION")

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        RelayHandler(
            isDebug = isDebug,
            scope = scope,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        writeBackChannel: Channel,
        direction: Direction,
        tag: String,
        client: TetherClient,
    ) {
      channel.apply {
        attr(TAG).set(tag)
        attr(WRITE_BACK_CHANNEL).set(writeBackChannel)
        attr(DIRECTION).set(direction)
      }

      ProxyHandler.applyChannelAttributes(
          channel = channel,
          client = client,
      )
    }
  }
}
