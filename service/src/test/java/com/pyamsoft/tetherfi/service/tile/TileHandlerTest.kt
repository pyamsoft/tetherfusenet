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

package com.pyamsoft.tetherfi.service.tile

import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.status.RunningStatus
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

private object NoopThreadEnforcer : ThreadEnforcer {
  override fun assertOffMainThread() = Unit

  override fun assertOnMainThread() = Unit
}

private class FakeBroadcastNetworkStatus(private val status: RunningStatus) :
    BroadcastNetworkStatus {
  override fun getCurrentStatus(): RunningStatus = status

  override fun onStatusChanged(): Flow<RunningStatus> = MutableStateFlow(status)

  override fun onGroupInfoChanged(): Flow<BroadcastNetworkStatus.GroupInfo> =
      MutableStateFlow(BroadcastNetworkStatus.GroupInfo.Unchanged)

  override fun onConnectionInfoChanged(): Flow<BroadcastNetworkStatus.ConnectionInfo> =
      MutableStateFlow(BroadcastNetworkStatus.ConnectionInfo.Unchanged)
}

private class FakeSharedProxy(private val status: RunningStatus) : SharedProxy {
  override fun getCurrentStatus(): RunningStatus = status

  override fun onStatusChanged(): Flow<RunningStatus> = MutableStateFlow(status)

  override suspend fun start(
      lock: com.pyamsoft.tetherfi.server.lock.Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  ) = Unit
}

private fun newHandler(
    dispatchers: AppDispatchers,
    broadcastStatus: RunningStatus,
    proxyStatus: RunningStatus,
): TileHandler =
    TileHandler(
        dispatchers = dispatchers,
        enforcer = NoopThreadEnforcer,
        networkStatus = FakeBroadcastNetworkStatus(broadcastStatus),
        proxy = FakeSharedProxy(proxyStatus),
    )

class TileHandlerTest {

  @Test
  fun `broadcast error takes precedence over everything`() {
    val broadcastError = RunningStatus.HotspotError(RuntimeException("broadcast"))
    val proxyError = RunningStatus.ProxyError(RuntimeException("proxy"))

    val handler =
        newHandler(
            broadcastStatus = broadcastError,
            proxyStatus = proxyError,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    assertSame(broadcastError, handler.getOverallStatus())
  }

  @Test
  fun `proxy error is reported when broadcast has no error`() {
    val proxyError = RunningStatus.ProxyError(RuntimeException("proxy"))

    val handler =
        newHandler(
            broadcastStatus = RunningStatus.Running,
            proxyStatus = proxyError,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    assertSame(proxyError, handler.getOverallStatus())
  }

  @Test
  fun `falls back to broadcast status when neither has an error`() {
    val handler =
        newHandler(
            broadcastStatus = RunningStatus.Starting,
            proxyStatus = RunningStatus.Running,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    assertSame(RunningStatus.Starting, handler.getOverallStatus())
  }
}
