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
import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.proxy.session.tcp.http.netty.SuspendingNettyProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

abstract class NettyProxyManager
protected constructor(
    private val socketBinder: SocketBinder,
    private val hostConnection: BroadcastNetworkStatus.ConnectionInfo.Connected,
    private val port: Int,
) : ProxyManager {

  final override suspend fun loop(
      lock: Locker.Lock,
      onOpened: suspend () -> Unit,
      onClosing: suspend () -> Unit,
      onError: suspend (Throwable) -> Unit,
  ) {
    val releaser = lock.acquire()

    try {
      socketBinder.withMobileDataNetworkActive { binder ->
        val network = binder.getNetwork()

        coroutineScope {
          val server =
              provideProxy(
                  network = network,
                  onOpened = onOpened,
                  onClosing = onClosing,
                  onError = onError,
              )

          Timber.d { "Netty server started: ${hostConnection.hostName} $port" }
          server.start()
          Timber.d { "Netty server stopped" }
        }
      }
    } finally {
      Timber.d { "Wakelock released!" }
      releaser.release()
    }
  }

  @CheckResult
  protected abstract fun CoroutineScope.provideProxy(
      network: Network?,
      onOpened: suspend () -> Unit,
      onClosing: suspend () -> Unit,
      onError: suspend (Throwable) -> Unit,
  ): SuspendingNettyProxy
}
