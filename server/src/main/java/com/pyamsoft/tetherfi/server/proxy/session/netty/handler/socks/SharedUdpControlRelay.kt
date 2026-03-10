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

import android.net.Network
import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.factory.NetworkBoundDatagramChannelFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

internal class SharedUdpControlRelay<T : Any> internal constructor(
  private val socketTagger: SocketTagger,
  private val androidPreferredNetwork: Network?,
  private val handler: (SharedUdpControlRelay<T>, Channel) -> ProxyHandler,
) : AutoCloseable {

  private val clients = ConcurrentHashMap<InetSocketAddress, T>()

  var udpChannel: ChannelFuture? = null
    private set

  fun start(
    eventLoop: EventLoopGroup,
    serverHostName: String? = null,
  ) {
    val self = this
    val channel = udpChannel
    if (channel == null) {
      val server = Bootstrap().group(eventLoop).channelFactory(
        NetworkBoundDatagramChannelFactory(
          socketTagger = socketTagger,
          androidPreferredNetwork = androidPreferredNetwork,
        )
      )
        .handler(
          object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
              val p = ch.pipeline()
              p.addLast(handler(self, ch))
            }
          }
        )

      val channel = server.run {
        if (serverHostName.isNullOrBlank()) {
          bind(0)
        } else {
          bind(serverHostName, 0)
        }
      }.also { udpChannel = it }.channel()
      channel.closeFuture().addListener { close() }
    }
  }

  @CheckResult
  fun register(client: InetSocketAddress, data: T): () -> Unit {
    clients[client] = data
    return {
      clients.remove(client)
    }
  }

  @CheckResult
  fun getClient(client: InetSocketAddress): T? {
    return clients[client]
  }

  override fun close() {
    Timber.e(RuntimeException("HERE")) { "CLOSE SHARED UDP $udpChannel" }
    val future = udpChannel
    udpChannel = null

    // TODO clear and clean up clients

    future?.channel()?.flushAndClose()
  }
}