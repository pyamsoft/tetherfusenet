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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.http.Http1ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks4ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ProtocolDelegatingHandler
private constructor(
    private val isDebug: Boolean,
    private val scope: CoroutineScope,
    private val isHttpEnabled: Boolean,
    private val allowedClients: AllowedClients,
    private val clientResolver: ClientResolver,
    private val serverSocketTimeout: ServerSocketTimeout,
    private val tcpSocketCreator: ChannelCreator,
    // IF this is NULL, SOCKS is not enabled
    private val udpSocketCreator: ChannelCreator?,
    blockedClients: BlockedClients,
) : ByteToMessageDecoder() {

  private val http1HandlerFactory =
      Http1ProxyHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          tcpSocketCreator = tcpSocketCreator,
          serverSocketTimeout = serverSocketTimeout,
      )

  private val socks4HandlerFactory =
      Socks4ProxyHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          tcpSocketCreator = tcpSocketCreator,
          serverSocketTimeout = serverSocketTimeout,
      )

  private val socks5HandlerFactory =
      Socks5ProxyHandler.factory(
          isDebug = isDebug,
          scope = scope,
          allowedClients = allowedClients,
          blockedClients = blockedClients,
          clientResolver = clientResolver,
          tcpSocketCreator = tcpSocketCreator,
          serverSocketTimeout = serverSocketTimeout,
      )

  private fun handleClientRequestSideEffects(
      client: TetherClient,
  ) {
    // Mark all client connections as seen
    //
    // We need to do this because we have access to the MAC address via the GroupInfo.clientList
    // but not the IP address. Android does not let us access the system ARP table so we cannot map
    // MACs to IPs. Thus we need to basically hold our own table of "known" IP addresses and allow
    // a user to block them as they see fit. This is UX wise, not great at all, since a user must
    // eliminate a "bad" IP address by first knowing all the good ones.
    //
    // Though, arguably, blocking is only a nice to have. Real network security should be handled
    // via the password.
    allowedClients.seen(client)
  }

  override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: List<Any>) {
    if (!input.isReadable) {
      Timber.w { "DROP: Unreadable input buffer sent." }
      return
    }

    // Copied from SocksPortUnificationServerHandler.java
    val readerIndex = input.readerIndex()
    val writerIndex = input.writerIndex()
    if (writerIndex == readerIndex) {
      Timber.w { "DROP: Bad input writer index saw=$writerIndex expect=$readerIndex" }
      return
    }

    val pipeline = ctx.pipeline()
    val versionVal = input.getByte(readerIndex)
    val socksVersion = SocksVersion.valueOf(versionVal)

    val serverChannel = ctx.channel()
    val remoteClient = serverChannel.remoteAddress().cast<InetSocketAddress>()
    if (remoteClient == null) {
      Timber.w { "DROP: remoteClient IP is NULL" }
      return
    }

    val remoteClientIpAddress = remoteClient.address.hostAddress
    if (remoteClientIpAddress == null) {
      Timber.w { "DROP: Could not resolve remoteClient.address.hostAddress" }
      return
    }

    val client = clientResolver.ensure(remoteClientIpAddress)
    scope.launch(context = Dispatchers.IO) { handleClientRequestSideEffects(client) }

    try {
      when (socksVersion) {
        SocksVersion.SOCKS4a -> {
          if (udpSocketCreator == null) {
            Timber.w { "DROP: SOCKS4a traffic received but SOCKS was not enabled" }
            return
          }

          if (isDebug) {
            pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
          }

          // Assume SOCKS4
          pipeline.addLast(Socks4ServerEncoder.INSTANCE)
          pipeline.addLast(Socks4ServerDecoder())

          // Bandwidth limiter
          pipeline.applyBandwidthLimitFor(client)

          // SOCKS4 Handler
          pipeline.addLast(socks4HandlerFactory.create(Unit))

          Socks4ProxyHandler.applyChannelAttributes(
              channel = serverChannel,
              client = client,
          )
        }

        SocksVersion.SOCKS5 -> {
          val udpControl = udpSocketCreator
          if (udpControl == null) {
            Timber.w { "DROP: SOCKS5 traffic received but SOCKS was not enabled" }
            return
          }

          if (isDebug) {
            pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
          }

          // Assume SOCKS5
          pipeline.addLast(Socks5ServerEncoder.DEFAULT)
          pipeline.addLast(Socks5InitialRequestDecoder())
          pipeline.addLast(Socks5CommandRequestDecoder())

          // Bandwidth limiter
          pipeline.applyBandwidthLimitFor(client)

          // SOCKS5 Handler
          pipeline.addLast(socks5HandlerFactory.create(udpControl))

          Socks5ProxyHandler.applyChannelAttributes(
              channel = serverChannel,
              client = client,
          )
        }

        else -> {
          if (!isHttpEnabled) {
            Timber.w { "DROP: HTTP traffic received but HTTP was not enabled" }
            return
          }

          if (isDebug) {
            pipeline.addFirst(LoggingHandler(LogLevel.DEBUG))
          }

          // Assume HTTP
          pipeline.addLast(HttpServerCodec())

          // Bandwidth limiter
          pipeline.applyBandwidthLimitFor(client)

          // And bind our proxy relay handler
          pipeline.addLast(http1HandlerFactory.create(Unit))

          Http1ProxyHandler.applyChannelAttributes(
              channel = serverChannel,
              client = client,
          )
        }
      }
    } finally {
      pipeline.dropHandler(this::class)
    }
  }

  @ConsistentCopyVisibility
  internal data class Params
  internal constructor(
      val scope: CoroutineScope,
      val tcp: ChannelCreator,
      // IF this is NULL, SOCKS is not enabled
      val udp: ChannelCreator?,
  )

  companion object {

    @JvmStatic
    @CheckResult
    fun factory(
        isHttpEnabled: Boolean,
        serverSocketTimeout: ServerSocketTimeout,
        allowedClients: AllowedClients,
        blockedClients: BlockedClients,
        clientResolver: ClientResolver,
        isDebug: Boolean,
    ): HandlerFactory<Params> {
      return { params ->
        ProtocolDelegatingHandler(
            isDebug = isDebug,
            isHttpEnabled = isHttpEnabled,
            serverSocketTimeout = serverSocketTimeout,
            clientResolver = clientResolver,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            scope = params.scope,
            tcpSocketCreator = params.tcp,

            // IF this is NULL, SOCKS is not enabled
            udpSocketCreator = params.udp,
        )
      }
    }
  }
}
