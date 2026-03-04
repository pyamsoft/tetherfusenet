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
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.NetworkBoundDatagramChannelFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.NetworkBoundSocketChannelFactory
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelInitializer
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

internal fun flushAndClose(channel: Channel) {
  if (channel.isActive) {
    channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
  }
}

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
    channel: Channel,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    onChannelOpened: (Channel) -> Unit = {},
): Bootstrap {
  return Bootstrap()
      .group(channel.eventLoop())
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
    channel: Channel,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    hostName: String? = null,
    onChannelOpened: (Channel) -> Unit = {},
): ChannelFuture {
  return createOutboundDatagramChannel(
          isDebug = isDebug,
          channel = channel,
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
