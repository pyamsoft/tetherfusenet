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

import android.net.Network
import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.DefaultProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.newOutboundConnection
import io.netty.buffer.Unpooled
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

internal class Http1ProxyHandler
internal constructor(
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    isDebug: Boolean,
    serverSocketTimeout: ServerSocketTimeout,
) :
    DefaultProxyHandler(
        socketTagger = socketTagger,
        androidPreferredNetwork = androidPreferredNetwork,
        isDebug = isDebug,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  @CheckResult
  private fun createHttpErrorResponse(): HttpResponse {
    return DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.BAD_GATEWAY,
        Unpooled.EMPTY_BUFFER,
    )
  }

  override fun createErrorResponse(msg: Any): Any {
    return createHttpErrorResponse()
  }

  private fun handleHttpsConnect(ctx: ChannelHandlerContext, msg: HttpRequest) {
    val parsed = parseUriAndPort(msg.uri(), 443)
    if (parsed == null) {
      sendErrorAndClose(ctx, msg)
      return
    }

    val clientChannel = ctx.channel()

    val future =
        newOutboundConnection(
            isDebug = isDebug,
            channel = clientChannel,
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
        )
    val outbound = future.channel()
    future.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "Unable to connect to $parsed" }
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      // Tell proxy we've established connection
      val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

      // Enable auto-read once connection is established
      clientChannel.config().isAutoRead = true

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Add a relay for the internet outbound
      pipeline.addLast(
          RelayHandler(
              id = "HTTPS-CONNECT-${parsed.resolvedHostName}:${parsed.resolvedPort}",
              clientChannel = outbound,
              serverSocketTimeout = serverSocketTimeout,
          )
      )

      // Then establish connection
      ctx.writeAndFlush(response)

      // Hold onto this channel for future requests to immediately fire off to it
      assignOutboundChannel(outbound)

      // And then replay any previously seen messages that arrived BEFORE we were set up
      // any future messages will go directly to the outbound now that the channel is held
      replayQueuedMessages(outbound)

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

    val clientChannel = ctx.channel()

    Timber.d { "Connect to $parsed" }
    val future =
        newOutboundConnection(
            isDebug = isDebug,
            channel = clientChannel,
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
            onChannelOpened = { ch ->
              // Our outbound client MUST speak HTTP
              ch.pipeline().addLast(HttpClientCodec())
            },
        )

    val outbound = future.channel()
    future.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "Unable to connect to $parsed" }
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      // Adjust the URL to be relative to the new host
      msg.uri = parsed.proxyCorrectedFilePath

      // Enable auto-read once connection is established
      clientChannel.config().isAutoRead = true

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Add a relay for the internet outbound
      pipeline.addLast(
          RelayHandler(
              id = "HTTP-FORWARD-${parsed.resolvedHostName}:${parsed.resolvedPort}",
              clientChannel = outbound,
              serverSocketTimeout = serverSocketTimeout,
          )
      )

      // Replay the initial request
      Timber.d { "REPLAY: $msg" }
      outbound.writeAndFlush(msg).addListener {
        Timber.d { "OUTBOUND: ${it.isSuccess} ${it.cause()}" }
      }

      // Hold onto this channel for future requests to immediately fire off to it
      assignOutboundChannel(outbound)

      // And then replay any previously seen messages that arrived BEFORE we were set up
      // any future messages will go directly to the outbound now that the channel is held
      replayQueuedMessages(outbound)

      // Remove the http server codec
      pipeline.dropHandler(HttpServerCodec::class)
    }
  }

  override fun onChannelRead(ctx: ChannelHandlerContext, msg: Any) {
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
      sendErrorAndClose(ctx, msg)
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
