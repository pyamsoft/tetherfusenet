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
import com.pyamsoft.tetherfi.server.clients.ClientResolver
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
    clientResolver: ClientResolver,
    serverSocketTimeout: ServerSocketTimeout,
    isDebug: Boolean,
) :
    ProxyHandler(
        scope = scope,
        clientResolver = clientResolver,
        serverSocketTimeout = serverSocketTimeout,
        isDebug = isDebug,
    ) {

  @CheckResult
  private fun getWritebackChannel(ctx: ChannelHandlerContext): Channel? {
    return ctx.channel().attr(WRITE_BACK_CHANNEL).get()
  }

  @CheckResult
  private fun getChannelTag(ctx: ChannelHandlerContext): String? {
    return ctx.channel().attr(TAG).get()
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val tag = getChannelTag(ctx)
    if (tag == null) {
      val local = ctx.channel().localAddress()
      setChannelId("RELAY-${local.address}:${local.port}")
    } else {
      setChannelId(tag)
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    ctx.flushAndClose()
    getWritebackChannel(ctx)?.flushAndClose()

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(WRITE_BACK_CHANNEL).set(null)
      attr(CLIENT).set(null)
    }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Can't do as this is a bytes based implementation
    Timber.w { "(${channelId}) Can't send generic error on RelayHandler" }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
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
      msg.readableBytes().toLong()
      // TODO Record amount consumed
      //      Timber.d { "(${hostName}:${port}) Read $byteCount bytes" }
      // TODO bandwidth limit enforcement
    }

    writeToChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
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

  companion object {
    @JvmStatic
    private val CLIENT: AttributeKey<TetherClient> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-CLIENT")

    @JvmStatic
    private val WRITE_BACK_CHANNEL: AttributeKey<Channel> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-WRITE_BACK_CHANNEL")

    @JvmStatic
    private val TAG: AttributeKey<String> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-ID")

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        clientResolver: ClientResolver,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        RelayHandler(
            isDebug = isDebug,
            scope = scope,
            clientResolver = clientResolver,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        writeBackChannel: Channel,
        tag: String,
        client: TetherClient,
    ) {
      channel.apply {
        attr(TAG).set(tag)
        attr(WRITE_BACK_CHANNEL).set(writeBackChannel)
        attr(CLIENT).set(client)
      }
    }
  }
}
