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

package com.pyamsoft.tetherfi.server.proxy.session.netty.factory

import android.net.Network
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import io.netty.channel.ChannelFactory
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel as JavaSocketChannel

internal class NetworkBoundSocketChannelFactory
internal constructor(
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
) : ChannelFactory<SocketChannel> {

  override fun newChannel(): SocketChannel {
    socketTagger.tagSocket()

    val outboundSocketChannel =
        JavaSocketChannel.open().apply {
          configureBlocking(false)

          setOption(StandardSocketOptions.TCP_NODELAY, true)
          setOption(StandardSocketOptions.SO_REUSEADDR, true)
        }

    val socket = outboundSocketChannel.socket()
    androidPreferredNetwork?.bindSocket(socket)

    return NioSocketChannel(outboundSocketChannel)
  }
}
