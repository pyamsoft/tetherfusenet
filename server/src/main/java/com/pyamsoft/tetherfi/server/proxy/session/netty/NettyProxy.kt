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
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.concurrent.Future
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet

abstract class NettyProxy
protected constructor(
    private val socketTagger: SocketTagger,
    private val host: String,
    private val port: Int,
    private val onOpened: () -> Unit,
    private val onClosing: () -> Unit,
    private val onClosed: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {

  private fun proxyDead() {
    Timber.d { "Netty is completely shutdown!" }
    onClosed()
  }

  private fun waitForDeath(
      future: Future<*>,
      thisState: MutableStateFlow<Boolean>,
      otherState: StateFlow<Boolean>,
  ) {
    future.addListener { f ->
      if (f.isDone) {
        val dead = thisState.updateAndGet { true }
        if (dead && otherState.value) {
          proxyDead()
        }
      }
    }
  }

  private fun serverStarted(scope: CoroutineScope, channel: Channel, workerGroup: EventLoopGroup) {
    onServerStarted(scope, channel, workerGroup)

    onOpened()
  }

  private fun serverStopped() {
    onClosing()

    onServerStopped()
  }

  private fun channelInitialized(channel: SocketChannel) {
    onChannelInitialized(channel)
  }

  @CheckResult
  fun start(): NettyServerStopper {
    // The boss group usually does not need more than a single thread allocated to it
    val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

    val bootstrap =
        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.AUTO_CLOSE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(
                object : ChannelInitializer<SocketChannel>() {
                  override fun initChannel(ch: SocketChannel) {
                    channelInitialized(ch)
                  }
                }
            )

    // Tag the server socket
    socketTagger.tagSocket()

    val server = bootstrap.bind(host, port)
    val channel = server.channel()

    val scope =
        CoroutineScope(
            context = SupervisorJob() + Dispatchers.IO + CoroutineName(this::class.java.name)
        )

    channel.closeFuture().addListener {
      Timber.d { "Netty server is closing!" }
      serverStopped()

      val bossDead = MutableStateFlow(false)
      val workerDead = MutableStateFlow(false)

      // Wait for pools to actually be dead, not just "starting shutdown"
      waitForDeath(bossGroup.terminationFuture(), bossDead, workerDead)
      waitForDeath(workerGroup.terminationFuture(), workerDead, bossDead)

      Timber.d { "Shutdown thread pools" }
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()

      Timber.d { "Closing server CoroutineScope" }
      scope.cancel()
    }

    server.apply {
      addListener { future ->
        if (future.isSuccess) {
          Timber.d { "Netty server started" }
          serverStarted(scope, channel, workerGroup)
        } else {
          val err = future.cause()
          Timber.e(err) { "Failed to bind netty server" }
          onError(err)

          if (channel.isOpen) {
            channel.close()
          }
        }
      }
    }

    return {
      if (channel.isOpen) {
        Timber.d { "Stopping Netty server gracefully" }
        channel.close()
      }
    }
  }

  protected open fun onServerStarted(
      scope: CoroutineScope,
      channel: Channel,
      workerGroup: EventLoopGroup,
  ) {}

  protected open fun onServerStopped() {}

  protected open fun onChannelInitialized(channel: SocketChannel) {}
}
