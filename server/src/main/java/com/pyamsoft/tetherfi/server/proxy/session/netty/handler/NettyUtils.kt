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
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.TetherClient
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.traffic.ChannelTrafficShapingHandler
import io.netty.resolver.DefaultAddressResolverGroup
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

internal fun ChannelHandlerContext.attachIdleStateHandler(
    serverSocketTimeout: ServerSocketTimeout
) {
  val self = this
  val timeout = serverSocketTimeout.timeoutDuration
  if (!timeout.isInfinite()) {
    val pipeline = self.pipeline()

    // Since a single channel can have multiple handlers, we don't want to attach duplicates
    if (pipeline.get(IdleStateHandler::class.java) == null) {
      pipeline.addFirst(IdleStateHandler(0, 0, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    }
  }
}

internal inline fun ChannelHandlerContext.handleIdleState(
    evt: Any,
    block: () -> Unit,
) {
  if (evt is IdleStateEvent) {
    if (evt.state() == IdleState.ALL_IDLE) {
      block()
    }
  }
}

internal fun <T : ChannelHandler> ChannelPipeline.dropHandler(c: KClass<T>) {
  val self = this
  val javaClass = c.java
  if (self.get(javaClass) != null) {
    self.remove(javaClass)
  }
}

internal fun Channel.flushAndClose() {
  val self = this
  if (self.isOpen) {
    self.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
  }
}

internal fun ChannelFuture.flushAndClose() {
  val self = this
  self.channel().flushAndClose()
}

internal fun ChannelHandlerContext.flushAndClose() {
  val self = this
  self.channel().flushAndClose()
}

@CheckResult
internal fun Int.zeroOrAmountAsLong(): Long {
  // TODO can we avoid the cast to long
  return if (this <= 0) 0L else this.toLong()
}

/**
 * We can apply a bandwidth limit at the delegating socket level
 *
 * This way we don't have to actually throttle connections out to the Internet, we can just throttle
 * connections at the Proxy interaction level
 */
internal fun ChannelPipeline.applyBandwidthLimitFor(client: TetherClient) {
  val self = this

  // Rate Limiting (inline for performance)
  val bandwidthLimit = client.bandwidthLimit?.bytes ?: 0L
  val mustEnforceBandwidthLimit = bandwidthLimit > 0
  if (mustEnforceBandwidthLimit) {
    self.addLast(
        ChannelTrafficShapingHandler(
            // Write limit in bytes/sec
            bandwidthLimit,
            // Read limit in bytes/sec
            bandwidthLimit,
        ),
    )
  }
}

@CheckResult
private suspend fun resolveDnsAddress(
  dispatchers: AppDispatchers,
  executor: EventExecutor,
  hostName: String, port: Int,
): InetSocketAddress? = withContext(context = dispatchers.io) {
  try {
    // Must be unresolved or else this would ALSO trigger a DNS blocking request
    val destination = InetSocketAddress.createUnresolved(hostName, port)
    val resolver = DefaultAddressResolverGroup.INSTANCE.getResolver(executor)
    return@withContext resolver.resolve(destination).get()
  } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
    Timber.e(e) { "Failed to resolve address for connect: $hostName:$port" }
    return@withContext null
  }
}

@CheckResult
internal suspend fun ChannelHandlerContext.resolveDnsAddress(
  dispatchers: AppDispatchers,
  hostName: String, port: Int,
): InetSocketAddress? = resolveDnsAddress(
  dispatchers = dispatchers,
  executor = executor(),
  hostName = hostName,
  port = port,
)

@CheckResult
internal suspend fun Channel.resolveDnsAddress(
  dispatchers: AppDispatchers,
  hostName: String, port: Int,
): InetSocketAddress? {
  val self = this
  return resolveDnsAddress(
    dispatchers = dispatchers,
    executor = self.eventLoop(),
    hostName = hostName,
    port = port,
  )
}