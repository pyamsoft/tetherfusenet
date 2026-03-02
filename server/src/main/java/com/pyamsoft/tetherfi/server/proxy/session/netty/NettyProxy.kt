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

package com.pyamsoft.tetherfi.server.proxy.session.netty

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

abstract class NettyProxy
protected constructor(
    private val socketTagger: SocketTagger,
    private val host: String,
    private val port: Int,
    private val onOpened: () -> Unit,
    private val onClosing: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {

  @CheckResult
  fun start(): NettyServerStopper {
    // The boss group usually does not need more than a single thread allocated to it
    val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

    val bootstrap =
        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(
                object : io.netty.channel.ChannelInitializer<SocketChannel>() {
                  override fun initChannel(ch: SocketChannel) {
                    onChannelInitialized(ch)
                  }
                }
            )

    // Tag the server socket
    socketTagger.tagSocket()

    val serverChannel =
        bootstrap
            .bind(host, port)
            .apply {
              addListener { future ->
                if (future.isSuccess) {
                  Timber.d { "Netty server started" }
                  onOpened()
                } else {
                  val err = future.cause()
                  Timber.e(err) { "Failed to bind netty server" }
                  onError(err)
                }
              }
            }
            .channel()
            .apply {
              closeFuture().addListener {
                Timber.d { "Netty server is closing!" }
                onClosing()

                Timber.d { "Shutdown thread pools" }
                bossGroup.shutdownGracefully()
                workerGroup.shutdownGracefully()
              }
            }

    return {
      Timber.d { "Stopping Netty server gracefully" }
      serverChannel.close()
    }
  }

  protected abstract fun onChannelInitialized(channel: SocketChannel)
}
