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
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import kotlinx.coroutines.CoroutineScope

internal abstract class ProxyHandler
internal constructor(
    protected val isDebug: Boolean,
    protected val scope: CoroutineScope,
    protected val serverSocketTimeout: ServerSocketTimeout,
) : ChannelInboundHandlerAdapter() {

  protected var channelId = "CHANNEL-UNKNOWN"
    private set

  protected fun setChannelId(id: String) {
    channelId = id
  }

  protected fun closeChannels(ctx: ChannelHandlerContext) {
    onCloseChannels(ctx)

    val channel = ctx.channel()
    channel.apply { attr(CLIENT).set(null) }

    if (channel.isOpen) {
      channel.flushAndClose()
    }
  }

  @CheckResult
  protected fun getTetherClient(ctx: ChannelHandlerContext): TetherClient? {
    return ctx.channel().attr(CLIENT).get()
  }

  final override fun channelActive(ctx: ChannelHandlerContext) {
    try {
      onChannelActive(ctx)
      ctx.attachIdleStateHandler(serverSocketTimeout)
    } finally {
      super.channelActive(ctx)
    }
  }

  final override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    try {
      ctx.handleIdleState(evt) {
        Timber.d { "(${channelId}): Close channel after idle timeout" }
        closeChannels(ctx)
      }
    } finally {
      super.userEventTriggered(ctx, evt)
    }
  }

  final override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      Timber.e(cause) { "($channelId): Exception caught! Close channel" }
      closeChannels(ctx)
    } finally {
      super.exceptionCaught(ctx, cause)
    }
  }

  final override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      Timber.d { "($channelId): Inactive! Close channel" }
      closeChannels(ctx)
    } finally {

      // Reset channel ID after inactive
      setChannelId("")

      super.channelInactive(ctx)
    }
  }

  protected open fun onCloseChannels(ctx: ChannelHandlerContext) {}

  protected open fun onChannelActive(ctx: ChannelHandlerContext) {}

  protected abstract fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any)

  companion object {

    @JvmStatic protected val VALID_PORT_RANGE = 1..<65335

    @JvmStatic
    private val CLIENT: AttributeKey<TetherClient> =
        AttributeKey.newInstance("${ProxyHandler::class.simpleName}-CLIENT")

    @JvmStatic
    protected fun applyChannelAttributes(
        channel: Channel,
        client: TetherClient,
    ) {
      channel.apply { attr(CLIENT).set(client) }
    }
  }
}