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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.core.LintIgnoreMagicNumber
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.applyBandwidthLimitFor
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
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Cannot be shareable because of the local state messageQueue and outboundChannel
internal class Http1ProxyHandler
private constructor(
    isDebug: Boolean,
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    private val allowedClients: AllowedClients,
    private val blockedClients: BlockedClients,
    private val tcpSocketCreator: ChannelCreator,
) :
    ProxyHandler(
        isDebug = isDebug,
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private val relayHandlerFactory =
      RelayHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          serverSocketTimeout = serverSocketTimeout,
      )

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
      // Retain the message until used
      val retained = ReferenceCountUtil.retain(msg)

      // Queue for later
      messageQueue.add(retained)
    } else {
      // Use immediately and release
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
          // Write here claims the original msg
          channel.write(q)

          // Release here claims the ref count from the "retain" operation in queue function
          ReferenceCountUtil.release(q)
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

  @LintIgnoreLongMethod
  private fun handleHttpsConnect(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: HttpRequest,
  ) {
    val tag = "HTTPS-CONNECT"

    val parsed = parseUriAndPort(msg.uri(), 443)
    if (parsed == null) {
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedHostName.isBlank()) {
      Timber.w {
        "(${channelId}) DROP: $tag Invalid upstream destination address: ${parsed.resolvedHostName}"
      }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedPort !in VALID_PORT_RANGE) {
      Timber.w {
        "(${channelId}) DROP: $tag Invalid upstream destination port: ${parsed.resolvedPort}"
      }
      sendErrorAndClose(ctx, msg)
      return
    }

    // Make sure that the HOST header matches the destination requested
    // if the host header is provided (most of the time it is)
    val hostHeader = msg.headers().get(HttpHeaderNames.HOST)
    if (!hostHeader.isNullOrBlank()) {
      val targetAuthority = "${parsed.resolvedHostName}:${parsed.resolvedPort}"
      val hostMatches =
          hostHeader.equals(targetAuthority, ignoreCase = true) ||
              hostHeader.equals(parsed.resolvedHostName, ignoreCase = true)
      if (!hostMatches) {
        Timber.w {
          "($channelId) DROP: $tag Host '$hostHeader' != CONNECT target '$targetAuthority'"
        }
        sendErrorAndClose(ctx, msg)
        return
      }
    }

    // Don't allow sending messages to local destinations
    if (isBlockedLocalAddress(parsed.resolvedHostName)) {
      Timber.w { "($channelId) DROP: $tag Blocked local address: ${parsed.resolvedHostName}" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val serverChannel = ctx.channel()
    val remoteClient = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (remoteClient == null) {
      Timber.w { "($channelId) DROP: $tag remoteClient IP is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val client = getTetherClient(ctx)
    if (client == null) {
      Timber.w { "($channelId) DROP: $tag TetherClient is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "($channelId) DROP: $tag client was blocked: $client" }
      sendErrorAndClose(ctx, msg)
      return
    }

    scope.launch(context = Dispatchers.IO) { allowedClients.seen(client) }

    val future =
        tcpSocketCreator.connect(
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            onChannelInitialized = { ch ->
              val pipeline = ch.pipeline()

              if (isDebug) {
                pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
              }

              // Bandwidth limiter
              pipeline.applyBandwidthLimitFor(client)

              // Read from the REMOTE and send back to the PROXY
              pipeline.addLast(relayHandlerFactory.create(Unit))
            },
        )

    val outbound = future.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }
    outbound.closeFuture().addListener { serverChannel.flushAndClose() }

    RelayHandler.applyChannelAttributes(
        channel = outbound,
        writeBackChannel = serverChannel,
        tag = "$tag-INBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}",
        direction = RelayHandler.Direction.INBOUND,
        client = client,
    )

    // Retain through listener creation
    val retained = ReferenceCountUtil.retain(msg)

    // At this point we are done with the original message and can release it
    ReferenceCountUtil.release(msg)

    // We start up a future listener here
    future.addListener { future ->
      try {
        if (!future.isSuccess) {
          Timber.e(future.cause()) { "(${channelId}) $tag Unable to connect to $parsed" }
          sendErrorAndClose(ctx, retained)
          return@addListener
        }

        // Enable auto-read once connection is established
        serverChannel.config().isAutoRead = true

        // Drop down to raw TCP
        val pipeline = ctx.pipeline()

        // Remove our own handler
        pipeline.dropHandler(this::class)

        // Bandwidth limiter
        pipeline.applyBandwidthLimitFor(client)

        // Read from the PROXY and send to the remote
        pipeline.addLast(relayHandlerFactory.create(Unit))

        RelayHandler.applyChannelAttributes(
            channel = serverChannel,
            writeBackChannel = outbound,
            tag = "$tag-OUTBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}",
            direction = RelayHandler.Direction.OUTBOUND,
            client = client,
        )

        // Then establish connection
        Timber.d { "(${channelId}) Write $tag to $parsed" }

        // Tell proxy we've established connection
        //
        // Write here claims the msg
        ctx.writeAndFlush(
                DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                )
            )
            .addListener {
              // Remove the http server codec only after 200 OK is fully written
              pipeline.dropHandler(HttpServerCodec::class)
            }
      } finally {
        ReferenceCountUtil.release(retained)
      }
    }
  }

  @LintIgnoreLongMethod
  private fun handleHttpForward(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: HttpRequest,
  ) {
    val tag = "HTTP-FORWARD"

    val parsed = parseUriAndPort(msg.uri(), 80)
    if (parsed == null) {
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedHostName.isBlank()) {
      Timber.w { "(${channelId}) DROP: $tag Invalid upstream destination address: $parsed" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (parsed.resolvedPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: $tag Invalid upstream destination port: $parsed" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // Don't allow sending messages to local destinations
    if (isBlockedLocalAddress(parsed.resolvedHostName)) {
      Timber.w { "($channelId) DROP: $tag Blocked local address: ${parsed.resolvedHostName}" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val serverChannel = ctx.channel()
    val remoteClient = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (remoteClient == null) {
      Timber.w { "($channelId) DROP: $tag remoteClient IP is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val client = getTetherClient(ctx)
    if (client == null) {
      Timber.w { "($channelId) DROP: $tag TetherClient is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "($channelId) DROP: $tag client was blocked: $client" }
      sendErrorAndClose(ctx, msg)
      return
    }

    scope.launch(context = Dispatchers.IO) { allowedClients.seen(client) }

    val future =
        tcpSocketCreator.connect(
            hostName = parsed.resolvedHostName,
            port = parsed.resolvedPort,
            onChannelInitialized = { ch ->
              val pipeline = ch.pipeline()

              if (isDebug) {
                pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
              }

              // Must speak HTTP to replay the initial message
              pipeline.addLast(HttpClientCodec())

              // Bandwidth limiter
              pipeline.applyBandwidthLimitFor(client)

              // Read from the REMOTE and send back to the PROXY
              pipeline.addLast(relayHandlerFactory.create(Unit))
            },
        )

    val outbound = future.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }
    outbound.closeFuture().addListener { serverChannel.flushAndClose() }

    RelayHandler.applyChannelAttributes(
        channel = outbound,
        writeBackChannel = serverChannel,
        tag = "$tag-INBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}",
        direction = RelayHandler.Direction.INBOUND,
        client = client,
    )

    // Adjust the URL to be relative to the new host
    msg.uri = parsed.proxyCorrectedFilePath

    // Strip hop-by-hop headers before forwarding
    val headers = msg.headers()

    @Suppress("DEPRECATION") headers.remove(HttpHeaderNames.KEEP_ALIVE)
    headers.remove(HttpHeaderNames.CONNECTION)

    headers.remove(HttpHeaderNames.TRANSFER_ENCODING)
    headers.remove(HttpHeaderNames.UPGRADE)
    headers.remove(HttpHeaderNames.TE)
    headers.remove(HttpHeaderNames.TRAILER)

    @Suppress("DEPRECATION") headers.remove(HttpHeaderNames.PROXY_CONNECTION)
    headers.remove(HttpHeaderNames.PROXY_AUTHENTICATE)
    headers.remove(HttpHeaderNames.PROXY_AUTHORIZATION)

    // Force Host to match the URI target, not whatever the client sent
    headers.set(HttpHeaderNames.HOST, parsed.resolvedHostName)

    // Retain through listener creation
    val retained = ReferenceCountUtil.retain(msg)

    // Original message is done at this point
    ReferenceCountUtil.release(msg)

    future.addListener { future ->
      try {
        if (!future.isSuccess) {
          Timber.e(future.cause()) { "Unable to connect to $parsed" }
          sendErrorAndClose(ctx, msg)
          return@addListener
        }

        // Enable auto-read once connection is established
        serverChannel.config().isAutoRead = true

        // Drop down to raw TCP
        val pipeline = ctx.pipeline()

        // Remove our own handler
        pipeline.dropHandler(this::class)

        // Bandwidth limiter
        pipeline.applyBandwidthLimitFor(client)

        // Read from the PROXY and send to REMOTE
        pipeline.addLast(relayHandlerFactory.create(Unit))

        RelayHandler.applyChannelAttributes(
            channel = serverChannel,
            writeBackChannel = outbound,
            tag = "$tag-OUTBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}",
            direction = RelayHandler.Direction.OUTBOUND,
            client = client,
        )

        // Replay the initial request
        Timber.d { "($channelId) Forward connect to $parsed" }

        // Write here claims the msg
        // msg.refCount = 0
        outbound.writeAndFlush(retained).addListener {
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
      } finally {
        ReferenceCountUtil.release(retained)
      }
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
    for (q in messageQueue) {
      ReferenceCountUtil.release(q)
    }
    messageQueue.clear()

    outboundChannel?.flushAndClose()
    outboundChannel = null
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val addr = ctx.channel().localAddress()
      return@applyChannelId "HTTP-INBOUND-${addr.address}:${addr.port}"
    }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Msg creation point, response.refCount = 1
    val response = createHttpErrorResponse()

    // Write here claims the msg
    // response.refCount = 0
    ctx.writeAndFlush(response).addListener { closeChannels(ctx) }

    // We should also release the original msg
    ReferenceCountUtil.release(msg)
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    // Inbound point, msg.refCount = 1

    ensureChannelTag(ctx)
    val channelId = getChannelId()

    when (msg) {
      is HttpRequest -> {
        if (msg.method() == HttpMethod.CONNECT) {
          handleHttpsConnect(ctx, channelId, msg)
        } else {
          handleHttpForward(ctx, channelId, msg)
        }
      }

      is HttpContent -> {
        // Message queued for later, no release needed
        // or is immediately written and claimed by netty, no release needed
        queueOrDeliverOutboundMessage(msg)
      }

      else -> {
        Timber.w { "($channelId) MSG was not HTTP based: $msg" }

        // Message is passed on, no release needed
        super.channelRead(ctx, msg)
      }
    }
  }

  companion object {

    private const val PORT_HTTP = 80
    private const val PORT_HTTPS = 443
    private const val PORT_UNKNOWN = 0

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"

    @CheckResult
    private fun parseUriAndPort(uri: String, defaultPort: Int): HttpHostAndPort? {
      if (uri.isBlank()) {
        Timber.w { "No URI without schema from: $uri" }
        return null
      }

      // TODO common code for port validation
      @LintIgnoreMagicNumber
      if (defaultPort !in 0..65535) {
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
        defaultPortBasedOnSchema = PORT_HTTPS
      } else if (uri.startsWith(HTTP_PREFIX)) {
        uriWithoutSchema = uri.substring(HTTP_PREFIX.length)
        defaultPortBasedOnSchema = PORT_HTTP
      } else {
        uriWithoutSchema = uri
        defaultPortBasedOnSchema = PORT_UNKNOWN
      }

      if (uriWithoutSchema.isBlank()) {
        Timber.w { "No URI without schema from: $uri" }
        return null
      }

      // Handle IPv6 literal notation like [::1](:port)(/path)
      if (uriWithoutSchema.startsWith("[")) {
        val bracketEnd = uriWithoutSchema.indexOf("]")
        if (bracketEnd < 0) {
          Timber.w { "Invalid IPv6 URI: $uri" }
          return null
        }

        val ipv6Host = uriWithoutSchema.substring(1, bracketEnd)
        val afterBracket = uriWithoutSchema.substring(bracketEnd + 1)
        val fallbackPort =
            if (defaultPortBasedOnSchema > 0) defaultPortBasedOnSchema else defaultPort
        val port =
            if (afterBracket.startsWith(":")) {
              afterBracket.substring(1).substringBefore("/").toIntOrNull() ?: fallbackPort
            } else {
              fallbackPort
            }

        val slashIndex = afterBracket.indexOf("/")
        val path = if (slashIndex >= 0) afterBracket.substring(slashIndex).ifBlank { "/" } else "/"
        return HttpHostAndPort(
            resolvedHostName = ipv6Host,
            resolvedPort = port,
            proxyCorrectedFilePath = path,
        )
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
        path = hostAndMaybePath.substring(pathStartIndex).ifBlank { "/" }
      }

      return HttpHostAndPort(
          resolvedHostName = host,
          resolvedPort = port,
          proxyCorrectedFilePath = path,
      )
    }

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        allowedClients: AllowedClients,
        blockedClients: BlockedClients,
        tcpSocketCreator: ChannelCreator,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        Http1ProxyHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            tcpSocketCreator = tcpSocketCreator,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        client: TetherClient,
    ) {
      ProxyHandler.applyChannelAttributes(
          channel = channel,
          client = client,
      )
    }
  }
}
