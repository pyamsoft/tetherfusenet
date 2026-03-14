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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CoroutineScope

// Cannot be shareable because of the local state messageQueue and outboundChannel
internal class Http1ProxyHandler
internal constructor(
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    clientResolver: ClientResolver,
    private val tcpSocketCreator: ChannelCreator,
) :
    ProxyHandler(
        scope = scope,
        clientResolver = clientResolver,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private val messageQueue = mutableListOf<Any>()

  private var outboundChannel: Channel? = null

  private fun assignOutboundChannel(channel: Channel) {
    outboundChannel?.let { old ->
      Timber.d { "Re-assigning outbound channel $old -> $channel" }
      if (old.isActive) {
        Timber.d { "Close old outbound channel $old" }
        old.flushAndClose()
      }
    }

    outboundChannel = channel
  }

  private fun setOutboundAutoRead(isAutoRead: Boolean) {
    outboundChannel?.config()?.isAutoRead = isAutoRead
  }

  private fun queueOrDeliverOutboundMessage(msg: Any) {
    val outbound = outboundChannel
    if (outbound == null) {
      messageQueue.add(msg)
    } else {
      outbound.writeAndFlush(msg)
    }
  }

  private fun replayQueuedMessages(channel: Channel) {
    var needsFlush = false
    try {
      val queued = messageQueue
      needsFlush = queued.isNotEmpty()
      if (needsFlush) {
        for (q in queued) {
          channel.write(q)
        }
      }
    } finally {
      if (needsFlush) {
        channel.flush()
      }

      messageQueue.clear()
    }
  }

  @CheckResult
  private fun createHttpErrorResponse(): HttpResponse {
    return DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.BAD_GATEWAY,
        Unpooled.EMPTY_BUFFER,
    )
  }

  private fun handleHttpsConnect(ctx: ChannelHandlerContext, msg: HttpRequest) {
    val parsed = parseUriAndPort(msg.uri(), 443)
    if (parsed == null) {
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedHostName.isBlank()) {
      Timber.w {
        "(${channelId}) DROP: Invalid upstream destination address: ${parsed.resolvedHostName}"
      }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination port: ${parsed.resolvedPort}" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val serverChannel = ctx.channel()

    val future =
        tcpSocketCreator.connect(
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            onChannelInitialized = { ch ->
              val pipeline = ch.pipeline()

              // Read from the REMOTE and send back to the PROXY
              pipeline.addLast(
                  RelayHandler(
                      serverSocketTimeout = serverSocketTimeout,
                  )
              )
            },
        )

    val outbound = future.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }
    outbound.closeFuture().addListener { serverChannel.flushAndClose() }

    outbound.apply {
      attr(RelayHandler.TAG)
          .set("HTTPS-CONNECT-INBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}")
      attr(RelayHandler.WRITE_BACK_CHANNEL).set(serverChannel)
    }

    future.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "Unable to connect to $parsed" }
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      // Tell proxy we've established connection
      val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

      // Enable auto-read once connection is established
      serverChannel.config().isAutoRead = true

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Read from the PROXY and send to the remote
      pipeline.addLast(
          RelayHandler(
              serverSocketTimeout = serverSocketTimeout,
          )
      )

      serverChannel.apply {
        attr(RelayHandler.TAG)
            .set("HTTPS-CONNECT-OUTBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}")
        attr(RelayHandler.WRITE_BACK_CHANNEL).set(outbound)
      }

      // Then establish connection
      Timber.d { "Write HTTPS connect to $parsed" }
      ctx.writeAndFlush(response)

      // Remove the http server codec
      pipeline.dropHandler(HttpServerCodec::class)
    }
  }

  private fun handleHttpForward(ctx: ChannelHandlerContext, msg: HttpRequest) {
    val parsed = parseUriAndPort(msg.uri(), 80)
    if (parsed == null) {
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedHostName.isBlank()) {
      Timber.w {
        "(${channelId}) DROP: Invalid upstream destination address: ${parsed.resolvedHostName}"
      }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination port: ${parsed.resolvedPort}" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val serverChannel = ctx.channel()

    val future =
        tcpSocketCreator.connect(
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            onChannelInitialized = { ch ->
              val pipeline = ch.pipeline()

              // Must speak HTTP to replay the initial message
              pipeline.addLast(HttpClientCodec())

              // Read from the REMOTE and send back to the PROXY
              pipeline.addLast(
                  RelayHandler(
                      serverSocketTimeout = serverSocketTimeout,
                  )
              )
            },
        )

    val outbound = future.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }
    outbound.closeFuture().addListener { serverChannel.flushAndClose() }

    outbound.apply {
      attr(RelayHandler.TAG)
          .set("HTTP-FORWARD-INBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}")
      attr(RelayHandler.WRITE_BACK_CHANNEL).set(serverChannel)
    }

    future.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "Unable to connect to $parsed" }
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      // Adjust the URL to be relative to the new host
      msg.uri = parsed.proxyCorrectedFilePath

      // Enable auto-read once connection is established
      serverChannel.config().isAutoRead = true

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Read from the PROXY and send to REMOTE
      pipeline.addLast(
          RelayHandler(
              serverSocketTimeout = serverSocketTimeout,
          )
      )

      serverChannel.apply {
        attr(RelayHandler.TAG)
            .set("HTTP-FORWARD-OUTBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}")
        attr(RelayHandler.WRITE_BACK_CHANNEL).set(outbound)
      }

      // Replay the initial request
      Timber.d { "Forward connect to $parsed" }
      outbound.writeAndFlush(msg)

      // Hold onto this channel for future requests to immediately fire off to it
      assignOutboundChannel(outbound)

      // And then replay any previously seen messages that arrived BEFORE we were set up
      // any future messages will go directly to the outbound now that the channel is held
      replayQueuedMessages(outbound)

      // All messages have been replayed, drop the client codec
      outbound.pipeline().dropHandler(HttpClientCodec::class)

      // Remove the http server codec
      pipeline.dropHandler(HttpServerCodec::class)
    }
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      val isWritable = ctx.channel().isWritable
      Timber.d { "Owner write changed: $ctx $isWritable" }
      setOutboundAutoRead(isWritable)
    } finally {
      super.channelWritabilityChanged(ctx)
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    Timber.d { "Clear pending message queue" }
    messageQueue.clear()

    outboundChannel?.flushAndClose()
    outboundChannel = null
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    setChannelId("HTTP-INBOUND-${addr.address}:${addr.port}")
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    val response = createHttpErrorResponse()
    ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    try {
      if (msg is HttpRequest) {
        if (msg.method() == HttpMethod.CONNECT) {
          handleHttpsConnect(ctx, msg)
        } else {
          handleHttpForward(ctx, msg)
        }
      } else if (msg is HttpContent) {
        queueOrDeliverOutboundMessage(msg)
      } else {
        Timber.w { "MSG was not HTTP based: $msg" }
        super.channelRead(ctx, msg)
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  companion object {

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"

    @CheckResult
    private fun parseUriAndPort(uri: String, defaultPort: Int): HttpHostAndPort? {
      if (uri.isBlank()) {
        Timber.w { "No URI without schema from: $uri" }
        return null
      }

      // TODO common code for port validation
      if (defaultPort !in 0..65335) {
        Timber.w { "Invalid default port: $defaultPort" }
        return null
      }

      // HTTPS connect does not always have a "valid looking" URI
      // Do not use URI(uri)

      // Remove the schema http:// or https:// if it exists
      val defaultPortBasedOnSchema: Int
      val uriWithoutSchema: String
      if (uri.startsWith(HTTPS_PREFIX)) {
        uriWithoutSchema = uri.substring(HTTPS_PREFIX.length)
        defaultPortBasedOnSchema = 443
      } else if (uri.startsWith(HTTP_PREFIX)) {
        uriWithoutSchema = uri.substring(HTTP_PREFIX.length)
        defaultPortBasedOnSchema = 80
      } else {
        uriWithoutSchema = uri
        defaultPortBasedOnSchema = 0
      }

      if (uriWithoutSchema.isBlank()) {
        Timber.w { "No URI without schema from: $uri" }
        return null
      }

      val hostAndPort = uriWithoutSchema.split(":")
      val hostAndMaybePath = hostAndPort[0]

      val fallbackPort = if (defaultPortBasedOnSchema > 0) defaultPortBasedOnSchema else defaultPort

      // Port must look like a port
      val portString = hostAndPort.getOrNull(1)
      val port =
          if (portString.isNullOrBlank()) fallbackPort else portString.toIntOrNull() ?: fallbackPort

      // Find the first slash to start the path
      val pathStartIndex = hostAndMaybePath.indexOf("/")
      val host: String
      val path: String
      if (pathStartIndex < 0) {
        // No path delivered, it's all host
        // path is root
        host = hostAndMaybePath
        path = "/"
      } else {
        host = hostAndMaybePath.substring(0, pathStartIndex)
        path = hostAndMaybePath.substring(pathStartIndex + 1).ifBlank { "/" }
      }

      return HttpHostAndPort(
          resolvedHostName = host,
          resolvedPort = port,
          proxyCorrectedFilePath = path,
      )
    }
  }
}
