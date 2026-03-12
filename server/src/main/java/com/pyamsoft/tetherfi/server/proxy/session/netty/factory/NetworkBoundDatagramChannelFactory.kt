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
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel as JavaDatagramChannel

internal class NetworkBoundDatagramChannelFactory
internal constructor(
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
) : ChannelFactory<DatagramChannel> {

  override fun newChannel(): DatagramChannel {
    socketTagger.tagSocket()

    val outboundSocketChannel =
        JavaDatagramChannel.open().apply {
          configureBlocking(false)

          setOption(StandardSocketOptions.SO_REUSEADDR, true)
        }

    val socket = outboundSocketChannel.socket()
    androidPreferredNetwork?.bindSocket(socket)

    return NioDatagramChannel(outboundSocketChannel)
  }
}
