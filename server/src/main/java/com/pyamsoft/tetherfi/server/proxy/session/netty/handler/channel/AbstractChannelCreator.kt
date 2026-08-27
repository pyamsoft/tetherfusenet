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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.resolveDnsAddress
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal abstract class AbstractChannelCreator<T : Channel>
internal constructor(
    private val eventLoop: EventLoopGroup,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val channelFactoryCreator: () -> ChannelFactory<T>,
) : ChannelCreator {

  private val channelFactory by lazy { channelFactoryCreator() }

  @CheckResult
  private fun bootstrap(onChannelInitialized: (Channel) -> Unit): Bootstrap {
    return Bootstrap()
        .group(eventLoop)
        .channelFactory(channelFactory)
        .handler(
            object : ChannelInitializer<Channel>() {
              override fun initChannel(ch: Channel) {
                onChannelInitialized(ch)
              }
            }
        )
  }

  override fun bind(onChannelInitialized: (Channel) -> Unit): ChannelFuture {
    return bootstrap(onChannelInitialized).bind(0)
  }

  override fun connect(
      hostName: String,
      port: Int,
      onChannelInitialized: (Channel) -> Unit,
  ): ChannelFuture {
    // We must bootstrap first and then manually field a DNS request
    // otherwise netty's default resolver group runs on the current thread and blocks
    val registerFuture = bootstrap(onChannelInitialized).register()
    val bootstrapChannel = registerFuture.channel()

    return bootstrapChannel.newPromise().also { promise ->
      registerFuture.addListener {
        if (!registerFuture.isSuccess) {
          promise.setFailure(registerFuture.cause())
          bootstrapChannel.close()
          return@addListener
        }

        // Branch off to IO
        scope.launch(context = dispatchers.io) {
          // Resolve in the IO branch (blocking)
          val resolved =
              bootstrapChannel.resolveDnsAddress(
                  dispatchers = dispatchers,
                  hostName = hostName,
                  port = port,
              )

          // Hop back onto the channel's event loop before touching the channel
          val eventLoop = bootstrapChannel.eventLoop()
          eventLoop.execute {
            if (resolved == null) {
              promise.setFailure(UnknownHostException(hostName))
              bootstrapChannel.close()
              return@execute
            }

            bootstrapChannel.connect(resolved).addListener { connectFuture ->
              if (connectFuture.isSuccess) {
                promise.setSuccess()
              } else {
                promise.setFailure(connectFuture.cause())
                bootstrapChannel.close()
              }
            }
          }
        }
      }
    }
  }
}
