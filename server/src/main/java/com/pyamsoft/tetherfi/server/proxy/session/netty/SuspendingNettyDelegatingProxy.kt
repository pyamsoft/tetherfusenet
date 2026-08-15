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

package com.pyamsoft.tetherfi.server.proxy.session.netty

import android.net.Network
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.proxy.SocketTagger

/** Run this with a completely new [com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager] */
class SuspendingNettyDelegatingProxy(
    isDebug: Boolean,
    host: String,
    port: Int,
    blockedClients: BlockedClients,
    clientResolver: ClientResolver,
    allowedClients: AllowedClients,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    isHttpEnabled: Boolean,
    isSocksEnabled: Boolean,
    dispatchers: AppDispatchers,
    serverSocketTimeout: ServerSocketTimeout,
    onOpened: () -> Unit,
    onClosing: () -> Unit,
    onClosed: () -> Unit,
    onError: (Throwable) -> Unit,
) : SuspendingNettyProxy() {

  private val proxy by lazy {
    NettyDelegatingProxy(
        isDebug = isDebug,
        host = host,
        port = port,
        clientResolver = clientResolver,
        blockedClients = blockedClients,
        allowedClients = allowedClients,
        socketTagger = socketTagger,
        androidPreferredNetwork = androidPreferredNetwork,
        isHttpEnabled = isHttpEnabled,
        isSocksEnabled = isSocksEnabled,
        serverSocketTimeout = serverSocketTimeout,
        dispatchers = dispatchers,
        onOpened = onOpened,
        onClosing = onClosing,
        onClosed = onClosed,
        onError = onError,
    )
  }

  override fun provideProxy(): NettyProxy {
    return proxy
  }
}
