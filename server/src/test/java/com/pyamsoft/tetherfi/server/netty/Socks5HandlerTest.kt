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
import com.pyamsoft.tetherfi.server.proxy.session.netty.handler.socks.Socks5ProxyHandler
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import io.ktor.util.network.address
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandler
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
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
            clientResolver = factoryParams.resolver,
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
              isHttpEnabled = true,
              isSocksEnabled = false,
              onTcpChannelCreated = { tcpConnection = it },
              factory = { socks5HandlerFactory(it) },
          )
      val channel = context.channel

      Socks5ProxyHandler.applyChannelAttributes(
          channel = channel,
          client = context.resolver.ensure(context.channel.remoteAddress().address),
      )

      val req =
          DefaultSocks5CommandRequest(
              Socks5CommandType.CONNECT,
              Socks5AddressType.IPv4,
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
