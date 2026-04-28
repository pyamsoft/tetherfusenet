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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.ensure
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.zeroOrAmountAsLong
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.util.AttributeKey
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class UdpRelayHandler
private constructor(
    isDebug: Boolean,
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    private val allowedClients: AllowedClients,
    private val blockedClients: BlockedClients,
    private val clientResolver: ClientResolver,
) :
    ProxyHandler(
        isDebug = isDebug,
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  // Your single socket read payload isn't THAT LARGE right???
  @Volatile private var bytesInbound: Int = 0
  @Volatile private var bytesOutbound: Int = 0

  private var byteCountJob: Job? = null

  @CheckResult
  private fun getBackToClientAddress(ctx: ChannelHandlerContext): InetSocketAddress? {
    return ctx.channel().attr(BACK_TO_CLIENT_ADDRESS).get()
  }

  private fun setBackToClientAddress(ctx: ChannelHandlerContext, address: InetSocketAddress) {
    return ctx.channel().attr(BACK_TO_CLIENT_ADDRESS).set(address)
  }

  @CheckResult
  private fun getChannelTag(ctx: ChannelHandlerContext): String? {
    return ctx.channel().attr(TAG).get()
  }

  private fun setChannelTag(ctx: ChannelHandlerContext, tag: String) {
    ctx.channel().attr(TAG).set(tag)
  }

  @CheckResult
  private fun getTcpControlAddress(ctx: ChannelHandlerContext): InetSocketAddress? {
    return ctx.channel().attr(TCP_CONTROL_ADDRESS).get()
  }

  private fun unwrapUdpResponse(
      ctx: ChannelHandlerContext,
      channelId: String,
      msg: DatagramPacket,
      sender: InetSocketAddress,
  ) {
    UDP.unwrap(
        channelId = channelId,
        ctx = ctx,
        msg = msg,
        onError = { sendErrorAndClose(ctx, msg) },
        onUnwrapped = { data, destination ->
          val tag = "UDP-RELAY-${destination.address}:${destination.port}"

          // Replace the channel ID here now that we have evaluated the real upstream
          setChannelTag(ctx, tag)
          setChannelId(tag)

          val client = resolveTetherClient(ctx, sender)

          // If the client is blocked we do not process any input
          if (blockedClients.isBlocked(client)) {
            Timber.w { "($channelId) DROP: client was blocked: $client" }
            sendErrorAndClose(ctx, msg)
            return@unwrap
          }

          // Grab the amount BEFORE the data buffer is released
          val amountMoved = data.readableBytes()

          // TODO bandwidth limit enforcement

          // Side effect for client tracking
          scope.launch(context = Dispatchers.IO) {
            // Update latest client activity
            allowedClients.seen(client)

            // This is from Proxy out to Internet
            bytesOutbound += amountMoved
          }

          val packet = DatagramPacket(data, destination)
          ctx.writeAndFlush(packet).addListener { packet.release() }
        },
    )
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val id = getChannelTag(ctx)
      if (id == null) {
        val local = ctx.channel().localAddress()
        return@applyChannelId "UDP-RELAY-${local.address}:${local.port}"
      }

      return@applyChannelId id
    }
  }

  @CheckResult
  private fun resolveTetherClient(
      ctx: ChannelHandlerContext,
      clientAddress: InetSocketAddress,
  ): TetherClient =
      getTetherClient(ctx).let { maybeClient ->
        if (maybeClient == null) {
          val newClient = clientResolver.ensure(clientAddress)
          // And set the TetherClient ATTR so we can yank it back later
          applyChannelAttributes(channel = ctx.channel(), client = newClient)
          return@let newClient
        }

        return@let maybeClient
      }

  private fun produceByteReport(client: TetherClient) {
    val inboundCount = bytesInbound
    val outboundCount = bytesOutbound

    // Reset back to zero or we keep double counting
    bytesInbound = 0
    bytesOutbound = 0

    allowedClients.reportTransfer(
        client = client,
        report =
            ByteTransferReport(
                // TODO can we avoid the cast to long
                internetToProxy = inboundCount.zeroOrAmountAsLong(),
                proxyToInternet = outboundCount.zeroOrAmountAsLong(),
            ),
    )
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    // Cancel the report looping job
    byteCountJob?.cancel()
    byteCountJob = null

    // One last report before we close
    getTetherClient(ctx)?.also { produceByteReport(it) }

    ctx.flushAndClose()

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(TCP_CONTROL_ADDRESS).set(null)
      attr(BACK_TO_CLIENT_ADDRESS).set(null)
    }
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    // Just in case we don't actually have the client or direction yet
    // resolve in the loop
    var client = getTetherClient(ctx)

    byteCountJob?.cancel()
    byteCountJob =
        scope.launch(context = Dispatchers.IO) {
          while (isActive) {
            // Don't report too often
            delay(10.seconds)

            if (client == null) {
              // No need to go through the resolver path, just yank the attr if it exists
              client = getTetherClient(ctx)
            }

            client?.also { produceByteReport(it) }
          }
        }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Write a "0" response back to the UDP control channel
    val response =
        DefaultSocks5CommandResponse(
            Socks5CommandStatus.FAILURE,
            Socks5AddressType.IPv4,
            "0.0.0.0",
            0,
        )

    ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    ensureChannelTag(ctx)
    val channelId = getChannelId()

    if (msg is DatagramPacket) {
      val serverChannel = ctx.channel()
      val serverAddress = serverChannel.localAddress().cast<InetSocketAddress>()
      if (serverAddress == null) {
        Timber.w { "(${channelId}) DROP: No server address" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val sender = msg.sender()
      if (sender == null) {
        Timber.w { "(${channelId}) DROP: Null sender in packet" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val tcpControlClient = getTcpControlAddress(ctx)
      if (tcpControlClient == null) {
        Timber.w { "(${channelId}) DROP: No TCP control client for destination: $sender" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val backToClient = getBackToClientAddress(ctx)
      if (backToClient == null || sender == backToClient) {
        // We had no client so this traffic is our client sending -> destination
        // Or this is continuing traffic from the same sender
        setBackToClientAddress(ctx, sender)

        // Validate that the IP ADDRESS of the client and sender are the same
        if (tcpControlClient.address != sender.address) {
          Timber.w {
            "(${channelId}) DROP: Sender did not match expected=${tcpControlClient.address} sender=${sender.address}"
          }
          sendErrorAndClose(ctx, msg)
          return
        }

        unwrapUdpResponse(ctx, channelId, msg, sender)
      } else {
        val content = msg.retain().content()
        val response = UDP.wrap(alloc = ctx.alloc(), sender = sender, content = content)

        val packet = DatagramPacket(response, backToClient)

        val client = resolveTetherClient(ctx, backToClient)

        // If the client is blocked we do not process any input
        if (blockedClients.isBlocked(client)) {
          Timber.w { "($channelId) DROP: client was blocked: $client" }
          sendErrorAndClose(ctx, msg)
          return
        }

        // Grab the amount BEFORE the data buffer is released
        val amountMoved = response.readableBytes()

        // Side effect for client tracking
        scope.launch(context = Dispatchers.IO) {
          // Update latest client activity
          allowedClients.seen(client)

          // This is from Internet back to Proxy
          bytesInbound = amountMoved
        }

        ctx.writeAndFlush(packet).addListener { msg.release() }
      }
    } else {
      Timber.w { "(${channelId}): Invalid message seen: $msg" }
      super.channelRead(ctx, msg)
    }
  }

  companion object {
    @JvmStatic
    private val TAG: AttributeKey<String> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-ID")

    @JvmStatic
    private val BACK_TO_CLIENT_ADDRESS: AttributeKey<InetSocketAddress> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-BACK_TO_CLIENT_ADDRESS")

    @JvmStatic
    private val TCP_CONTROL_ADDRESS: AttributeKey<InetSocketAddress> =
        AttributeKey.newInstance("${UdpRelayHandler::class.simpleName}-TCP_CONTROL_ADDRESS")

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        allowedClients: AllowedClients,
        blockedClients: BlockedClients,
        clientResolver: ClientResolver,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        UdpRelayHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            clientResolver = clientResolver,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        tcpControlAddress: InetSocketAddress,
    ) {
      channel.apply { attr(TCP_CONTROL_ADDRESS).set(tcpControlAddress) }
    }
  }
}
