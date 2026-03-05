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

import android.net.ConnectivityManager
import android.net.Network
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks4ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

class NettyDelegatingProxy
internal constructor(
    private val host: String,
    private val port: Int,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val androidConnectivityManager: ConnectivityManager,
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

  override fun onChannelInitialized(channel: SocketChannel) {
    Timber.d { "Netty proxy server initialized!" }

    val pipeline = channel.pipeline()

    if (isDebug) {
      pipeline.addLast(LoggingHandler(LogLevel.DEBUG))
    }

    // And bind our proxy relay handler
    pipeline.addLast(
        DelegatingHandler(
            serverHostName = host,
            serverPort = port,
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

private class DelegatingHandler(
    private val serverHostName: String,
    private val serverPort: Int,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
    private val isHttpEnabled: Boolean,
    private val isSocksEnabled: Boolean,
    private val serverSocketTimeout: ServerSocketTimeout,
) : ByteToMessageDecoder() {

  override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: List<Any>) {
    if (!input.isReadable) {
      Timber.w { "DROP: Unreadable input buffer sent." }
      return
    }

    // Copied from SocksPortUnificationServerHandler.java
    val readerIndex = input.readerIndex()
    val writerIndex = input.writerIndex()
    if (writerIndex == readerIndex) {
      Timber.w { "DROP: Bad input writer index saw=$writerIndex expect=$readerIndex" }
      return
    }

    val pipeline = ctx.pipeline()
    val versionVal = input.getByte(readerIndex)
    val socksVersion = SocksVersion.valueOf(versionVal)

    try {
      when (socksVersion) {
        SocksVersion.SOCKS4a -> {
          if (!isSocksEnabled) {
            Timber.w { "DROP: SOCKS4a traffic received but SOCKS was not enabled" }
            return
          }

          // Assume SOCKS4
          pipeline.addLast(Socks4ServerEncoder.INSTANCE)
          pipeline.addLast(Socks4ServerDecoder())

          pipeline.addLast(
              Socks4ProxyHandler(
                  isDebug = isDebug,
                  socketTagger = socketTagger,
                  androidPreferredNetwork = androidPreferredNetwork,
                  serverSocketTimeout = serverSocketTimeout,
              )
          )
        }

        SocksVersion.SOCKS5 -> {
          if (!isSocksEnabled) {
            Timber.w { "DROP: SOCKS5 traffic received but SOCKS was not enabled" }
            return
          }

          // Assume SOCKS5
          pipeline.addLast(Socks5ServerEncoder.DEFAULT)
          pipeline.addLast(Socks5InitialRequestDecoder())
          pipeline.addLast(Socks5CommandRequestDecoder())

          pipeline.addLast(
              Socks5ProxyHandler(
                  serverHostName = serverHostName,
                  isDebug = isDebug,
                  socketTagger = socketTagger,
                  androidPreferredNetwork = androidPreferredNetwork,
                  serverSocketTimeout = serverSocketTimeout,
              )
          )
        }

        else -> {
          if (!isHttpEnabled) {
            Timber.w { "DROP: HTTP traffic received but HTTP was not enabled" }
            return
          }

          // Assume HTTP
          pipeline.addLast(HttpServerCodec())

          // And bind our proxy relay handler
          pipeline.addLast(
              Http1ProxyHandler(
                  isDebug = isDebug,
                  socketTagger = socketTagger,
                  androidPreferredNetwork = androidPreferredNetwork,
                  serverSocketTimeout = serverSocketTimeout,
              )
          )
        }
      }
    } finally {
      pipeline.dropHandler(this::class)
    }
  }
}
