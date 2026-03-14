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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler

import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks4ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import kotlinx.coroutines.CoroutineScope

internal class ProtocolDelegatingHandler
internal constructor(
    // IF this is NULL, SOCKS is not enabled
    private val udpSocketCreator: ChannelCreator?,
    private val tcpSocketCreator: ChannelCreator,
    private val isHttpEnabled: Boolean,
    private val serverSocketTimeout: ServerSocketTimeout,
    private val clientResolver: ClientResolver,
    private val scope: CoroutineScope,
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
          if (udpSocketCreator == null) {
            Timber.w { "DROP: SOCKS4a traffic received but SOCKS was not enabled" }
            return
          }

          // Assume SOCKS4
          pipeline.addLast(Socks4ServerEncoder.INSTANCE)
          pipeline.addLast(Socks4ServerDecoder())

          pipeline.addLast(
              Socks4ProxyHandler(
                  scope = scope,
                  clientResolver = clientResolver,
                  tcpSocketCreator = tcpSocketCreator,
                  serverSocketTimeout = serverSocketTimeout,
              )
          )
        }

        SocksVersion.SOCKS5 -> {
          val udpControl = udpSocketCreator
          if (udpControl == null) {
            Timber.w { "DROP: SOCKS5 traffic received but SOCKS was not enabled" }
            return
          }

          // Assume SOCKS5
          pipeline.addLast(Socks5ServerEncoder.DEFAULT)
          pipeline.addLast(Socks5InitialRequestDecoder())
          pipeline.addLast(Socks5CommandRequestDecoder())

          pipeline.addLast(
              Socks5ProxyHandler(
                  scope = scope,
                  clientResolver = clientResolver,
                  udpSocketCreator = udpControl,
                  tcpSocketCreator = tcpSocketCreator,
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
                  scope = scope,
                  clientResolver = clientResolver,
                  tcpSocketCreator = tcpSocketCreator,
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
