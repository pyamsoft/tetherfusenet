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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks

import androidx.annotation.CheckResult
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup

internal abstract class AbstractChannelCreator<T : Channel>
internal constructor(
    private val eventLoop: EventLoopGroup,
    private val channelFactoryCreator: () -> ChannelFactory<T>,
) : ChannelCreator {

  private val channelFactory by lazy { channelFactoryCreator() }

  @CheckResult
  private fun doBind(hostName: String?, onChannelInitialized: (Channel) -> Unit): ChannelFuture {
    val server =
        Bootstrap()
            .group(eventLoop)
            .channelFactory(channelFactory)
            .handler(
                object : ChannelInitializer<Channel>() {
                  override fun initChannel(ch: Channel) {
                    onChannelInitialized(ch)
                  }
                }
            )

    return server.run {
      if (hostName.isNullOrBlank()) {
        bind(0)
      } else {
        bind(hostName, 0)
      }
    }
  }

  override fun bind(onChannelInitialized: (Channel) -> Unit): ChannelFuture {
    return doBind(hostName = null, onChannelInitialized = onChannelInitialized)
  }

  override fun bind(hostName: String, onChannelInitialized: (Channel) -> Unit): ChannelFuture {
    return doBind(hostName = hostName, onChannelInitialized = onChannelInitialized)
  }
}
