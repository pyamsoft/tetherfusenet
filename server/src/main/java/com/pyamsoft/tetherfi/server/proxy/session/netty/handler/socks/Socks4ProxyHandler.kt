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
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.dropHandler
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest
import io.netty.handler.codec.socksx.v4.Socks4CommandResponse
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus
import io.netty.handler.codec.socksx.v4.Socks4CommandType
import io.netty.handler.codec.socksx.v4.Socks4Message
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CoroutineScope

internal class Socks4ProxyHandler
internal constructor(
    clientResolver: ClientResolver,
    tcpSocketCreator: ChannelCreator,
    serverSocketTimeout: ServerSocketTimeout,
    scope: CoroutineScope,
) :
    SocksProxyHandler<Socks4CommandRequest>(
        scope = scope,
        clientResolver = clientResolver,
        tcpSocketCreator = tcpSocketCreator,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  @CheckResult
  private fun createSOCKS4ErrorResponse(): Socks4CommandResponse {
    return DefaultSocks4CommandResponse(
        Socks4CommandStatus.REJECTED_OR_FAILED,
    )
  }

  private fun handleSocks4CommandRequest(ctx: ChannelHandlerContext, msg: Socks4CommandRequest) {
    when (val type = msg.type()) {
      Socks4CommandType.CONNECT -> {
        handleSocksConnectRequest(ctx, msg)
      }

      Socks4CommandType.BIND -> {
        Timber.w { "SOCKS4 Bind request received: We do not support BIND currently" }
        sendErrorAndClose(ctx, msg)
      }

      else -> {
        Timber.w { "Unknown SOCKS4 command type: $type" }
        sendErrorAndClose(ctx, msg)
      }
    }
  }

  override fun dropSocksHandlers(pipeline: ChannelPipeline) {
    pipeline.dropHandler(Socks4ServerDecoder::class)
  }

  override fun isConnectMessageType(msg: Socks4CommandRequest): Boolean {
    return msg.type() == Socks4CommandType.CONNECT
  }

  override fun publishConnectSuccess(
      tag: String,
      ctx: ChannelHandlerContext,
      msg: Socks4CommandRequest,
      outbound: Channel,
  ) {
    val remote = outbound.localAddress()
    if (remote == null) {
      Timber.w { "(${channelId}) DROP $tag outbound remote==null" }
      sendFailureAndClose(ctx, msg)
      return
    }

    ctx.writeAndFlush(
        DefaultSocks4CommandResponse(
            Socks4CommandStatus.SUCCESS,
            remote.address,
            remote.port,
        )
    )
  }

  override fun sendFailureAndClose(ctx: ChannelHandlerContext, msg: Socks4CommandRequest) {
    sendErrorAndClose(ctx, msg)
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    var response: Socks4CommandResponse? = null
    if (msg is Socks4Message) {
      if (msg is Socks4CommandRequest) {
        response = createSOCKS4ErrorResponse()
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

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    try {
      if (msg is Socks4Message) {
        if (msg is Socks4CommandRequest) {
          handleSocks4CommandRequest(ctx, msg)
        } else {
          Timber.w { "Unknown SOCKS4 Message: $msg" }
          sendErrorAndClose(ctx, msg)
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
