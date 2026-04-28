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
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import io.ktor.util.network.address
import io.ktor.util.network.port
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.util.AttributeKey
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class RelayHandler
private constructor(
    isDebug: Boolean,
    scope: CoroutineScope,
    serverSocketTimeout: ServerSocketTimeout,
    private val allowedClients: AllowedClients,
    private val blockedClients: BlockedClients,
) :
    ProxyHandler(
        scope = scope,
        serverSocketTimeout = serverSocketTimeout,
        isDebug = isDebug,
    ) {

  // Your single socket read payload isn't THAT LARGE right???
  @Volatile private var bytesMoved: Int = 0

  private var byteCountJob: Job? = null

  @CheckResult
  private fun getWritebackChannel(ctx: ChannelHandlerContext): Channel? {
    return ctx.channel().attr(WRITE_BACK_CHANNEL).get()
  }

  @CheckResult
  private fun getDirection(ctx: ChannelHandlerContext): Direction? {
    return ctx.channel().attr(DIRECTION).get()
  }

  @CheckResult
  private fun getChannelTag(ctx: ChannelHandlerContext): String? {
    return ctx.channel().attr(TAG).get()
  }

  private fun ensureChannelTag(ctx: ChannelHandlerContext) {
    applyChannelId {
      val tag = getChannelTag(ctx)
      if (tag == null) {
        val local = ctx.channel().localAddress()
        return@applyChannelId "RELAY-${local.address}:${local.port}"
      } else {
        return@applyChannelId tag
      }
    }
  }

  private fun produceByteReport(client: TetherClient, direction: Direction) {
    val count = bytesMoved

    // Reset back to zero or we keep double counting
    bytesMoved = 0

    var internetToProxy = 0
    var proxyToInternet = 0
    when (direction) {
      Direction.INBOUND -> internetToProxy = count
      Direction.OUTBOUND -> proxyToInternet = count
    }

    allowedClients.reportTransfer(
        client = client,
        report =
            ByteTransferReport(
                // TODO can we avoid the cast to long
                internetToProxy = internetToProxy.zeroOrAmountAsLong(),
                proxyToInternet = proxyToInternet.zeroOrAmountAsLong(),
            ),
    )
  }

  override fun onCloseChannels(ctx: ChannelHandlerContext) {
    // Cancel the report looping job
    byteCountJob?.cancel()
    byteCountJob = null

    // One last report before we close
    val client = getTetherClient(ctx)
    if (client != null) {
      val direction = getDirection(ctx)
      if (direction != null) {
        produceByteReport(client = client, direction = direction)
      }
    }

    ctx.flushAndClose()
    getWritebackChannel(ctx)?.flushAndClose()

    ctx.channel().apply {
      attr(TAG).set(null)
      attr(WRITE_BACK_CHANNEL).set(null)
      attr(DIRECTION).set(null)
    }
  }

  override fun onChannelActive(ctx: ChannelHandlerContext) {
    // Just in case we don't actually have the client or direction yet
    // resolve in the loop
    var client = getTetherClient(ctx)
    var direction = getDirection(ctx)

    byteCountJob?.cancel()
    byteCountJob =
        scope.launch(context = Dispatchers.IO) {
          while (isActive) {
            // Don't report too often
            delay(10.seconds)

            if (client == null) {
              client = getTetherClient(ctx)
            }

            if (direction == null) {
              direction = getDirection(ctx)
            }

            client?.also { c ->
              direction?.also { d -> produceByteReport(client = c, direction = d) }
            }
          }
        }
  }

  override fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any) {
    val channelId = getChannelId()

    // Can't do as this is a bytes based implementation
    Timber.w { "(${channelId}) Can't send generic error on RelayHandler" }
    ctx.flushAndClose()
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    ensureChannelTag(ctx)
    val channelId = getChannelId()

    val writeToChannel = getWritebackChannel(ctx)
    if (writeToChannel == null) {
      Timber.w { "($channelId): channelRead writeToChannel is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (!writeToChannel.isActive) {
      Timber.w { "($channelId): channelRead writeToChannel is not active" }
      sendErrorAndClose(ctx, msg)
      return
    }

    if (msg is ByteBuf) {
      // Grab the amount BEFORE the data buffer is released
      val amountMoved = msg.readableBytes()

      // Keep count
      bytesMoved += amountMoved
    }

    val client = getTetherClient(ctx)
    if (client == null) {
      Timber.w { "($channelId) DROP: TetherClient is NULL" }
      sendErrorAndClose(ctx, msg)
      return
    }

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "($channelId) DROP: client was blocked: $client" }
      sendErrorAndClose(ctx, msg)
      return
    }

    scope.launch(context = Dispatchers.IO) { allowedClients.seen(client) }

    writeToChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      ensureChannelTag(ctx)
      val channelId = getChannelId()

      val writeToChannel = getWritebackChannel(ctx)
      if (writeToChannel == null) {
        Timber.w { "($channelId): channelWritabilityChanged writeToChannel is NULL" }
        return
      }

      val isWritable = ctx.channel().isWritable
      Timber.d { "($channelId) Relay write changed: $ctx $isWritable" }
      writeToChannel.config().isAutoRead = isWritable
    } finally {
      super.channelWritabilityChanged(ctx)
    }
  }

  internal enum class Direction {
    INBOUND,
    OUTBOUND,
  }

  companion object {

    @JvmStatic
    private val WRITE_BACK_CHANNEL: AttributeKey<Channel> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-WRITE_BACK_CHANNEL")

    @JvmStatic
    private val TAG: AttributeKey<String> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-ID")

    @JvmStatic
    private val DIRECTION: AttributeKey<Direction> =
        AttributeKey.newInstance("${RelayHandler::class.simpleName}-DIRECTION")

    @JvmStatic
    @CheckResult
    fun factory(
        isDebug: Boolean,
        scope: CoroutineScope,
        allowedClients: AllowedClients,
        blockedClients: BlockedClients,
        serverSocketTimeout: ServerSocketTimeout,
    ): HandlerFactory<Unit> {
      return {
        RelayHandler(
            isDebug = isDebug,
            scope = scope,
            allowedClients = allowedClients,
            blockedClients = blockedClients,
            serverSocketTimeout = serverSocketTimeout,
        )
      }
    }

    fun applyChannelAttributes(
        channel: Channel,
        writeBackChannel: Channel,
        direction: Direction,
        tag: String,
        client: TetherClient,
    ) {
      channel.apply {
        attr(TAG).set(tag)
        attr(WRITE_BACK_CHANNEL).set(writeBackChannel)
        attr(DIRECTION).set(direction)
      }

      applyChannelAttributes(
          channel = channel,
          client = client,
      )
    }
  }
}
