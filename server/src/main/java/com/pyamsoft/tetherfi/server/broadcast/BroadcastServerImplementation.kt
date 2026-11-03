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

package com.pyamsoft.tetherfi.server.broadcast

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.lock.Locker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

internal interface BroadcastServerImplementation<T> {

  /**
   * Connect data source for implementation
   *
   * At the point this function is run, we already claim the lock
   */
  @CheckResult
  suspend fun withLockStartBroadcast(
      updateNetworkInfo: suspend (T) -> DelegatingBroadcastServer.UpdateResult,
  ): T

  /**
   * Connect data source for implementation
   *
   * At the point this function is run, we already claim the lock
   */
  suspend fun withLockStopBroadcast(source: T)

  /** Resolve connection info for implementation */
  @CheckResult
  suspend fun resolveCurrentConnectionInfo(source: T): BroadcastNetworkStatus.ConnectionInfo

  /** Resolve group info for implementation */
  @CheckResult suspend fun resolveCurrentGroupInfo(source: T): BroadcastNetworkStatus.GroupInfo

  /** Side effects ran from this function should have their own launch {} */
  fun onNetworkStarted(
      scope: CoroutineScope,
      lock: Locker.Lock,
      connectionStatus: Flow<BroadcastNetworkStatus.ConnectionInfo>,
  )
}
