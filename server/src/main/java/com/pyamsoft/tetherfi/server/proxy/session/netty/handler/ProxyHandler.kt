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
import kotlinx.io.IOException

internal abstract class ProxyHandler
internal constructor(
    protected val isDebug: Boolean,
    protected val scope: CoroutineScope,
    protected val serverSocketTimeout: ServerSocketTimeout,
) : ChannelInboundHandlerAdapter() {

  @Volatile private var channelId = ""

  protected inline fun applyChannelId(block: () -> String) {
    if (channelId.isBlank()) {
      setChannelId(block())
    }
  }

  @CheckResult
  protected fun getChannelId(): String {
    if (channelId.isBlank()) {
      setChannelId("CHANNEL-UNKNOWN")
    }
    return channelId
  }

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
      val channelId = getChannelId()

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
      val channelId = getChannelId()
      var log = true

      // We don't care to log connection reset, they happen all the time
      if (cause is IOException) {
        if (cause.message == CONNECTION_RESET_MESSAGE) {
          log = false
        }
      }

      if (log) {
        Timber.e(cause) { "($channelId): Exception caught! Close channel" }
      }
    } finally {
      closeChannels(ctx)
    }
  }

  final override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      val channelId = getChannelId()

      Timber.d { "($channelId): Inactive! Close channel" }
      closeChannels(ctx)
    } finally {

      // Reset channel ID after inactive
      channelId = ""

      super.channelInactive(ctx)
    }
  }

  protected open fun onCloseChannels(ctx: ChannelHandlerContext) {}

  protected open fun onChannelActive(ctx: ChannelHandlerContext) {}

  protected abstract fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any)

  companion object {

    private const val CONNECTION_RESET_MESSAGE = "Connection reset by peer"

    @JvmStatic protected val VALID_PORT_RANGE = 1..<65535

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
