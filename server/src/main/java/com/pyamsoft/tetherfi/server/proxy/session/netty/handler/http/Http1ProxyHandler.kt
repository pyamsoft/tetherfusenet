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
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.session.address
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.applyBandwidthLimitFor
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.closeOnFailure
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.runInEventLoop
import com.pyamsoft.tetherfi.server.proxy.session.port
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.Future
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
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
    dispatchers: AppDispatchers,
) :
    ProxyHandler(
        isDebug = isDebug,
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
        dispatchers = dispatchers,
    ) {

  private val relayHandlerFactory =
      RelayHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          serverSocketTimeout = serverSocketTimeout,
          dispatchers = dispatchers,
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

  private fun queueOrDeliverOutboundMessage(
      ctx: ChannelHandlerContext,
      msg: HttpContent,
      channelId: String,
  ) {
    val outbound = outboundChannel

    if (outbound == null) {
      messageQueue.add(msg)
    } else {
      // Use immediately and release
      outbound
          .writeAndFlush(msg)
          .closeOnFailure(
              ctx = ctx,
              channelId = channelId,
              tag = "HTTP-CONTENT",
          )

      if (msg is LastHttpContent) {
        finishForwardedRequest(ctx, outbound)
      }
    }
  }

  private fun releaseQueuedMessages() {
    for (q in messageQueue) {
      ReferenceCountUtil.release(q)
    }
    messageQueue.clear()
  }

  private fun replayQueuedMessages(
      ctx: ChannelHandlerContext,
      channel: Channel,
      channelId: String,
  ) {
    if (messageQueue.isEmpty()) {
      return
    }

    val queued = messageQueue.toList()
    messageQueue.clear()

    var isRequestComplete = false
    for (q in queued) {
      // Write here claims the original msg
      channel
          .write(q)
          .closeOnFailure(
              ctx = ctx,
              channelId = channelId,
              tag = "HTTP-FORWARD",
          )

      if (q is LastHttpContent) {
        isRequestComplete = true
      }
    }
    channel.flush()

    if (isRequestComplete) {
      finishForwardedRequest(ctx, channel)
    }
  }

  private fun finishForwardedRequest(ctx: ChannelHandlerContext, outbound: Channel) {
    if (ctx.isRemoved) {
      return
    }

    // The client codec must encode the final chunk before it is removed, and must be gone before
    // any raw bytes are relayed after it
    outbound.eventLoop().runInEventLoop { outbound.pipeline().dropHandler(HttpClientCodec::class) }

    // Drop down to raw TCP
    val pipeline = ctx.pipeline()

    // Read from the PROXY and send to REMOTE
    pipeline.addLast(relayHandlerFactory.create(Unit))

    // Remove our own handler
    pipeline.dropHandler(this::class)

    // Any undecoded bytes left in the server codec are passed on to the relay
    pipeline.dropHandler(HttpServerCodec::class)
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

    val parsed =
        parseUriAndPort(
            uri = msg.uri(),
            defaultPort = 443,
            resolveHostHeader = { msg.headers().get(HttpHeaderNames.HOST) },
        )
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
      val resolvedFromHeader = resolveDestinationFromHostHeader(hostHeader)
      val hostNameMatches =
          resolvedFromHeader.hostName.equals(parsed.resolvedHostName, ignoreCase = true)
      val resolvedHeaderPort = resolvedFromHeader.port
      val portMatches = resolvedHeaderPort == null || resolvedHeaderPort == parsed.resolvedPort

      if (!hostNameMatches || !portMatches) {
        val target = "${parsed.resolvedHostName}:${parsed.resolvedPort}"
        Timber.w {
          "($channelId) DROP: $tag Host '$hostHeader' != CONNECT target '$target'"
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

    scope.launch(context = dispatchers.io) { allowedClients.seen(client) }

    // Bound the pending message queue while the outbound connects
    serverChannel.config().isAutoRead = false

    val outboundFuture =
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

    val outbound = outboundFuture.channel()

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
    outboundFuture.addListener { future ->
      // The outbound channel is open, move back to the receive side context and adjust the
      // pipeline.
      try {
        ctx.executor().runInEventLoop {
          handleHttpsRelay(
              ctx = ctx,
              channelId = channelId,
              future = future,
              parsed = parsed,
              retained = retained,
              outbound = outbound,
              serverChannel = serverChannel,
              client = client,
          )
        }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "(${channelId}) Unable to execute HTTPS connect relay" }
        // Just release, the ctx is dead
        ReferenceCountUtil.release(retained)

        releaseQueuedMessages()
        outbound.flushAndClose()
      }
    }
  }

  private fun handleHttpsRelay(
      ctx: ChannelHandlerContext,
      channelId: String,
      future: Future<in Void>,
      parsed: HttpHostAndPort,
      retained: HttpRequest,
      outbound: Channel,
      serverChannel: Channel,
      client: TetherClient,
  ) {
    val tag = "HTTPS-CONNECT"

    if (!future.isSuccess) {
      Timber.e(future.cause()) { "(${channelId}) $tag Unable to connect to $parsed" }
      sendErrorAndClose(ctx, retained)
      return
    }

    // We are done with the original message at this point and can release it
    ReferenceCountUtil.release(retained)

    // The tunnel is raw TCP, queued HTTP content has nowhere to go
    releaseQueuedMessages()

    // Drop down to raw TCP
    val pipeline = ctx.pipeline()

    // Remove our own handler
    pipeline.dropHandler(this::class)

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
            ),
        )
        .addListener {
          // Remove the http server codec only after 200 OK is fully written
          pipeline.dropHandler(HttpServerCodec::class)

          serverChannel.config().isAutoRead = true
        }
  }

  @LintIgnoreLongMethod
  private fun handleHttpForward(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: HttpRequest,
  ) {
    val tag = "HTTP-FORWARD"

    val parsed =
        parseUriAndPort(
            uri = msg.uri(),
            defaultPort = 80,
            resolveHostHeader = { msg.headers().get(HttpHeaderNames.HOST) },
        )
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

    scope.launch(context = dispatchers.io) { allowedClients.seen(client) }

    // Bound the pending message queue while the outbound connects
    serverChannel.config().isAutoRead = false

    val outboundFuture =
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

    val outbound = outboundFuture.channel()

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
    adjustHttpHeaders(
        msg = msg,
        parsed = parsed,
    )

    // Retain through listener creation
    val retained = ReferenceCountUtil.retain(msg)

    // Original message is done at this point
    ReferenceCountUtil.release(msg)

    // No try/finally — ownership is tracked per-branch.
    outboundFuture.addListener { future ->
      // The outbound channel is open, move back to the receive side context and adjust the
      // pipeline.
      try {
        ctx.executor().runInEventLoop {
          handleHttpRelay(
              ctx = ctx,
              channelId = channelId,
              future = future,
              parsed = parsed,
              retained = retained,
              outbound = outbound,
              serverChannel = serverChannel,
              client = client,
          )
        }
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "(${channelId}) Unable to execute HTTP fwd relay" }
        ReferenceCountUtil.release(retained)
        releaseQueuedMessages()
        outbound.flushAndClose()
      }
    }
  }

  private fun adjustHttpHeaders(msg: HttpRequest, parsed: HttpHostAndPort) {
    val headers = msg.headers()

    // Websocket upgrades need Connection/Upgrade to reach the upstream server intact
    val isWebSocketUpgrade =
        headers.get(HttpHeaderNames.UPGRADE)?.equals("websocket", ignoreCase = true) == true

    @Suppress("DEPRECATION") headers.remove(HttpHeaderNames.KEEP_ALIVE)

    // If this is a websocket upgrade, we keep the UPGRADE header
    if (!isWebSocketUpgrade) {
      // Otherwise drop the header
      headers.remove(HttpHeaderNames.UPGRADE)

      // Also, we only support a single host-port connection per proxy
      // Force the connection closed to avoid connection re-use having data sent off to the wrong
      // host.
      headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
    }

    headers.remove(HttpHeaderNames.TE)
    headers.remove(HttpHeaderNames.TRAILER)

    @Suppress("DEPRECATION") headers.remove(HttpHeaderNames.PROXY_CONNECTION)
    headers.remove(HttpHeaderNames.PROXY_AUTHENTICATE)
    headers.remove(HttpHeaderNames.PROXY_AUTHORIZATION)

    // Force Host to match the URI target, not whatever the client sent
    headers.set(HttpHeaderNames.HOST, parsed.resolvedHostName)
  }

  private fun handleHttpRelay(
      ctx: ChannelHandlerContext,
      channelId: String,
      future: Future<in Void>,
      parsed: HttpHostAndPort,
      retained: HttpRequest,
      outbound: Channel,
      serverChannel: Channel,
      client: TetherClient,
  ) {
    val tag = "HTTP-FORWARD"

    if (!future.isSuccess) {
      Timber.e(future.cause()) { "(${channelId}) $tag Unable to connect to $parsed" }
      sendErrorAndClose(ctx, retained)
      return
    }

    RelayHandler.applyChannelAttributes(
        channel = serverChannel,
        writeBackChannel = outbound,
        tag = "HTTP-FORWARD-OUTBOUND-${parsed.resolvedHostName}:${parsed.resolvedPort}",
        direction = RelayHandler.Direction.OUTBOUND,
        client = client,
    )

    // Responses are relayed raw, including any sent before the request body completes
    ctx.pipeline().get(HttpServerCodec::class.java)?.removeOutboundHandler()
    outbound.pipeline().get(HttpClientCodec::class.java)?.removeInboundHandler()

    // Replay the initial request
    Timber.d { "($channelId) Write $tag to $parsed" }

    // Success: writeAndFlush transfers ownership of retained to Netty.
    // Netty releases retained after encoding — do NOT release again.
    outbound
        .writeAndFlush(retained)
        .closeOnFailure(
            ctx = ctx,
            channelId = channelId,
            tag = tag,
        )

    // Hold onto this channel so the rest of the request body goes straight to it
    assignOutboundChannel(outbound)

    val isRequestComplete = retained is LastHttpContent
    if (isRequestComplete) {
      // We have the current last http content message
      // Clear our "hold queue" and finish the request
      releaseQueuedMessages()
      finishForwardedRequest(ctx, outbound)
    } else {
      // Replay any body content that arrived BEFORE we were set up
      replayQueuedMessages(ctx, outbound, channelId)
    }

    serverChannel.config().isAutoRead = true
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      val isWritable = ctx.channel().isWritable
      setOutboundAutoRead(isWritable)
    } finally {
      super.channelWritabilityChanged(ctx)
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    Timber.d { "Clear pending message queue" }
    releaseQueuedMessages()

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
    // Write here claims the msg
    // response.refCount = 0
    ctx.writeAndFlush(createHttpErrorResponse()).addListener { closeChannels(ctx) }

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
        queueOrDeliverOutboundMessage(ctx, msg, channelId)
      }

      else -> {
        Timber.w { "($channelId) MSG was not HTTP based: $msg" }

        // Message is passed on, no release needed
        super.channelRead(ctx, msg)
      }
    }
  }

  private data class DestinationFromHostHeader(
      val hostName: String,
      val port: Int?,
  )

  companion object {

    private const val PORT_HTTP = 80
    private const val PORT_HTTPS = 443
    private const val PORT_UNKNOWN = 0

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"

    @JvmStatic
    @CheckResult
    private fun resolveDestinationFromHostHeader(hostHeader: String): DestinationFromHostHeader {
      // IPv6 literal
      if (hostHeader.startsWith("[")) {
        val bracketEnd = hostHeader.indexOf("]")
        if (bracketEnd < 0) {
          // Malformed bracketed literal, treat the whole thing as the host
          return DestinationFromHostHeader(
              hostName = hostHeader,
              port = null,
          )
        }

        val host = hostHeader.substring(1, bracketEnd)
        val afterBracket = hostHeader.substring(bracketEnd + 1)
        val port =
            if (afterBracket.startsWith(":")) {
              afterBracket.substring(1)
            } else {
              null
            }
        return DestinationFromHostHeader(
            hostName = host,
            port = port?.toIntOrNull(),
        )
      }

      val colonIndex = hostHeader.lastIndexOf(":")
      return if (colonIndex < 0) {
        DestinationFromHostHeader(
            hostName = hostHeader,
            port = null,
        )
      } else {
        DestinationFromHostHeader(
            hostName = hostHeader.substring(0, colonIndex),
            port = hostHeader.substring(colonIndex + 1).toIntOrNull(),
        )
      }
    }

    @CheckResult
    private fun parseIpv6Literal(
        uri: String,
        uriWithoutSchema: String,
        defaultPortBasedOnSchema: Int,
        defaultPort: Int,
    ): HttpHostAndPort? {
      val bracketEnd = uriWithoutSchema.indexOf("]")
      if (bracketEnd < 0) {
        Timber.w { "Invalid IPv6 URI: $uri" }
        return null
      }

      val ipv6Host = uriWithoutSchema.substring(1, bracketEnd)
      val afterBracket = uriWithoutSchema.substring(bracketEnd + 1)
      val fallbackPort = if (defaultPortBasedOnSchema > 0) defaultPortBasedOnSchema else defaultPort
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

    @CheckResult
    private fun parseHostAndPort(
        uriWithoutSchema: String,
        defaultPortBasedOnSchema: Int,
        defaultPort: Int,
    ): HttpHostAndPort {
      val hostAndPort = uriWithoutSchema.split(":")
      val hostAndMaybePath = hostAndPort[0]

      val fallbackPort = if (defaultPortBasedOnSchema > 0) defaultPortBasedOnSchema else defaultPort

      // Port must look like a port
      // Could be "nothing", could be "80" could be "8096/"
      val port: Int
      var path = ""
      val portAndMaybePath = hostAndPort.getOrNull(1).orEmpty()
      if (portAndMaybePath.isNotBlank()) {
        val pathStartIndex = portAndMaybePath.indexOf("/")
        if (pathStartIndex < 0) {
          // There is no path after the port OR there is no port
          port =
              if (portAndMaybePath.isBlank()) fallbackPort
              else portAndMaybePath.toIntOrNull() ?: fallbackPort
        } else {
          // There is a port number and a path after the port
          val maybeJustPortNumber = portAndMaybePath.substring(0, pathStartIndex)
          port =
              if (maybeJustPortNumber.isBlank()) fallbackPort
              else maybeJustPortNumber.toIntOrNull() ?: fallbackPort
          if (path.isBlank()) {
            path = portAndMaybePath.substring(pathStartIndex).ifBlank { "/" }
          }
        }
      } else {
        // No port, fallback
        port = fallbackPort
      }

      // Find the first slash to start the path
      val pathStartIndex = hostAndMaybePath.indexOf("/")
      val host: String
      if (pathStartIndex < 0) {
        // No path delivered, it's all host
        host = hostAndMaybePath
        if (path.isBlank()) {
          // Path not found yet, assume root
          path = "/"
        }
      } else {
        host = hostAndMaybePath.substring(0, pathStartIndex)

        if (path.isBlank()) {
          // Otherwise assign best path
          path = hostAndMaybePath.substring(pathStartIndex).ifBlank { "/" }
        }
      }

      return HttpHostAndPort(
          resolvedHostName = host,
          resolvedPort = port,
          proxyCorrectedFilePath = path,
      )
    }

    @CheckResult
    private fun parseOriginFormFromHostHeader(
        resolveHostHeader: (() -> String)?,
        path: String,
        defaultPort: Int,
    ): HttpHostAndPort? {
      if (resolveHostHeader == null) {
        Timber.w { "No resolveHostHeader function provided." }
        return null
      }

      val hostHeader = resolveHostHeader()
      if (hostHeader.isBlank()) {
        Timber.w { "Origin-form request has no Host header to resolve destination: $path" }
        return null
      }

      val parsed =
          if (hostHeader.startsWith("[")) {
            // Could just be straight IPv6
            parseIpv6Literal(
                uri = hostHeader,
                uriWithoutSchema = hostHeader,
                defaultPortBasedOnSchema = PORT_UNKNOWN,
                defaultPort = defaultPort,
            )
          } else {
            parseHostAndPort(
                uriWithoutSchema = hostHeader,
                defaultPortBasedOnSchema = PORT_UNKNOWN,
                defaultPort = defaultPort,
            )
          }

      return parsed?.copy(proxyCorrectedFilePath = path)
    }

    @CheckResult
    private fun parseUriAndPort(
        uri: String,
        defaultPort: Int,
        resolveHostHeader: (() -> String)?,
    ): HttpHostAndPort? {
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
        return parseIpv6Literal(
            uri = uri,
            uriWithoutSchema = uriWithoutSchema,
            defaultPortBasedOnSchema = defaultPortBasedOnSchema,
            defaultPort = defaultPort,
        )
      }

      val hostAndPort =
          parseHostAndPort(
              uriWithoutSchema = uriWithoutSchema,
              defaultPortBasedOnSchema = defaultPortBasedOnSchema,
              defaultPort = defaultPort,
          )

      // Some requests (like "GET /path HTTP/1.1") have no additional info
      // read the actual host header and try to resolve from that
      if (hostAndPort.resolvedHostName.isBlank()) {
        return parseOriginFormFromHostHeader(
            resolveHostHeader = resolveHostHeader,
            path = uriWithoutSchema,
            defaultPort = defaultPort,
        )
      }

      // Otherwise we probably found something?
      return hostAndPort
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
        dispatchers: AppDispatchers,
    ): HandlerFactory<Unit> {
      return {
        Http1ProxyHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            tcpSocketCreator = tcpSocketCreator,
            serverSocketTimeout = serverSocketTimeout,
            dispatchers = dispatchers,
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
