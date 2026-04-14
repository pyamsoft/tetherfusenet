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
import com.pyamsoft.tetherfi.server.HOSTNAME
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.netty.SuspendingNettyDelegatingProxy
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
import timber.log.Timber

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

suspend fun withLogging(
    isLoggingEnabled: Boolean = true,
    block: suspend () -> Unit,
) {
  var tree: Timber.Tree? = null
  if (isLoggingEnabled) {
    val t =
        object : Timber.Tree() {
          override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            t?.printStackTrace()
            println(message)
          }
        }
    tree = t
    Timber.plant(t)
  }

  try {
    block()
  } finally {
    tree?.also { Timber.uproot(it) }
  }
}

suspend fun withNetty(
    hostName: String = HOSTNAME,
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
