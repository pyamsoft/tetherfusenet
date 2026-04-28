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

package com.pyamsoft.tetherfi.server.netty

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.SuspendingNettyDelegatingProxy
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.ChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.TcpChannelCreator
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.channel.UdpChannelCreator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.nio.NioIoHandler
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@ConsistentCopyVisibility
data class IntHolder
internal constructor(
    private var value: Int = 0,
) {

  @CheckResult
  fun get(): Int {
    return value
  }

  fun inc() {
    ++value
  }
}

@ConsistentCopyVisibility
data class NettyTestContext
internal constructor(
    val job: Job,
    val scope: CoroutineScope,
    var openCount: IntHolder,
    var closingCount: IntHolder,
    var closedCount: IntHolder,
    var errorCount: IntHolder,
)

private class TestEmbeddedChannel(
    vararg handlers: ChannelHandler,
) : EmbeddedChannel(*handlers) {

  private val server = InetSocketAddress("127.0.0.1", 8228)
  private val remote = InetSocketAddress("127.0.0.2", 12345)

  override fun localAddress0(): SocketAddress {
    return server
  }

  override fun remoteAddress0(): SocketAddress {
    return remote
  }
}

private data class TestChannelCreator(
    private val impl: ChannelCreator,
    private val onChannelCreated: (Channel) -> Unit,
) : ChannelCreator {

  override fun bind(onChannelInitialized: (Channel) -> Unit): ChannelFuture =
      impl.bind { channel ->
        onChannelCreated(channel)
        onChannelInitialized(channel)
      }

  override fun connect(hostName: String, port: Int, onChannelInitialized: (Channel) -> Unit) =
      impl.connect(
          hostName = hostName,
          port = port,
          onChannelInitialized = { channel ->
            onChannelCreated(channel)
            onChannelInitialized(channel)
          },
      )
}

internal object TestSetup {

  @CheckResult
  private fun ChannelCreator.wrap(onChannelCreated: (Channel) -> Unit): ChannelCreator {
    return TestChannelCreator(
        impl = this,
        onChannelCreated = onChannelCreated,
    )
  }

  data class FactoryParams(
      val isHttpEnabled: Boolean,
      val isSocksEnabled: Boolean,
      val allowed: AllowedClients,
      val blocked: BlockedClients,
      val resolver: ClientResolver,
      val serverSocketTimeout: ServerSocketTimeout,
      val provideTcpChannelCreator: () -> ChannelCreator,
      val provideUdpChannelCreator: () -> ChannelCreator,
  )

  data class HandlerContext(
      val channel: EmbeddedChannel,
      val allowed: AllowedClients,
      val resolver: ClientResolver,
  )

  internal fun withHandler(
      isHttpEnabled: Boolean,
      isSocksEnabled: Boolean,
      onTcpChannelCreated: (Channel) -> Unit = {},
      onUdpChannelCreated: (Channel) -> Unit = {},
      factory: (FactoryParams) -> ChannelInboundHandler,
  ): HandlerContext {
    val allowed =
        object : AllowedClients {
          override fun listenForClients(): Flow<List<TetherClient>> {
            return flowOf(emptyList())
          }

          override fun seen(client: TetherClient) {}

          override fun reportTransfer(client: TetherClient, report: ByteTransferReport) {}
        }

    val blocked =
        object : BlockedClients {
          override fun listenForBlocked(): Flow<Collection<TetherClient>> {
            return flowOf(emptyList())
          }

          override fun isBlocked(client: TetherClient): Boolean {
            return false
          }
        }

    val resolver =
        object : ClientResolver {

          private val clients = mutableMapOf<String, TetherClient>()

          override fun ensure(hostNameOrIp: String): TetherClient {
            return clients.getOrPut(hostNameOrIp) {
              TetherClient.create(
                  hostNameOrIp,
                  clock = Clock.systemDefaultZone(),
              )
            }
          }
        }

    val socketTagger = SocketTagger {}

    val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

    val tcpSocketCreator =
        TcpChannelCreator(
            eventLoop = workerGroup,
            socketTagger = socketTagger,
            androidPreferredNetwork = null,
        )

    val udpSocketCreator =
        UdpChannelCreator(
            eventLoop = workerGroup,
            socketTagger = socketTagger,
            androidPreferredNetwork = null,
        )

    val channel =
        TestEmbeddedChannel(
            factory(
                FactoryParams(
                    isHttpEnabled = isHttpEnabled,
                    isSocksEnabled = isSocksEnabled,
                    allowed = allowed,
                    blocked = blocked,
                    resolver = resolver,
                    serverSocketTimeout = ServerSocketTimeout.Defaults.BALANCED,
                    provideTcpChannelCreator = {
                      tcpSocketCreator.wrap { onTcpChannelCreated(it) }
                    },
                    provideUdpChannelCreator = {
                      udpSocketCreator.wrap { onUdpChannelCreated(it) }
                    },
                )
            )
        )

    return HandlerContext(
        channel = channel,
        allowed = allowed,
        resolver = resolver,
    )
  }

  suspend fun withNetty(
      hostName: String = "127.0.0.1",
      port: Int = 8228,
      isLoggingEnabled: Boolean = true,
      block: suspend NettyTestContext.(SuspendingNettyDelegatingProxy) -> Unit,
  ) {
    val blocked =
        object : BlockedClients {
          override fun listenForBlocked(): Flow<Collection<TetherClient>> {
            return flowOf(emptyList())
          }

          override fun isBlocked(client: TetherClient): Boolean {
            return false
          }
        }

    val allowed =
        object : AllowedClients {
          override fun listenForClients(): Flow<List<TetherClient>> {
            return flowOf(emptyList())
          }

          override fun seen(client: TetherClient) {}

          override fun reportTransfer(client: TetherClient, report: ByteTransferReport) {}
        }

    val resolver =
        object : ClientResolver {

          private val clients = mutableMapOf<String, TetherClient>()

          override fun ensure(hostNameOrIp: String): TetherClient {
            return clients.getOrPut(hostNameOrIp) {
              TetherClient.create(
                  hostNameOrIp,
                  clock = Clock.systemDefaultZone(),
              )
            }
          }
        }

    val socketTagger = SocketTagger {}

    val openCount = IntHolder()
    val closingCount = IntHolder()
    val closedCount = IntHolder()
    val errorCount = IntHolder()

    val proxy =
        SuspendingNettyDelegatingProxy(
            host = hostName,
            port = port,
            blockedClients = blocked,
            allowedClients = allowed,
            clientResolver = resolver,
            isDebug = true,
            socketTagger = socketTagger,
            androidPreferredNetwork = null,
            isHttpEnabled = true,
            isSocksEnabled = true,
            serverSocketTimeout = ServerSocketTimeout.Defaults.BALANCED,
            onOpened = { openCount.inc() },
            onClosing = { closingCount.inc() },
            onClosed = { closedCount.inc() },
            onError = { errorCount.inc() },
        )

    withLogging(
        isLoggingEnabled = isLoggingEnabled,
    ) {
      // Need a real scope for the Netty server to actually start
      val nettyScope = CoroutineScope(context = Dispatchers.Default)
      try {
        // Before job starts, callbacks have not run
        assert(openCount.get() == 0)
        assert(closingCount.get() == 0)
        assert(closedCount.get() == 0)
        assert(errorCount.get() == 0)

        val nettyJob = nettyScope.launch { proxy.start(scope = nettyScope) }

        // Assert netty is alive
        assert(nettyJob.isActive)

        try {
          val nettyContext =
              NettyTestContext(
                  job = nettyJob,
                  scope = nettyScope,
                  openCount = openCount,
                  closingCount = closingCount,
                  closedCount = closedCount,
                  errorCount = errorCount,
              )
          nettyContext.block(proxy)
        } finally {
          // Now dead
          nettyJob.cancelAndJoin()
        }

        assert(!nettyJob.isActive)

        assert(openCount.get() == 1)
        assert(closingCount.get() == 1)

        // Full close event takes a little bit of time
        while (closedCount.get() <= 0) {
          delay(100.milliseconds)
        }

        // Finally closed!
        assert(closedCount.get() == 1)

        // Don't check errors
      } finally {
        nettyScope.cancel()
      }
    }
  }
}
