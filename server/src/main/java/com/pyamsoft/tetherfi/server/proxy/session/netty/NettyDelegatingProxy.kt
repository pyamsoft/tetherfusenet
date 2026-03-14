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

import android.net.Network
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProtocolDelegatingHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.TcpChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.UdpChannelCreator
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import kotlinx.coroutines.CoroutineScope

class NettyDelegatingProxy
internal constructor(
    host: String,
    port: Int,
    private val blockedClients: BlockedClients,
    private val allowedClients: AllowedClients,
    private val clientResolver: ClientResolver,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
    private val isHttpEnabled: Boolean,
    private val isSocksEnabled: Boolean,
    private val serverSocketTimeout: ServerSocketTimeout,
    onOpened: () -> Unit,
    onClosing: () -> Unit,
    onClosed: () -> Unit,
    onError: (Throwable) -> Unit,
) :
    NettyProxy(
        socketTagger = socketTagger,
        host = host,
        port = port,
        onOpened = onOpened,
        onClosing = onClosing,
        onClosed = onClosed,
        onError = onError,
    ) {

  private var udpSocketCreator: ChannelCreator? = null
  private var tcpSocketCreator: ChannelCreator? = null

  private val delegatingHandlerFactory =
      ProtocolDelegatingHandler.factory(
          isHttpEnabled = isHttpEnabled,
          serverSocketTimeout = serverSocketTimeout,
          clientResolver = clientResolver,
          isDebug = isDebug,
      )

  override fun onServerStarted(
      scope: CoroutineScope,
      channel: Channel,
      workerGroup: EventLoopGroup,
  ) {
    Timber.d { "Netty proxy server initialized!" }

    if (isSocksEnabled) {
      udpSocketCreator =
          UdpChannelCreator(
              eventLoop = workerGroup,
              socketTagger = socketTagger,
              androidPreferredNetwork = androidPreferredNetwork,
          )
    }

    tcpSocketCreator =
        TcpChannelCreator(
            eventLoop = workerGroup,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
        )
  }

  override fun onServerStopped() {
    udpSocketCreator = null
    tcpSocketCreator = null
  }

  override fun onChannelInitialized(
      scope: CoroutineScope,
      channel: SocketChannel,
  ) {
    val pipeline = channel.pipeline()

    if (isDebug) {
      pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
    }

    // And bind our proxy relay handler
    pipeline.addLast(
        delegatingHandlerFactory.create(
            ProtocolDelegatingHandler.Params(
                scope = scope,

                // This will be resolved in time I am pretty sure :)
                tcp = tcpSocketCreator.requireNotNull(),

                // If this is NULL, then SOCKS is disabled.
                // If non-null SOCKS is enabled
                udp = udpSocketCreator,
            ),
        )
    )
  }
}
