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
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.runInEventLoop
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.util.NetUtil
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ensure UDP travels in order it was received, per destination
 *
 * Sometimes we send directly if it's an IP address but sometimes we start a coroutine for DNS
 *
 * Since the coroutine can finish "whenever" we need to ensure packets are sent out in the order
 * they are received
 */
internal class DefaultUdpDestinationQueue(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val resolveDnsAddress:
        suspend (ChannelHandlerContext, String, Int) -> InetSocketAddress?,
) : UdpDestinationQueue {

  // As long as you always call the same queue from the same UDP handler on the same Netty thread,
  // this should not need a lock :)
  private var pending: MutableMap<String, ArrayDeque<PendingPacket>>? = null

  private fun deliverResult(
      key: String,
      waiting: ArrayDeque<PendingPacket>,
      resolved: InetSocketAddress?,
  ) {
    while (true) {
      val packet = waiting.removeFirstOrNull() ?: break
      if (resolved == null) {
        packet.onFailed(packet.data)
      } else {
        packet.onReady(packet.data, resolved)
      }
    }

    // A failed delivery can clear() us, only drop the key if it is still ours
    val map = pending
    if (map != null) {
      if (map[key] === waiting) {
        map.remove(key)
      }
    }
  }

  @CheckResult
  private fun resolveQueueKey(hostName: String, port: Int): String {
    return "$hostName:$port"
  }

  override fun enqueue(
      ctx: ChannelHandlerContext,
      channelId: String,
      data: ByteBuf,
      hostName: String,
      port: Int,
      onReady: (ByteBuf, InetSocketAddress) -> Unit,
      onFailed: (ByteBuf) -> Unit,
  ) {
    // Literally an IP address, fast path without coroutine
    val literal = NetUtil.createInetAddressFromIpAddressString(hostName)
    if (literal != null) {
      onReady(data, InetSocketAddress(literal, port))
      return
    }

    val packet =
        PendingPacket(
            data = data,
            onReady = onReady,
            onFailed = onFailed,
        )

    val key = resolveQueueKey(hostName, port)

    // A lookup for this destination is already running, wait behind it
    val existing = pending?.get(key)
    if (existing != null) {
      existing.addLast(packet)
      return
    }

    // Otherwise we create a new queue
    val waiting = ArrayDeque<PendingPacket>().apply { addLast(packet) }

    // Initialize the map lazily here
    val map = pending ?: mutableMapOf<String, ArrayDeque<PendingPacket>>().also { pending = it }
    map[key] = waiting

    // We either deliver a result (DNS resolved or not) and the channel closes us
    //
    // Or we error out, and the channel closes us
    scope
        .launch(context = dispatchers.io) {
          // Could be an address, could not be if DNS fails
          val resolved =
              try {
                resolveDnsAddress(ctx, hostName, port)
              } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
                e.ifNotCancellation {
                  Timber.e(e) { "(${channelId}) Error during UDP destination DNS resolution" }
                  null
                }
              }

          try {
            ctx.executor().runInEventLoop { deliverResult(key, waiting, resolved) }
          } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
            Timber.e(e) { "(${channelId}) Unable to return to event loop" }
          }
        }
        .invokeOnCompletion { throwable ->
          if (throwable != null) {
            if (throwable !is CancellationException) {
              Timber.e(throwable) { "(${channelId}) Error during UDP destination resolve" }
            }
          }
        }
  }

  override fun clear() {
    val map = pending
    if (map != null) {
      for (waiting in map.values) {
        while (true) {
          val packet = waiting.removeFirstOrNull() ?: break
          packet.data.release()
        }
      }
      map.clear()
    }

    pending = null
  }

  private class PendingPacket(
      val data: ByteBuf,
      val onReady: (ByteBuf, InetSocketAddress) -> Unit,
      val onFailed: (ByteBuf) -> Unit,
  )
}
