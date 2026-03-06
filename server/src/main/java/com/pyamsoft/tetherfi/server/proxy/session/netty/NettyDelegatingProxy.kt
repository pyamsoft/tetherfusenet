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
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import io.netty.channel.socket.SocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.time.Clock

class NettyDelegatingProxy
internal constructor(
    private val clock: Clock,
    private val host: String,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
    private val isHttpEnabled: Boolean,
    private val isSocksEnabled: Boolean,
    private val serverSocketTimeout: ServerSocketTimeout,
    port: Int,
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

  override fun onChannelInitialized(channel: SocketChannel) {
    Timber.d { "Netty proxy server initialized!" }

    val pipeline = channel.pipeline()

    if (isDebug) {
      pipeline.addLast(LoggingHandler(LogLevel.DEBUG))
    }

    // And bind our proxy relay handler
    pipeline.addLast(
        ProtocolDelegatingHandler(
            clock = clock,
            serverHostName = host,
            isDebug = isDebug,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
            isHttpEnabled = isHttpEnabled,
            isSocksEnabled = isSocksEnabled,
            serverSocketTimeout = serverSocketTimeout,
        )
    )
  }
}
