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

import android.net.Network
import androidx.annotation.CallSuper
import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

internal abstract class ProxyHandler
internal constructor(
    protected val isDebug: Boolean,
    protected val socketTagger: SocketTagger,
    protected val androidPreferredNetwork: Network?,
) : ChannelInboundHandlerAdapter() {

  private val messageQueue = MutableStateFlow<List<Any>>(emptyList())

  private var outboundChannel: Channel? = null

  protected fun assignOutboundChannel(channel: Channel) {
    outboundChannel?.let { old ->
      Timber.d { "Re-assigning outbound channel $old -> $channel" }
      if (old.isActive) {
        Timber.d { "Close old outbound channel $old" }
        flushAndClose(old)
      }
    }

    outboundChannel = channel
  }

  protected open fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    val response = createErrorResponse(msg)
    if (response != null) {
      ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
    } else {
      closeChannels(ctx)
    }
  }

  @CallSuper
  protected open fun closeChannels(ctx: ChannelHandlerContext) {
    Timber.d { "Clear pending message queue" }
    messageQueue.update { emptyList() }

    outboundChannel?.also { outbound ->
      if (outbound.isActive) {
        Timber.d { "close outbound channel $outbound" }
        flushAndClose(outbound)
      }
    }

    val channel = ctx.channel()
    if (channel.isActive) {
      Timber.d { "close owner channel $channel" }
      flushAndClose(channel)
    }
  }

  protected fun setOutboundAutoRead(isAutoRead: Boolean) {
    outboundChannel?.config()?.isAutoRead = isAutoRead
  }

  protected fun queueOrDeliverOutboundMessage(msg: Any) {
    val outbound = outboundChannel
    if (outbound == null) {
      messageQueue.update { it + msg }
    } else {
      outbound.writeAndFlush(msg)
    }
  }

  protected fun replayQueuedMessages(channel: Channel) {
    var needsFlush = false
    try {
      val queued = messageQueue.getAndUpdate { emptyList() }
      needsFlush = queued.isNotEmpty()
      if (needsFlush) {
        for (q in queued) {
          Timber.d { "RELAY: $q" }
          channel.write(q).addListener {
            Timber.d { "RELAY OUTBOUND: ${it.isSuccess} ${it.cause()}" }
          }
        }
      }
    } finally {
      if (needsFlush) {
        channel.flush()
      }
    }
  }

  /** Return NULL from this function to NOT SEND a response */
  @CheckResult protected abstract fun createErrorResponse(msg: Any): Any?
}
