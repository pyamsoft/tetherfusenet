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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty

import android.net.Network
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty.http.NettyHttpProxy

/** Run this with a completely new [com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager] */
class SuspendingNettyHttpProxy(
    isDebug: Boolean,
    host: String,
    port: Int,
    socketTagger: SocketTagger,
    androidPreferredNetwork: Network?,
    onOpened: () -> Unit,
    onClosing: () -> Unit,
    onError: (Throwable) -> Unit,
) : SuspendingNettyProxy() {

  private val proxy by lazy {
    NettyHttpProxy(
        isDebug = isDebug,
        host = host,
        port = port,
        socketTagger = socketTagger,
        androidPreferredNetwork = androidPreferredNetwork,
        onOpened = onOpened,
        onClosing = onClosing,
        onError = onError,
    )
  }

  override fun provideProxy(): NettyProxy {
    return proxy
  }
}
