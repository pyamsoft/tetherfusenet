/*
 * Copyright 2025 pyamsoft
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

package com.pyamsoft.tetherfi.server.proxy.manager

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.SocketCreator
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SharedProxy

internal interface ProxyManager {

  suspend fun loop(
      lock: Locker.Lock,
      onOpened: suspend () -> Unit,
      onClosing: suspend () -> Unit,
      onClosed: () -> Unit,
      onError: suspend (Throwable) -> Unit,
  )

  interface Factory {

    @CheckResult
    suspend fun create(
        type: SharedProxy.Type,
        info: BroadcastNetworkStatus.ConnectionInfo.Connected,
        socketCreator: SocketCreator,
        serverDispatcher: ServerDispatcher,
    ): ProxyManager
  }
}
