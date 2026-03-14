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

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.ensure
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope

internal abstract class SocksProxyHandler<T : SocksMessage>
internal constructor(
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    clientResolver: ClientResolver,
    isDebug: Boolean,
    private val tcpSocketCreator: ChannelCreator,
) :
    ProxyHandler(
        scope = scope,
        clientResolver = clientResolver,
        serverSocketTimeout = serverSocketTimeout,
        isDebug = isDebug,
    ) {

  private val relayHandlerFactory =
      RelayHandler.factory(
          isDebug = isDebug,
          scope = scope,
          clientResolver = clientResolver,
          serverSocketTimeout = serverSocketTimeout,
      )

  protected fun handleSocksConnectRequest(ctx: ChannelHandlerContext, msg: T) {
    if (!isConnectMessageType(msg)) {
      sendErrorAndClose(ctx, msg)
      return
    }

    when (msg) {
      is Socks4CommandRequest -> {
        performSocksConnectRequest(ctx, msg, msg.dstAddr(), msg.dstPort())
      }

      is Socks5CommandRequest -> {
        performSocksConnectRequest(ctx, msg, msg.dstAddr(), msg.dstPort())
      }

      else -> {
        Timber.w {
          "Invalid MSG interface type $msg. Expected Socks4CommandRequest Socks5CommandRequest"
        }
        sendErrorAndClose(ctx, msg)
      }
    }
  }

  private fun performSocksConnectRequest(
      ctx: ChannelHandlerContext,
      msg: T,
      dstAddr: String?,
      dstPort: Int,
  ) {
    val tag = "${msg.version()}-CONNECT"
    if (dstAddr.isNullOrBlank()) {
      Timber.w { "(${channelId}) DROP: $tag Invalid upstream destination address: $dstAddr" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (dstPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: $tag Invalid upstream destination port: $dstPort" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val serverChannel = ctx.channel()
    val remoteClient = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (remoteClient == null) {
      Timber.w { "($channelId) DROP: $tag remoteClient IP is NULL" }
      return
    }

    val client = clientResolver.ensure(remoteClient)

    val connectSocket =
        tcpSocketCreator.connect(
            hostName = dstAddr,
            port = dstPort,
            onChannelInitialized = { ch ->
              val pipeline = ch.pipeline()

              if (isDebug) {
                pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
              }

              // Read from the REMOTE and send back to the PROXY
              pipeline.addLast(relayHandlerFactory.create(Unit))
            },
        )

    val outbound = connectSocket.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }
    outbound.closeFuture().addListener { serverChannel.flushAndClose() }

    RelayHandler.applyChannelAttributes(
        channel = outbound,
        writeBackChannel = serverChannel,
        tag = "$tag-INBOUND-${dstAddr}:${dstPort}",
        client = client,
    )

    connectSocket.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "$tag proxied outbound failed" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      RelayHandler.applyChannelAttributes(
          channel = serverChannel,
          writeBackChannel = outbound,
          tag = "$tag-OUTBOUND-${dstAddr}:${dstPort}",
          client = client,
      )

      // Tell proxy we've established connection
      publishConnectSuccess(tag, ctx, msg, outbound)

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      dropSocksHandlers(pipeline)

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Add a relay for the internet outbound
      pipeline.addLast(relayHandlerFactory.create(Unit))
    }
  }

  @CheckResult protected abstract fun isConnectMessageType(msg: T): Boolean

  protected abstract fun publishConnectSuccess(
      tag: String,
      ctx: ChannelHandlerContext,
      msg: T,
      outbound: Channel,
  )

  protected abstract fun dropSocksHandlers(pipeline: ChannelPipeline)

  protected abstract fun sendFailureAndClose(ctx: ChannelHandlerContext, msg: T)
}
