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
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.TimeUnit

internal abstract class DefaultProxyHandler
internal constructor(
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    isDebug: Boolean,
    protected val serverSocketTimeout: ServerSocketTimeout,
) :
    ProxyHandler(
        socketTagger = socketTagger,
        androidPreferredNetwork = androidPreferredNetwork,
        isDebug = isDebug,
    ) {

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
        closeChannels(ctx)
      }
    }
  }

  final override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      onChannelWritabilityChanged(ctx)
    } finally {
      ctx.fireChannelWritabilityChanged()
    }
  }

  final override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      onChannelInactive(ctx)
    } finally {
      closeChannels(ctx)
    }
  }

  final override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      onExceptionCaught(ctx, cause)
    } finally {
      closeChannels(ctx)
    }
  }

  final override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    try {
      onChannelRead(ctx, msg)
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  protected open fun onChannelWritabilityChanged(ctx: ChannelHandlerContext) {
    val isWritable = ctx.channel().isWritable
    Timber.d { "Owner write changed: $ctx $isWritable" }
    setOutboundAutoRead(isWritable)
  }

  protected open fun onChannelInactive(ctx: ChannelHandlerContext) {
    Timber.d { "Close inactive outbound channels: $ctx" }
  }

  protected open fun onExceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    Timber.e(cause) { "ProxyServer exception caught $ctx" }
  }

  protected abstract fun onChannelRead(ctx: ChannelHandlerContext, msg: Any)
}
