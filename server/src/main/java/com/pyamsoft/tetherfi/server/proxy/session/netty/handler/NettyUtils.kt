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
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

internal fun ChannelHandlerContext.attachIdleStateHandler(
    serverSocketTimeout: ServerSocketTimeout
) {
  val self = this
  val timeout = serverSocketTimeout.timeoutDuration
  if (timeout.isInfinite()) {
    Timber.d { "Not adding idle timeout, infinite timeout configured!" }
  } else {
    Timber.d { "Add idle timeout handler $timeout" }
    self
        .pipeline()
        .addFirst(IdleStateHandler(0, 0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
  }
}

internal inline fun ChannelHandlerContext.handleIdleState(
    evt: Any,
    block: () -> Unit,
) {
  if (evt is IdleStateEvent) {
    if (evt.state() == IdleState.ALL_IDLE) {
      block()
    }
  }
}

internal fun <T : ChannelHandler> ChannelPipeline.dropHandler(c: KClass<T>) {
  val self = this
  val javaClass = c.java
  if (self.get(javaClass) != null) {
    self.remove(javaClass)
  }
}

internal fun Channel.flushAndClose() {
  val self = this
  if (self.isOpen) {
    self.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
  }
}

internal fun ChannelFuture.flushAndClose() {
  val self = this
  self.channel().flushAndClose()
}

internal fun ChannelHandlerContext.flushAndClose() {
  val self = this
  self.channel().flushAndClose()
}
