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

package com.pyamsoft.tetherfi.server.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.netty.TestSetup
import com.pyamsoft.tetherfi.server.netty.withLogging
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks4ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.ktor.util.network.address
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandRequest
import io.netty.handler.codec.socksx.v4.Socks4CommandType
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import org.junit.Test

class Socks4HandlerTest {

  @CheckResult
  private fun CoroutineScope.socks4HandlerFactory(
      factory: TestSetup.FactoryParams
  ): ChannelInboundHandler {
    val factory =
        Socks4ProxyHandler.factory(
            scope = this,
            isDebug = true,
            serverSocketTimeout = factory.serverSocketTimeout,
            allowedClients = factory.allowed,
            tcpSocketCreator = factory.provideTcpChannelCreator(),
        )

    return factory.create(Unit)
  }

  @Test
  fun `test SOCKS4A Handler receives connections`(): Unit = runBlockingWithDelays {
    withLogging {
      var tcpConnection: Channel? = null
      val context =
        TestSetup.withHandler(
          isHttpEnabled = true,
          isSocksEnabled = false,
          onTcpChannelCreated = { tcpConnection = it },
          factory = { socks4HandlerFactory(it) },
        )
      val channel = context.channel

      Socks4ProxyHandler.applyChannelAttributes(
        channel = channel,
        client = context.resolver.ensure(context.channel.remoteAddress().address),
      )

      val req =
        DefaultSocks4CommandRequest(
          Socks4CommandType.CONNECT,
          "127.0.0.1",
          43210,
        )

      channel.apply {
        writeInbound(req)
        flushInbound()
        runPendingTasks()
      }

      // A TCP outbound has been created
      assertNotNull(tcpConnection)
    }
  }
}