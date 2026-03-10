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
import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.factory.NetworkBoundDatagramChannelFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.factory.NetworkBoundSocketChannelFactory
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoop
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

@CheckResult
private fun createOutboundChannel(
    isDebug: Boolean,
    channel: Channel,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    onChannelOpened: (Channel) -> Unit = {},
): Bootstrap {
  return Bootstrap()
      .group(channel.eventLoop())
      .channelFactory(
          NetworkBoundSocketChannelFactory(
              socketTagger = socketTagger,
              androidPreferredNetwork = androidPreferredNetwork,
          )
      )
      .handler(
          object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
              val pipeline = ch.pipeline()
              if (isDebug) {
                pipeline.addLast(LoggingHandler(LogLevel.DEBUG))
              }

              onChannelOpened(ch)
            }
          },
      )
}

@CheckResult
internal fun newOutboundConnection(
    isDebug: Boolean,
    channel: Channel,
    hostName: String,
    port: Int,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    onChannelOpened: (Channel) -> Unit = {},
): ChannelFuture {
  return createOutboundChannel(
          isDebug = isDebug,
          channel = channel,
          socketTagger = socketTagger,
          androidPreferredNetwork = androidPreferredNetwork,
          onChannelOpened = onChannelOpened,
      )
      .connect(hostName, port)
}

@CheckResult
private fun createOutboundDatagramChannel(
    isDebug: Boolean,
    eventLoop: EventLoop,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    onChannelOpened: (Channel) -> Unit = {},
): Bootstrap {
  return Bootstrap()
      .group(eventLoop)
      .channelFactory(
          NetworkBoundDatagramChannelFactory(
              socketTagger = socketTagger,
              androidPreferredNetwork = androidPreferredNetwork,
          )
      )
      .handler(
          object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
              val pipeline = ch.pipeline()
              if (isDebug) {
                pipeline.addLast(LoggingHandler(LogLevel.DEBUG))
              }

              onChannelOpened(ch)
            }
          }
      )
}

@CheckResult
internal fun newDatagramServer(
    isDebug: Boolean,
    eventLoop: EventLoop,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    hostName: String? = null,
    onChannelOpened: (Channel) -> Unit = {},
): ChannelFuture {
  return createOutboundDatagramChannel(
          isDebug = isDebug,
          eventLoop = eventLoop,
          socketTagger = socketTagger,
          androidPreferredNetwork = androidPreferredNetwork,
          onChannelOpened = onChannelOpened,
      )
      .run {
        if (hostName.isNullOrBlank()) {
          bind(0)
        } else {
          bind(hostName, 0)
        }
      }
}

@CheckResult
internal fun newDatagramServer(
    isDebug: Boolean,
    channel: Channel,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    hostName: String? = null,
    onChannelOpened: (Channel) -> Unit = {},
): ChannelFuture {
  return newDatagramServer(
      isDebug = isDebug,
      socketTagger = socketTagger,
      androidPreferredNetwork = androidPreferredNetwork,
      onChannelOpened = onChannelOpened,
      hostName = hostName,
      eventLoop = channel.eventLoop(),
  )
}

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
  val self = this
  if (evt is IdleStateEvent) {
    if (evt.state() == IdleState.ALL_IDLE) {
      Timber.d { "Closing idle connection: $self $evt" }
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

internal fun ChannelHandlerContext.flushAndClose() {
  val self = this
  self.channel().flushAndClose()
}
