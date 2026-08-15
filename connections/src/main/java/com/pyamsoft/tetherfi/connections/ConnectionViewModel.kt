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

package com.pyamsoft.tetherfi.connections

import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClientTracker
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientEditor
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.TransferAmount
import com.pyamsoft.tetherfi.server.clients.key
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ConnectionViewModel
@Inject
internal constructor(
    override val state: MutableConnectionViewState,
    private val allowedClients: AllowedClients,
    private val blockedClients: BlockedClients,
    private val blockTracker: BlockedClientTracker,
    private val clientEditor: ClientEditor,
    private val dispatchers: AppDispatchers,
) : ConnectionViewState by state, AbstractViewModeler<ConnectionViewState>(state) {

  fun bind(scope: CoroutineScope) {
    scope.launch(context = dispatchers.default) {
      allowedClients.listenForClients().collect { clients ->
        val list = clients.sortedBy { it.key() }
        state.connections.value = list
      }
    }

    scope.launch(context = dispatchers.default) {
      blockedClients.listenForBlocked().collect { state.blocked.value = it }
    }
  }

  fun handleToggleBlock(client: TetherClient) {
    if (blockedClients.isBlocked(client)) {
      blockTracker.unblock(client)
    } else {
      blockTracker.block(client)
    }
  }

  fun handleOpenManage(client: TetherClient, manage: ConnectionViewManagement) =
      when (manage) {
        ConnectionViewManagement.NICK_NAME -> {
          state.managingNickName.value = client
        }
        ConnectionViewManagement.TRANSFER_LIMIT -> {
          state.managingTransferLimit.value = client
        }
        ConnectionViewManagement.BANDWIDTH_LIMIT -> {
          state.managingBandwidthLimit.value = client
        }
      }

  fun handleCloseManage(manage: ConnectionViewManagement) =
      when (manage) {
        ConnectionViewManagement.NICK_NAME -> {
          state.managingNickName.value = null
        }
        ConnectionViewManagement.TRANSFER_LIMIT -> {
          state.managingTransferLimit.value = null
        }
        ConnectionViewManagement.BANDWIDTH_LIMIT -> {
          state.managingBandwidthLimit.value = null
        }
      }

  fun handleUpdateNickName(scope: CoroutineScope, nickName: String) {
    val client = state.managingNickName.value
    if (client == null) {
      Timber.w { "Cannot update nick name, no client" }
      return
    }

    scope.launch(context = dispatchers.default) {
      Timber.d { "Update client nickName: $client $nickName" }
      clientEditor.updateNickName(client, nickName)
    }
  }

  fun handleUpdateTransferLimit(scope: CoroutineScope, limit: TransferAmount?) {
    val client = state.managingTransferLimit.value
    if (client == null) {
      Timber.w { "Cannot update transfer limit, no client" }
      return
    }

    scope.launch(context = dispatchers.default) {
      Timber.d { "Update client limit: $client $limit" }
      clientEditor.updateTransferLimit(client, limit)
    }
  }

  fun handleUpdateBandwidthLimit(scope: CoroutineScope, limit: TransferAmount?) {
    val client = state.managingBandwidthLimit.value
    if (client == null) {
      Timber.w { "Cannot update bandwidth limit, no client" }
      return
    }

    scope.launch(context = dispatchers.default) {
      Timber.d { "Update client brandwidth limit: $client $limit" }
      clientEditor.updateBandwidthLimit(client, limit)
    }
  }
}
