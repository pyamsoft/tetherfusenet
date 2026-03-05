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
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.ProxyHandler
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.flushAndClose
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.newDatagramServer
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal class UdpRelayHandler
internal constructor(
    isDebug: Boolean,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    private val tcpControlChannel: Channel,
    private val serverSocketTimeout: ServerSocketTimeout,
) :
    ProxyHandler(
        isDebug = isDebug,
        socketTagger = socketTagger,
        androidPreferredNetwork = androidPreferredNetwork,
    ) {

  private var id: String = "UDP-RELAY-UNKNOWN"

  private fun unwrapUdpResponse(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
      sentFrom: InetSocketAddress,
  ) {
    val buf = msg.content()
    // Drop bad connection
    if (buf == null) {
      Timber.w { "Null buffer in packet, drop" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val reservedByteOne = buf.readByte()
    if (reservedByteOne != RESERVED_BYTE) {
      Timber.w { "DROP: Expected reserve byte one, but got data: $reservedByteOne" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val reservedByteTwo = buf.readByte()
    if (reservedByteTwo != RESERVED_BYTE) {
      Timber.w { "DROP: Expected reserve byte two, but got data: $reservedByteTwo" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val fragment = buf.readByte()
    if (fragment != FRAGMENT_ZERO) {
      Timber.w { "DROP: Fragments not supported: $fragment" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val addressTypeByte = buf.readByte()
    val addrType = Socks5AddressType.valueOf(addressTypeByte)
    val destinationAddr = readAddress(buf, addrType)

    // A short max is 32767 but ports can go up to 65k
    // Sometimes the short value is negative, in that case, we
    // "fix" it by converting back to an unsigned number
    val destinationPort = buf.readUnsignedShort()

    // The rest of the packet is data
    // We must retain this slice or the underlying buffer will be cleaned up too early
    val data = buf.readRetainedSlice(buf.readableBytes())

    // Build the destination
    val destination = InetSocketAddress(destinationAddr, destinationPort)

    // NOTE(Peter): Currently this tunnel only works over IPv4
    //              If we receive a non-ipv4 address, we must DNS lookup the IPv4 equivalent
    //              and map the address over.
    val serverChannel = ctx.channel()
    val udpRelaySocket =
        newDatagramServer(
            isDebug = isDebug,
            channel = serverChannel,
            socketTagger = socketTagger,
            androidPreferredNetwork = androidPreferredNetwork,
            onChannelOpened = { ch ->
              ch.pipeline()
                  .addLast(
                      UdpRelayUpstreamHandler(
                          serverSocketTimeout = serverSocketTimeout,
                          udpControlChannel = serverChannel,
                          client = sentFrom,
                      )
                  )
            },
        )

    val outbound = udpRelaySocket.channel()
    serverChannel.closeFuture().addListener { flushAndClose(outbound) }

    udpRelaySocket.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "Failed to standup outbound connection!" }
        data.release()
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      val packet = DatagramPacket(data, destination)
      Timber.d { "Opened UDP relay outbound connection $outbound $packet" }
      outbound.writeAndFlush(packet).addListener { packet.release() }
    }
  }

  private fun readAddress(buf: ByteBuf, type: Socks5AddressType): String {
    try {
      when (type) {
        Socks5AddressType.IPv4 -> {
          val bytes = ByteArray(4)
          buf.readBytes(bytes)
          val addr = Inet4Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "Unable to construct IPv4 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "Empty address from IPv4 bytes: $addr" }
            return ""
          }

          return host
        }

        Socks5AddressType.IPv6 -> {
          val bytes = ByteArray(16)
          buf.readBytes(bytes)
          val addr = Inet6Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "Unable to construct IPv6 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "Empty address from IPv6 bytes: $addr" }
            return ""
          }

          return host
        }

        Socks5AddressType.DOMAIN -> {
          val addressLength = buf.readUnsignedByte().toInt()
          if (addressLength == 0) {
            // SOCKS spec says we must fall back to 0 address
            return "0.0.0.0"
          }

          val sequence = buf.readCharSequence(addressLength, StandardCharsets.US_ASCII).toString()
          if (addressLength == 1 && sequence == "0") {
            // PySocks delivers a random port with an address of "0"
            // SOCKS spec says we must fall back to 0 address
            return "0.0.0.0"
          }

          return sequence
        }

        else -> {
          Timber.w { "Invalid datapacket address type $type" }
          return ""
        }
      }
    } catch (e: Throwable) {
      Timber.e(e) { "Error when reading address from data type $type" }
      return ""
    }
  }

  override fun channelRegistered(ctx: ChannelHandlerContext) {
    val timeout = serverSocketTimeout.timeoutDuration
    if (timeout.isInfinite()) {
      Timber.d { "Not adding idle timeout, infinite timeout configured!" }
    } else {
      Timber.d { "Add idle timeout handler $timeout" }
      ctx.pipeline()
          .addFirst(IdleStateHandler(0, 0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    }
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    if (evt is IdleStateEvent) {
      if (evt.state() == IdleState.ALL_IDLE) {
        Timber.d { "Closing idle connection: $ctx $evt" }
        closeChannels(ctx)
      }
    }
  }

  override fun channelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    id = "UDP-RELAY-${addr.address}:${addr.port}"

    // Close UDP relay when control socket closes
    tcpControlChannel.closeFuture().addListener {
      Timber.d { "Closing UDP relay because TCP control closed" }
      closeChannels(ctx)
    }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is DatagramPacket) {
      val sender = msg.sender()
      if (sender == null) {
        Timber.w { "Null sender in packet, drop" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val clientAddress = tcpControlChannel.remoteAddress().cast<InetSocketAddress>()
      if (clientAddress == null) {
        Timber.w { "SOCKS TCP control channel remote==null" }
        sendErrorAndClose(ctx, msg)
        return
      }

      // The sender of this packet is a REMOTE that we interacted with
      val isValid = clientAddress.address == sender.address
      if (!isValid) {
        Timber.w {
          "Sender address did not match expected client sender=${sender.address} client=${clientAddress.address}"
        }
        sendErrorAndClose(ctx, msg)
        return
      }

      unwrapUdpResponse(ctx, msg, sender)
    } else {
      Timber.w { "Invalid message seen: $msg" }
    }
  }

  override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      Timber.d { "($id) Close inactive relay channel: $ctx" }
    } finally {
      closeChannels(ctx)
    }
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      Timber.e(cause) { "($id) exception caught $ctx" }
    } finally {
      closeChannels(ctx)
    }
  }

  override fun closeChannels(ctx: ChannelHandlerContext) {
    super.closeChannels(ctx)

    if (tcpControlChannel.isActive) {
      Timber.d { "close control channel $tcpControlChannel" }
      flushAndClose(tcpControlChannel)
    }
  }

  override fun createErrorResponse(msg: Any): Any? {
    return null
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    // Tell TCP control we are dead
    val response =
        DefaultSocks5CommandResponse(
            Socks5CommandStatus.FAILURE,
            msg.cast<Socks5CommandRequest>().requireNotNull().dstAddrType(),
        )
    tcpControlChannel.writeAndFlush(response).addListener {
      // Then close everyone
      closeChannels(ctx)
    }
  }
}
