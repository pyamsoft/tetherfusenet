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
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.netty.TestSetup
import com.pyamsoft.tetherfi.server.netty.withLogging
import com.pyamsoft.tetherfi.server.proxy.session.address
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.junit.Test

class Socks5HandlerTest {

  @CheckResult
  private fun CoroutineScope.socks5HandlerFactory(
      factoryParams: TestSetup.FactoryParams
  ): ChannelInboundHandler {
    val factory =
        Socks5ProxyHandler.factory(
            scope = this,
            isDebug = true,
            serverSocketTimeout = factoryParams.serverSocketTimeout,
            allowedClients = factoryParams.allowed,
            blockedClients = factoryParams.blocked,
            clientResolver = factoryParams.resolver,
            dispatchers = factoryParams.dispatchers,
            tcpSocketCreator = factoryParams.provideTcpChannelCreator(),
        )

    return factory.create(factoryParams.provideUdpChannelCreator())
  }

  @Test
  fun `test SOCKS5 Handler receives connections`(): Unit = runBlockingWithDelays {
    withLogging {
      var tcpConnection: Channel? = null
      val context =
          TestSetup.withHandler(
              scope = this,
              isHttpEnabled = true,
              isSocksEnabled = false,
              onTcpChannelCreated = { tcpConnection = it },
              factory = { socks5HandlerFactory(it) },
              // TODO(Peter): Do we need test dispatchers?
              dispatchers = AppDispatchers.create(),
          )

      val channel = context.channel
      Socks5ProxyHandler.applyChannelAttributes(
          channel = channel,
          client = context.resolver.ensure(channel.remoteAddress().address),
      )

      val req =
          DefaultSocks5CommandRequest(
              Socks5CommandType.CONNECT,
              Socks5AddressType.IPv4,
              // Internally does NOT allow localhost connections
              "192.168.0.1",
              43210,
          )

      channel.apply {
        writeInbound(req)
        flushInbound()
        runPendingTasks()
      }

      // Wait for tasks to finish
      delay(100.milliseconds)

      // A TCP outbound has been created
      assertNotNull(tcpConnection)
    }
  }
}
