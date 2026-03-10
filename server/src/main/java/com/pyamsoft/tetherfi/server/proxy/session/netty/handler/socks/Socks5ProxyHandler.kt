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

import android.net.Network
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
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
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

internal class Socks5ProxyHandler
internal constructor(
  serverSocketTimeout: ServerSocketTimeout,
  isDebug: Boolean,
  socketTagger: SocketTagger,
  androidPreferredNetwork: Network?,
  private val udpControlRelay: SharedUdpControlRelay<Channel>,
) :
  SocksProxyHandler<Socks5CommandRequest>(
    socketTagger = socketTagger,
    androidPreferredNetwork = androidPreferredNetwork,
    isDebug = isDebug,
    serverSocketTimeout = serverSocketTimeout,
  ) {

  private val upstreamMap: MutableMap<InetSocketAddress, UdpUpstream> = ConcurrentHashMap()

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

  private fun handleSocks5InitialRequest(ctx: ChannelHandlerContext, msg: Socks5InitialRequest) {
    // We do not care about auth
    ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
  }

  private fun handleSocksUdpAssociateRequest(
    ctx: ChannelHandlerContext,
    msg: Socks5CommandRequest,
  ) {
    val serverChannel = ctx.channel()

    val clientAddress = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (clientAddress == null) {
      Timber.w { "SOCKS client remote==null" }
      sendFailureAndClose(ctx, msg)
      return
    }

    val udpControl = udpControlRelay.udpChannel
    if (udpControl == null) {
      Timber.w { "SOCKS UDP Control is NULL" }
      sendFailureAndClose(ctx, msg)
      return
    }

    // When the shared UDP control socket closes, close this socket
    val controlSocket = udpControl.channel()
    controlSocket.closeFuture().addListener { serverChannel.flushAndClose() }

    // Register the client
    // When this channel closes, remove it from the registered list
    val unregister = udpControlRelay.register(clientAddress, serverChannel)
    serverChannel.closeFuture().addListener { unregister() }

    udpControl.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "SOCKS UDP-ASSOC proxied outbound failed" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      val relayControl = controlSocket.localAddress()
      if (relayControl == null) {
        Timber.w { "SOCKS UDP-ASSOC proxied outbound remote==null" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      val relayControlAddress = relayControl.cast<InetSocketAddress>()
      if (relayControlAddress == null) {
        Timber.w { "SOCKS UDP-ASSOC proxied outbound remote is not InetSocketAddress" }
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
      Timber.d { "Tell client about UDP control $type ${relayControl.address}:${relayControl.port}" }
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

  private fun handleSocks5CommandRequest(ctx: ChannelHandlerContext, msg: Socks5CommandRequest) {
    when (val type = msg.type()) {
      Socks5CommandType.CONNECT -> {
        handleSocksConnectRequest(ctx, msg)
      }

      Socks5CommandType.UDP_ASSOCIATE -> {
        handleSocksUdpAssociateRequest(ctx, msg)
      }

      Socks5CommandType.BIND -> {
        Timber.w { "SOCKS5 Bind request received: We do not support BIND currently" }
        sendErrorAndClose(ctx, msg)
      }

      else -> {
        Timber.w { "Unknown SOCKS5 command type: $type" }
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
    msg: Socks5CommandRequest,
    outbound: Channel,
  ) {
    val remote = outbound.localAddress()
    if (remote == null) {
      Timber.w { "SOCKS5 Connect remote==null" }
      sendFailureAndClose(ctx, msg)
      return
    }

    val remoteAddress = remote.cast<InetSocketAddress>()
    if (remoteAddress == null) {
      Timber.w { "SOCKS5 Connect remoteAddress is not InetSocketAddress" }
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

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    setChannelId("SOCKS4-INBOUND-${addr.address}:${addr.port}")
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    // Clear the map so that we can close any UDP upstream connections
    upstreamMap.forEach { (_, udp) -> udp.upstreamFuture.channel().flushAndClose() }
    upstreamMap.clear()
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    try {
      if (msg is Socks5Message) {
        when (msg) {
          is Socks5InitialRequest -> {
            handleSocks5InitialRequest(ctx, msg)
          }

          is Socks5CommandRequest -> {
            handleSocks5CommandRequest(ctx, msg)
          }

          else -> {
            Timber.w { "Unknown SOCKS5 Message: $msg" }
            sendErrorAndClose(ctx, msg)
          }
        }
      } else {
        Timber.w { "Unknown Message: $msg" }
        super.channelRead(ctx, msg)
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }
}