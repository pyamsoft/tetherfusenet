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
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.DefaultProxyHandler
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
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class UdpRelayHandler
internal constructor(
    tcpControlChannel: Channel,
    serverSocketTimeout: ServerSocketTimeout,
    private val clock: Clock,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val androidPreferredNetwork: Network?,
) :
    DefaultProxyHandler(
        serverSocketTimeout = serverSocketTimeout,
    ) {

  private val upstreamMap: MutableMap<InetSocketAddress, UdpUpstream> = ConcurrentHashMap()
  private val clientAddress by lazy { tcpControlChannel.remoteAddress().cast<InetSocketAddress>() }

  private fun unwrapUdpResponse(
      ctx: ChannelHandlerContext,
      msg: DatagramPacket,
      sentFrom: InetSocketAddress,
  ) {
    val buf = msg.content()
    // Drop bad connection
    if (buf == null) {
      Timber.w { "(${channelId}) DROP: Null buffer in packet" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val reservedByteOne = buf.readByte()
    if (reservedByteOne != RESERVED_BYTE) {
      Timber.w { "(${channelId}) DROP: Expected reserve byte one, but got data: $reservedByteOne" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val reservedByteTwo = buf.readByte()
    if (reservedByteTwo != RESERVED_BYTE) {
      Timber.w { "(${channelId}) DROP: Expected reserve byte two, but got data: $reservedByteTwo" }
      sendErrorAndClose(ctx, msg)
      return
    }

    val fragment = buf.readByte()
    if (fragment != FRAGMENT_ZERO) {
      Timber.w { "(${channelId}) DROP: Fragments not supported: $fragment" }
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

    if (destinationAddr.isBlank()) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination address: $destinationAddr" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (destinationPort !in VALID_PORT_RANGE) {
      Timber.w { "(${channelId}) DROP: Invalid upstream destination port: $destinationPort" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // The rest of the packet is data
    // We must retain this slice or the underlying buffer will be cleaned up too early
    val data = buf.readRetainedSlice(buf.readableBytes())

    // Build the destination
    val destination = InetSocketAddress(destinationAddr, destinationPort)

    // TODO(Peter): Close sockets that have not seen activity in a while?
    val serverChannel = ctx.channel()
    val udpRelaySocket =
        upstreamMap.getOrPut(sentFrom) {
          val future =
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

          return@getOrPut UdpUpstream(
              upstreamFuture = future,
              lastActivityTimeMillis = 0,
          )
        }

    // Update the last active time
    udpRelaySocket.lastActivityTimeMillis = Instant.now(clock).toEpochMilli()

    val udpFuture = udpRelaySocket.upstreamFuture
    val outbound = udpFuture.channel()

    // When this socket closes, close the outbound
    serverChannel.closeFuture().addListener { outbound.flushAndClose() }

    udpFuture.addListener { future ->
      if (!future.isSuccess) {
        Timber.e(future.cause()) { "(${channelId}) Failed to standup outbound connection!" }
        data.release()
        sendErrorAndClose(ctx, msg)
        return@addListener
      }

      val packet = DatagramPacket(data, destination)
      outbound.writeAndFlush(packet).addListener { packet.release() }
    }
  }

  @CheckResult
  private fun readAddress(
      buf: ByteBuf,
      type: Socks5AddressType,
  ): String {
    try {
      when (type) {
        Socks5AddressType.IPv4 -> {
          val bytes = ByteArray(4)
          buf.readBytes(bytes)
          val addr = Inet4Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "(${channelId}) Unable to construct IPv4 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "(${channelId}) Empty address from IPv4 bytes: $addr" }
            return ""
          }

          return host
        }

        Socks5AddressType.IPv6 -> {
          val bytes = ByteArray(16)
          buf.readBytes(bytes)
          val addr = Inet6Address.getByAddress(bytes)
          if (addr == null) {
            Timber.w { "(${channelId}) Unable to construct IPv6 from byte array $bytes" }
            return ""
          }

          val host = addr.hostAddress
          if (host.isNullOrBlank()) {
            Timber.w { "(${channelId}) Empty address from IPv6 bytes: $addr" }
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
          Timber.w { "(${channelId}) Invalid datapacket address type $type" }
          return ""
        }
      }
    } catch (e: Throwable) {
      Timber.e(e) { "(${channelId}) Error when reading address from data type $type" }
      return ""
    }
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    val addr = ctx.channel().localAddress()
    setChannelId("UDP-RELAY-${addr.address}:${addr.port}")
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    // Clear the map so that we can close any UDP upstream connections
    upstreamMap.forEach { (_, udp) -> udp.upstreamFuture.channel().flushAndClose() }
    upstreamMap.clear()
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
    if (msg is DatagramPacket) {
      val sender = msg.sender()
      if (sender == null) {
        Timber.w { "(${channelId}) DROP: Null sender in packet" }
        sendErrorAndClose(ctx, msg)
        return
      }

      val client = clientAddress
      if (client == null) {
        Timber.w { "(${channelId}) DROP: SOCKS TCP control channel remote==null" }
        sendErrorAndClose(ctx, msg)
        return
      }

      // The sender of this packet is a REMOTE that we interacted with
      val isValid = client.address == sender.address
      if (!isValid) {
        Timber.w {
          "(${channelId}) DROP: Sender address did not match expected client sender=${sender.address} client=${client.address}"
        }
        sendErrorAndClose(ctx, msg)
        return
      }

      unwrapUdpResponse(ctx, msg, sender)
    } else {
      Timber.w { "(${channelId}): Invalid message seen: $msg" }
      super.channelRead(ctx, msg)
    }
  }
}
