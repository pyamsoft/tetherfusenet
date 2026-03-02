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
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.dropHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.DefaultProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.RelayHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.newOutboundConnection
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest

internal abstract class SocksProxyHandler<T : SocksMessage>
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
    val channel = ctx.channel()

    if (dstAddr.isNullOrBlank()) {
      sendErrorAndClose(ctx, msg)
      return
    }

    if (dstPort !in VALID_PORT_RANGE) {
      sendErrorAndClose(ctx, msg)
      return
    }

    val connectSocket =
        newOutboundConnection(
            isDebug = isDebug,
            channel = channel,
            hostName = dstAddr,
            port = dstPort,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
        )
    val outbound = connectSocket.channel()
    connectSocket.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "SOCKS proxied outbound failed" }
        sendFailureAndClose(ctx, msg)
        return@addListener
      }

      // Tell proxy we've established connection
      publishConnectSuccess(ctx, msg, outbound)

      assignOutboundChannel(outbound)

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()

      dropSocksHandlers(pipeline)

      // Remove our own handler
      pipeline.dropHandler(this::class)

      // Add a relay for the internet outbound
      pipeline.addLast(
          RelayHandler(
              id = "SOCKS${msg.version()}-CONNECT-${dstAddr}:${dstPort}",
              clientChannel = outbound,
              serverSocketTimeout = serverSocketTimeout,
          )
      )
    }
  }

  @CheckResult protected abstract fun isConnectMessageType(msg: T): Boolean

  protected abstract fun publishConnectSuccess(
      ctx: ChannelHandlerContext,
      msg: T,
      outbound: Channel,
  )

  protected abstract fun dropSocksHandlers(pipeline: ChannelPipeline)

  protected abstract fun sendFailureAndClose(ctx: ChannelHandlerContext, msg: T)

  companion object {
    protected val VALID_PORT_RANGE = 1..<65535
  }
}
