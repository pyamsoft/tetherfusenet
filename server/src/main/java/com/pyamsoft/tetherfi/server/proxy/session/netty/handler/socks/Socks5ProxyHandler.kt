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
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.applyBandwidthLimitFor
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp.UdpRelayHandler
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5Message
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope

internal class Socks5ProxyHandler
internal constructor(
    isDebug: Boolean,
    scope: CoroutineScope,
    clientResolver: ClientResolver,
    allowedClients: AllowedClients,
    tcpSocketCreator: ChannelCreator,
    serverSocketTimeout: ServerSocketTimeout,
    private val blockedClients: BlockedClients,
    private val udpSocketCreator: ChannelCreator,
) :
    SocksProxyHandler<Socks5CommandRequest>(
        isDebug = isDebug,
        scope = scope,
        allowedClients = allowedClients,
        blockedClients = blockedClients,
        tcpSocketCreator = tcpSocketCreator,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private val udpRelayHandlerFactory =
      UdpRelayHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          clientResolver = clientResolver,
          serverSocketTimeout = serverSocketTimeout,
      )

  @CheckResult
  private fun createSOCKS5CommandErrorResponse(msg: Socks5CommandRequest): Socks5CommandResponse {
    return DefaultSocks5CommandResponse(
        Socks5CommandStatus.COMMAND_UNSUPPORTED,
        msg.dstAddrType(),
    )
  }

  @CheckResult
  private fun createSOCKS5CommandFailureResponse(msg: Socks5CommandRequest): Socks5CommandResponse {
    return DefaultSocks5CommandResponse(
        Socks5CommandStatus.FAILURE,
        msg.dstAddrType(),
    )
  }

  private fun handleSocks5InitialRequest(ctx: ChannelHandlerContext) {
    // We do not care about auth
    ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
  }

  private fun handleSocksUdpAssociateRequest(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: Socks5CommandRequest,
  ) {
    val tag = "${msg.version()}-UDP_ASSOC"

    val serverChannel = ctx.channel()

    val tcpControlAddress = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (tcpControlAddress == null) {
      Timber.w { "($channelId) DROP: $tag client remote==null" }
      sendFailureAndClose(ctx, msg)
      return
    }

    val client = getTetherClient(ctx)
    if (client == null) {
      Timber.w { "($channelId) DROP: $tag TetherClient is NULL" }
      sendFailureAndClose(ctx, msg)
      return
    }

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "($channelId) DROP: $tag client was blocked: $client" }
      sendFailureAndClose(ctx, msg)
      return
    }

    Timber.d { "(${channelId}) $tag Register UDP for TCP control $tcpControlAddress" }
    val udpControl =
        udpSocketCreator.bind { ch ->
          val pipeline = ch.pipeline()

          if (isDebug) {
            pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
          }

          // Bandwidth limiter
          pipeline.applyBandwidthLimitFor(client)

          // Read from the REMOTE and send back to the PROXY
          pipeline.addLast(udpRelayHandlerFactory.create(Unit))
        }

    val udpRelay = udpControl.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { udpRelay.flushAndClose() }
    udpRelay.closeFuture().addListener { serverChannel.flushAndClose() }

    UdpRelayHandler.applyChannelAttributes(
        channel = udpRelay,
        tcpControlAddress = tcpControlAddress,
    )

    udpControl.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "($channelId) DROP $tag proxied outbound failed" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      val relayControl = udpRelay.localAddress()
      if (relayControl == null) {
        Timber.w { "($channelId) DROP $tag proxied outbound remote==null" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      val relayControlAddress = relayControl.cast<InetSocketAddress>()
      if (relayControlAddress == null) {
        Timber.w { "($channelId) DROP $tag proxied outbound remote is not InetSocketAddress" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      dropSocksHandlers(pipeline)

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Tell proxy we've established connection so that NOW we can relay
      val type = resolveSocks5AddressType(relayControlAddress)
      Timber.d {
        "(${channelId}) $tag Inform client of UDP $type ${relayControl.address}:${relayControl.port}"
      }
      ctx.writeAndFlush(
          DefaultSocks5CommandResponse(
              Socks5CommandStatus.SUCCESS,
              type,
              relayControl.address,
              relayControl.port,
          )
      )
    }
  }

  private fun handleSocks5CommandRequest(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: Socks5CommandRequest,
  ) {
    when (val type = msg.type()) {
      Socks5CommandType.CONNECT -> {
        handleSocksConnectRequest(ctx, channelId, msg)
      }

      Socks5CommandType.UDP_ASSOCIATE -> {
        handleSocksUdpAssociateRequest(ctx, channelId, msg)
      }

      Socks5CommandType.BIND -> {
        Timber.w { "(${channelId}) SOCKS5 Bind request received: We do not support BIND currently" }
        sendErrorAndClose(ctx, msg)
      }

      else -> {
        Timber.w { "(${channelId}) Unknown SOCKS5 command type: $type" }
        sendErrorAndClose(ctx, msg)
      }
    }
  }

  override fun sendFailureAndClose(ctx: ChannelHandlerContext, msg: Socks5CommandRequest) {
    ctx.writeAndFlush(createSOCKS5CommandFailureResponse(msg)).addListener { closeChannels(ctx) }
  }

  override fun isConnectMessageType(msg: Socks5CommandRequest): Boolean {
    return msg.type() == Socks5CommandType.CONNECT
  }

  override fun dropSocksHandlers(pipeline: ChannelPipeline) {
    pipeline.dropHandler(Socks5InitialRequestDecoder::class)
    pipeline.dropHandler(Socks5CommandRequestDecoder::class)
  }

  override fun publishConnectSuccess(
      ctx: ChannelHandlerContext,
      tag: String,
      channelId: String,
      msg: Socks5CommandRequest,
      outbound: Channel,
  ) {
    val remote = outbound.localAddress()
    if (remote == null) {
      Timber.w { "($channelId) DROP $tag remote==null" }
      sendFailureAndClose(ctx, msg)
      return
    }

    val remoteAddress = remote.cast<InetSocketAddress>()
    if (remoteAddress == null) {
      Timber.w { "($channelId) DROP $tag remoteAddress is not InetSocketAddress" }
      sendFailureAndClose(ctx, msg)
      return
    }

    ctx.writeAndFlush(
        DefaultSocks5CommandResponse(
            Socks5CommandStatus.SUCCESS,
            resolveSocks5AddressType(remoteAddress),
            remote.address,
            remote.port,
        )
    )
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    var response: Socks5CommandResponse? = null
    if (msg is Socks5Message) {
      if (msg is Socks5CommandRequest) {
        response = createSOCKS5CommandErrorResponse(msg)
      }
    }

    // Otherwise this is either a socks5 init call, or an unknown message
    // according to spec, we do NOT respond to the client
    if (response == null) {
      closeChannels(ctx)
    } else {
      ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
    }
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val addr = ctx.channel().localAddress()
      return@applyChannelId "SOCKS5-INBOUND-${addr.address}:${addr.port}"
    }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    try {
      ensureChannelTag(ctx)

      val channelId = getChannelId()

      if (msg is Socks5Message) {
        when (msg) {
          is Socks5InitialRequest -> {
            handleSocks5InitialRequest(ctx)
          }

          is Socks5CommandRequest -> {
            handleSocks5CommandRequest(ctx, channelId, msg)
          }

          else -> {
            Timber.w { "(${channelId}) Unknown SOCKS5 Message: $msg" }
            sendErrorAndClose(ctx, msg)
          }
        }
      } else {
        Timber.w { "($channelId) Unknown Message: $msg" }
        super.channelRead(ctx, msg)
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  companion object {

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        allowedClients: AllowedClients,
        blockedClients: BlockedClients,
        clientResolver: ClientResolver,
        tcpSocketCreator: ChannelCreator,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<ChannelCreator> {
      return { udpSocketCreator ->
        Socks5ProxyHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            clientResolver = clientResolver,
            tcpSocketCreator = tcpSocketCreator,
            udpSocketCreator = udpSocketCreator,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        client: TetherClient,
    ) {
      SocksProxyHandler.applyChannelAttributes(
          channel = channel,
          client = client,
      )
    }
  }
}
