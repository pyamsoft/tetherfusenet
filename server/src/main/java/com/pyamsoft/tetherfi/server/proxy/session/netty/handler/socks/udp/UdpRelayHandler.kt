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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.udp

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.ensure
import com.pyamsoft.tetherfi.server.proxy.session.address
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.HandlerFactory
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.zeroOrAmountAsLong
import com.pyamsoft.tetherfi.server.proxy.session.port
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
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
    dispatchers: AppDispatchers,
) :
    ProxyHandler(
        isDebug = isDebug,
        scope = scope,
        dispatchers = dispatchers,
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private val bytesInbound = AtomicInteger(0)
  private val bytesOutbound = AtomicInteger(0)

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
        onError = { sendErrorAndClose(ctx, it) },
        onUnwrapped = { retainedData, destination ->
          val tag = "UDP-RELAY-${destination.address}:${destination.port}"

          // Replace the channel ID here now that we have evaluated the real upstream
          setChannelTag(ctx, tag)
          setChannelId(tag)

          val client = resolveTetherClient(ctx, sender)

          // If the client is blocked we do not process any input
          if (blockedClients.isBlocked(client)) {
            Timber.w { "($channelId) DROP: client was blocked: $client" }
            sendErrorAndClose(ctx, retainedData)
            return@unwrap
          }

          // Grab the amount BEFORE the data buffer is released
          val amountMoved = retainedData.readableBytes()

          // Side effect for client tracking
          scope.launch(context = dispatchers.io) {
            // Update latest client activity
            allowedClients.seen(client)

            // This is from Proxy out to Internet
            bytesOutbound.addAndGet(amountMoved)
          }

          // Write here claims the msg
          ctx.writeAndFlush(DatagramPacket(retainedData, destination))
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
    val inboundCount = bytesInbound.getAndSet(0)
    val outboundCount = bytesOutbound.getAndSet(0)

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

  private fun handleUdpToInternetMessage(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
      channelId: String,
      sender: InetSocketAddress,
      tcpControlClient: InetSocketAddress,
  ) {
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
  }

  private fun handleUdpFromInternetMessage(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
      channelId: String,
      sender: InetSocketAddress,
      backToClient: InetSocketAddress,
      tcpControlClient: InetSocketAddress,
  ) {
    // This is from the remote server
    // but this packet should NEVER be from our TCP control socket
    // so if it is, drop it
    if (sender.address == tcpControlClient.address) {
      Timber.w {
        "(${channelId}) DROP: Spoof packet received? Claim from ${sender.address} in internet-response path"
      }
      sendErrorAndClose(ctx, msg)
      return
    }

    val client = resolveTetherClient(ctx, backToClient)

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "($channelId) DROP: client was blocked: $client" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // This copies the bytes out of the retained content
    // Msg creation point, response.refCount = 1
    val response =
        UDP.wrap(
            alloc = ctx.alloc(),
            sender = sender,
            content = msg.content(),
        )

    // At this point, the original message content is copied into response
    // we can free the original message
    ReferenceCountUtil.release(msg)

    // Grab the amount BEFORE the data buffer is released
    val amountMoved = response.readableBytes()

    // Side effect for client tracking
    scope.launch(context = dispatchers.io) {
      // Update latest client activity
      allowedClients.seen(client)

      // This is from Internet back to Proxy
      bytesInbound.addAndGet(amountMoved)
    }

    // Write here claims the msg
    ctx.writeAndFlush(DatagramPacket(response, backToClient))
  }

  private fun handleUdpMessage(ctx: ChannelHandlerContext, msg: DatagramPacket, channelId: String) {
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
      handleUdpToInternetMessage(ctx, msg, channelId, sender, tcpControlClient)
    } else {
      // From internet back to us
      handleUdpFromInternetMessage(ctx, msg, channelId, sender, backToClient, tcpControlClient)
    }
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    // Cancel the report looping job
    byteCountJob?.cancel()
    byteCountJob = null

    // One last report before we close
    getTetherClient(ctx)?.also { produceByteReport(it) }

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(TCP_CONTROL_ADDRESS).set(null)
      attr(BACK_TO_CLIENT_ADDRESS).set(null)
    }

    ctx.flushAndClose()
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    // Just in case we don't actually have the client or direction yet
    // resolve in the loop
    var client = getTetherClient(ctx)

    byteCountJob?.cancel()
    byteCountJob =
        scope.launch(context = dispatchers.io) {
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
    closeChannels(ctx)

    // Release the message
    ReferenceCountUtil.release(msg)
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    // Inbound point, msg.refCount = 1

    ensureChannelTag(ctx)
    val channelId = getChannelId()

    if (msg is DatagramPacket) {
      handleUdpMessage(ctx, msg, channelId)
    } else {
      Timber.w { "(${channelId}): Invalid message seen: $msg" }

      // Message is passed on, no release needed
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
        dispatchers: AppDispatchers,
    ): HandlerFactory<Unit> {
      return {
        UdpRelayHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            clientResolver = clientResolver,
            serverSocketTimeout = serverSocketTimeout,
            dispatchers = dispatchers,
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
