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
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.factory.NetworkBoundDatagramChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.DatagramChannel

internal class UdpChannelCreator
internal constructor(
    eventLoop: EventLoopGroup,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
) :
    AbstractChannelCreator<DatagramChannel>(
        eventLoop = eventLoop,
        channelFactoryCreator = {
          NetworkBoundDatagramChannelFactory(
              socketTagger = socketTagger,
              androidPreferredNetwork = androidPreferredNetwork,
          )
        },
    )
