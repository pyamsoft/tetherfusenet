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

package com.pyamsoft.tetherfi.server.proxy.manager.factory

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.proxy.manager.netty.NettyDelegatingProxyManager
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class DefaultProxyManagerFactory
@Inject
internal constructor(
    @param:ServerInternalApi private val socketBinder: SocketBinder,
    @param:Named("debug") private val isDebug: Boolean,
    private val expertPreferences: ExpertPreferences,
    private val socketTagger: SocketTagger,
    private val enforcer: ThreadEnforcer,
    private val blockedClients: BlockedClients,
    private val clientResolver: ClientResolver,
    private val allowedClients: AllowedClients,
) : ProxyManager.Factory {

  @CheckResult
  private suspend fun createNetty(
      info: BroadcastNetworkStatus.ConnectionInfo.Connected,
      isHttpEnabled: Boolean,
      isSocksEnabled: Boolean,
      port: Int,
  ): ProxyManager {
    enforcer.assertOffMainThread()

    val socketTimeout = expertPreferences.listenForSocketTimeout().first()

    Timber.d { "Using new Netty server" }
    return NettyDelegatingProxyManager(
        isDebug = isDebug,
        blockedClients = blockedClients,
        allowedClients = allowedClients,
        clientResolver = clientResolver,
        socketBinder = socketBinder,
        socketTagger = socketTagger,
        isHttpEnabled = isHttpEnabled,
        isSocksEnabled = isSocksEnabled,
        serverSocketTimeout = socketTimeout,
        hostConnection = info,
        port = port,
    )
  }

  override suspend fun create(
    type: SharedProxy.Type,
    info: BroadcastNetworkStatus.ConnectionInfo.Connected,
    port: Int,
    isHttpEnabled: Boolean,
    isSocksEnabled: Boolean
  ): ProxyManager =
      withContext(context = Dispatchers.Default) {
        return@withContext when (type) {
          SharedProxy.Type.NETTY ->
              createNetty(
                  info = info,
                  isHttpEnabled = isHttpEnabled,
                  isSocksEnabled = isSocksEnabled,
                  port = port,
              )
        }
      }
}
