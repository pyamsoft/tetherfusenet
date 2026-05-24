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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.isIpAddress
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import io.netty.util.NetUtil
import io.netty.util.ReferenceCountUtil
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.IOException

internal abstract class ProxyHandler
internal constructor(
    protected val isDebug: Boolean,
    protected val scope: CoroutineScope,
    protected val serverSocketTimeout: ServerSocketTimeout,
) : ChannelInboundHandlerAdapter() {

  @Volatile private var channelId = ""

  protected inline fun applyChannelId(block: () -> String) {
    if (channelId.isBlank()) {
      setChannelId(block())
    }
  }

  @CheckResult
  protected fun getChannelId(): String {
    if (channelId.isBlank()) {
      setChannelId("CHANNEL-UNKNOWN")
    }
    return channelId
  }

  protected fun setChannelId(id: String) {
    channelId = id
  }

  protected fun closeChannels(ctx: ChannelHandlerContext) {
    onCloseChannels(ctx)

    val channel = ctx.channel()
    channel.apply { attr(CLIENT).set(null) }

    channel.flushAndClose()
  }

  final override fun channelActive(ctx: ChannelHandlerContext) {
    try {
      onChannelActive(ctx)
      ctx.attachIdleStateHandler(serverSocketTimeout)
    } finally {
      super.channelActive(ctx)
    }
  }

  final override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    try {
      val channelId = getChannelId()

      ctx.handleIdleState(evt) {
        Timber.d { "(${channelId}): Close channel after idle timeout" }
        closeChannels(ctx)
      }
    } finally {
      super.userEventTriggered(ctx, evt)
    }
  }

  final override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      val channelId = getChannelId()
      var log = true

      // We don't care to log connection reset, they happen all the time
      if (cause is IOException) {
        if (cause.message == CONNECTION_RESET_MESSAGE) {
          log = false
        }
      }

      if (log) {
        Timber.e(cause) { "($channelId): Exception caught! Close channel" }
      }
    } finally {
      closeChannels(ctx)
    }
  }

  final override fun channelInactive(ctx: ChannelHandlerContext) {
    try {
      val channelId = getChannelId()

      Timber.d { "($channelId): Inactive! Close channel" }
      closeChannels(ctx)
    } finally {

      // Reset channel ID after inactive
      channelId = ""

      super.channelInactive(ctx)
    }
  }

  protected open fun onCloseChannels(ctx: ChannelHandlerContext) {}

  protected open fun onChannelActive(ctx: ChannelHandlerContext) {}

  protected abstract fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any)

  companion object {

    private const val CONNECTION_RESET_MESSAGE = "Connection reset by peer"

    private const val IPV4_LOCALHOST_BYTE_IDENTIFIER = 127.toByte()
    private val IPV6_LOCALHOST_BYTE_ARRAY = byteArrayOf(
      0, 0, 0, 0,
      0, 0, 0, 0,
      0, 0, 0, 0,
      0, 0, 0, 1,
    )

    @JvmStatic protected val VALID_PORT_RANGE = 1..65535

    @CheckResult
    private fun isByteAddressLocalhostAddress(bytes: ByteArray): Boolean {
      if (bytes.contentEquals(NetUtil.LOCALHOST4.address)) {
        return true
      }

      if (bytes.contentEquals(NetUtil.LOCALHOST6.address)) {
        return true
      }

      return when (bytes.size) {
        // IPv4 - is this a 127.X.X.X
        4 -> bytes[0] == IPV4_LOCALHOST_BYTE_IDENTIFIER
        // IPv6 - is this ::1 (covered by the if statement but just in case this logic changes in the future?)
        16 -> bytes.contentEquals(IPV6_LOCALHOST_BYTE_ARRAY)
        // What the fuck lol
        else -> false
      }
    }

    /**
     * Block localhost and localdomain addressing
     */
    @CheckResult
    private fun isLocalBlockedAddress(ipLiteral: String): Boolean {
      val bytes = NetUtil.createByteArrayFromIpAddressString(ipLiteral)
      if (bytes == null) {
        // Was not a valid ip address
        Timber.w { "Not a valid IP address: $ipLiteral" }
        return false
      }

      // Fast compare
      if (isByteAddressLocalhostAddress(bytes)) {
        return true
      }

      // Otherwise slower compare
      // NON-BLOCKING :)
      val address = InetAddress.getByAddress(bytes)

      // No loopback, no wildcard (ipv6)
      return address.isLoopbackAddress || address.isAnyLocalAddress
    }

    /**
     * Sending traffic to the localhost is NOT a valid destination
     */
    @JvmStatic
    @CheckResult
    protected fun isBlockedLocalAddress(hostOrIp: String): Boolean {
      // Reject "localhost"
      if (hostOrIp.equals("localhost", ignoreCase = true)) {
        return true
      }

      // Reject ".localdomain"
      if (hostOrIp.equals("localhost.localdomain", ignoreCase = true)) {
        return true
      }

      // Only check IP literals — hostname DNS resolution happens inside Netty at connect time
      val isLiteralIpAddress = isIpAddress(hostOrIp)
      if (isLiteralIpAddress) {
        return isLocalBlockedAddress(hostOrIp)
      }

      // TODO(Peter): Do we want to check hostnames here?
      //              Is there ever a situation where a client could be assigned a host name
      //              No? since we are just dealing with DNS
      //              Do we really care that much?
      return false
    }

    @JvmStatic
    @CheckResult
    protected fun getTetherClient(ctx: ChannelHandlerContext): TetherClient? {
      return ctx.channel().attr(CLIENT).get()
    }

    inline fun releaseMsgOnChannelReadError(msg: Any, block: () -> Unit) {
      try {
        block()
      } catch (e: Throwable) {
        ReferenceCountUtil.release(msg)
        throw e
        // Re-throw to be caught by Handler.exceptionCaught
      }
    }

    @JvmStatic
    private val CLIENT: AttributeKey<TetherClient> =
        AttributeKey.newInstance("${ProxyHandler::class.simpleName}-CLIENT")

    @JvmStatic
    protected fun applyChannelAttributes(
        channel: Channel,
        client: TetherClient,
    ) {
      channel.apply { attr(CLIENT).set(client) }
    }
  }
}
