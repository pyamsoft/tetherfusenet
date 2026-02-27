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

package com.pyamsoft.tetherfi.server.proxy.manager.netty

import android.net.Network
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty.SuspendingNettyHttpProxy
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty.SuspendingNettyProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NettyHttpProxyManager
internal constructor(
    socketBinder: SocketBinder,
    private val isDebug: Boolean,
    private val socketTagger: SocketTagger,
    private val hostConnection: BroadcastNetworkStatus.ConnectionInfo.Connected,
    private val port: Int,
) :
    NettyProxyManager(
        socketBinder = socketBinder,
        hostConnection = hostConnection,
        port = port,
    ) {

  override fun CoroutineScope.provideProxy(
      network: Network?,
      onOpened: suspend () -> Unit,
      onClosing: suspend () -> Unit,
      onError: suspend (Throwable) -> Unit,
  ): SuspendingNettyProxy {
    return SuspendingNettyHttpProxy(
        isDebug = isDebug,
        host = hostConnection.hostName,
        port = port,
        socketTagger = socketTagger,
        androidPreferredNetwork = network,
        onOpened = { launch { onOpened() } },
        onClosing = { launch { onClosing() } },
        onError = { launch { onError(it) } },
    )
  }
}
