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

import android.net.Network
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.factory.NetworkBoundSocketChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.CoroutineScope

internal class TcpChannelCreator
internal constructor(
    eventLoop: EventLoopGroup,
    scope: CoroutineScope,
    dispatchers: AppDispatchers,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
) :
    AbstractChannelCreator<SocketChannel>(
        eventLoop = eventLoop,
        scope = scope,
        dispatchers = dispatchers,
        channelFactoryCreator = {
          NetworkBoundSocketChannelFactory(
              socketTagger = socketTagger,
              androidPreferredNetwork = androidPreferredNetwork,
          )
        },
    )
