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
import androidx.annotation.VisibleForTesting
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.resolveDnsAddress
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope

/**
 * Ensure UDP travels in order it was received
 *
 * Sometimes we send directly if it's an IP address but sometimes we start a coroutine for DNS
 *
 * Since the coroutine can finish "whenever" we need to ensure packets are sent out in the order
 * they are received
 */
internal interface UdpDestinationQueue {

  fun enqueue(
      ctx: ChannelHandlerContext,
      channelId: String,
      data: ByteBuf,
      hostName: String,
      port: Int,
      onReady: (ByteBuf, InetSocketAddress) -> Unit,
      onFailed: (ByteBuf) -> Unit,
  )

  fun clear()

  companion object {
    @JvmStatic
    @CheckResult
    fun create(
        scope: CoroutineScope,
        dispatchers: AppDispatchers,
    ): UdpDestinationQueue {
      return DefaultUdpDestinationQueue(
          scope = scope,
          dispatchers = dispatchers,
          resolveDnsAddress = { ctx, hostName, port ->
            ctx.resolveDnsAddress(
                dispatchers = dispatchers,
                hostName = hostName,
                port = port,
            )
          },
      )
    }

    @JvmStatic
    @CheckResult
    @VisibleForTesting
    fun createForTest(
        scope: CoroutineScope,
        dispatchers: AppDispatchers,
        resolveDnsAddress: suspend (ChannelHandlerContext, String, Int) -> InetSocketAddress?,
    ): UdpDestinationQueue {
      return DefaultUdpDestinationQueue(
          scope = scope,
          dispatchers = dispatchers,
          resolveDnsAddress = resolveDnsAddress,
      )
    }
  }
}
