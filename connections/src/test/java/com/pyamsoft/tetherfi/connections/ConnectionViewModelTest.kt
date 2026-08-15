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

import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClientTracker
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientEditor
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.TransferAmount
import com.pyamsoft.tetherfi.server.clients.TransferUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

private fun clientFor(ip: String): TetherClient =
    TetherClient.create(hostNameOrIp = ip, clock = FIXED_CLOCK)

private class FakeAllowedClients(
    private val clients: MutableStateFlow<Collection<TetherClient>> = MutableStateFlow(emptyList()),
) : AllowedClients {
  override fun listenForClients(): Flow<Collection<TetherClient>> = clients

  override fun seen(client: TetherClient) = Unit

  override fun reportTransfer(client: TetherClient, report: ByteTransferReport) = Unit
}

private class FakeBlockedClients(
    private val blocked: MutableStateFlow<Collection<TetherClient>> = MutableStateFlow(emptySet()),
    private val isBlockedResult: Boolean = false,
) : BlockedClients {
  override fun listenForBlocked(): Flow<Collection<TetherClient>> = blocked

  override fun isBlocked(client: TetherClient): Boolean = isBlockedResult
}

private class FakeBlockedClientTracker : BlockedClientTracker {
  val blockCalls = mutableListOf<TetherClient>()
  val unblockCalls = mutableListOf<TetherClient>()

  override fun block(client: TetherClient) {
    blockCalls.add(client)
  }

  override fun unblock(client: TetherClient) {
    unblockCalls.add(client)
  }
}

private class FakeClientEditor : ClientEditor {
  val nickNameCalls = MutableStateFlow<List<Pair<TetherClient, String>>>(emptyList())
  val transferLimitCalls = MutableStateFlow<List<Pair<TetherClient, TransferAmount?>>>(emptyList())
  val bandwidthLimitCalls = MutableStateFlow<List<Pair<TetherClient, TransferAmount?>>>(emptyList())

  override suspend fun updateNickName(client: TetherClient, nickName: String) {
    nickNameCalls.update { it + (client to nickName) }
  }

  override suspend fun updateTransferLimit(client: TetherClient, limit: TransferAmount?) {
    transferLimitCalls.update { it + (client to limit) }
  }

  override suspend fun updateBandwidthLimit(client: TetherClient, limit: TransferAmount?) {
    bandwidthLimitCalls.update { it + (client to limit) }
  }
}

private fun newViewModel(
    dispatchers: AppDispatchers,
    allowedClients: AllowedClients = FakeAllowedClients(),
    blockedClients: BlockedClients = FakeBlockedClients(),
    blockTracker: BlockedClientTracker = FakeBlockedClientTracker(),
    clientEditor: ClientEditor = FakeClientEditor(),
): ConnectionViewModel =
    ConnectionViewModel(
        state = MutableConnectionViewState(),
        allowedClients = allowedClients,
        blockedClients = blockedClients,
        blockTracker = blockTracker,
        clientEditor = clientEditor,
        dispatchers = dispatchers,
    )

class ConnectionViewModelTest {

  @Test
  fun `vm state matches expectged screen`() = runTest {
    val clientA = clientFor("1.1.1.1")
    val clientB = clientFor("2.2.2.2")
    val allowedFlow = MutableStateFlow<Collection<TetherClient>>(listOf(clientB, clientA))
    val blockedFlow = MutableStateFlow<Collection<TetherClient>>(listOf(clientB))
    val viewModel =
        newViewModel(
            allowedClients = FakeAllowedClients(allowedFlow),
            blockedClients = FakeBlockedClients(blockedFlow),
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    val bindScope = CoroutineScope(Job())
    try {
      viewModel.bind(bindScope)

      val connections = viewModel.connections.first { it.size == 2 }
      assertEquals(listOf(clientA, clientB), connections)

      val blocked = viewModel.blocked.first { it.isNotEmpty() }
      assertEquals(listOf(clientB), blocked.toList())
    } finally {
      bindScope.cancel()
    }
  }

  @Test
  fun `handleToggleBlock unblocks a currently blocked client`() {
    val blockTracker = FakeBlockedClientTracker()
    val viewModel =
        newViewModel(
            blockedClients = FakeBlockedClients(isBlockedResult = true),
            blockTracker = blockTracker,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = clientFor("1.1.1.1")

    viewModel.handleToggleBlock(client)

    assertEquals(listOf(client), blockTracker.unblockCalls)
    assertTrue(blockTracker.blockCalls.isEmpty())
  }

  @Test
  fun `handleToggleBlock blocks a currently unblocked client`() {
    val blockTracker = FakeBlockedClientTracker()
    val viewModel =
        newViewModel(
            blockedClients = FakeBlockedClients(isBlockedResult = false),
            blockTracker = blockTracker,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = clientFor("1.1.1.1")

    viewModel.handleToggleBlock(client)

    assertEquals(listOf(client), blockTracker.blockCalls)
    assertTrue(blockTracker.unblockCalls.isEmpty())
  }

  @Test
  fun `handleOpenManage routes to the matching dialog state`() {
    val viewModel =
        newViewModel(
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )
    val client = clientFor("1.1.1.1")

    assertNull(viewModel.managingNickName.value)
    assertNull(viewModel.managingBandwidthLimit.value)
    assertNull(viewModel.managingTransferLimit.value)

    viewModel.handleOpenManage(client, ConnectionViewManagement.NICK_NAME)
    assertEquals(client, viewModel.managingNickName.value)
    viewModel.handleCloseManage(ConnectionViewManagement.NICK_NAME)
    assertNull(viewModel.managingNickName.value)

    viewModel.handleOpenManage(client, ConnectionViewManagement.TRANSFER_LIMIT)
    assertEquals(client, viewModel.managingTransferLimit.value)
    viewModel.handleCloseManage(ConnectionViewManagement.TRANSFER_LIMIT)
    assertNull(viewModel.managingTransferLimit.value)

    viewModel.handleOpenManage(client, ConnectionViewManagement.BANDWIDTH_LIMIT)
    assertEquals(client, viewModel.managingBandwidthLimit.value)
    viewModel.handleCloseManage(ConnectionViewManagement.BANDWIDTH_LIMIT)
    assertNull(viewModel.managingBandwidthLimit.value)
  }

  @Test
  fun `handleUpdateNickName is a no-op when no client is being managed`() = runTest {
    val clientEditor = FakeClientEditor()
    val viewModel =
        newViewModel(
            clientEditor = clientEditor,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    viewModel.handleUpdateNickName(this, "Bob")

    assertTrue(clientEditor.nickNameCalls.value.isEmpty())
  }

  @Test
  fun `handleUpdateNickName delegates to the client editor for the managed client`() = runTest {
    val client = clientFor("1.1.1.1")
    val clientEditor = FakeClientEditor()
    val viewModel =
        newViewModel(
            clientEditor = clientEditor,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    viewModel.handleOpenManage(client, ConnectionViewManagement.NICK_NAME)

    viewModel.handleUpdateNickName(this, "Bob")

    val calls = clientEditor.nickNameCalls.first { it.isNotEmpty() }
    assertEquals(listOf(client to "Bob"), calls)
  }

  @Test
  fun `handleUpdateTransferLimit is a no-op when no client is being managed`() = runTest {
    val clientEditor = FakeClientEditor()
    val viewModel =
        newViewModel(
            clientEditor = clientEditor,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    viewModel.handleUpdateTransferLimit(this, TransferAmount(amount = 1, unit = TransferUnit.MB))

    assertTrue(clientEditor.transferLimitCalls.value.isEmpty())
  }

  @Test
  fun `handleUpdateTransferLimit delegates to the client editor for the managed client`() =
      runTest {
        val client = clientFor("1.1.1.1")
        val clientEditor = FakeClientEditor()
        val viewModel =
            newViewModel(
                clientEditor = clientEditor,
                // TODO(Peter): Do we need test dispatchers?
                dispatchers = AppDispatchers.create(),
            )
        val limit = TransferAmount(amount = 5, unit = TransferUnit.MB)

        viewModel.handleOpenManage(client, ConnectionViewManagement.TRANSFER_LIMIT)
        viewModel.handleUpdateTransferLimit(this, limit)

        val calls = clientEditor.transferLimitCalls.first { it.isNotEmpty() }
        assertEquals(listOf(client to limit), calls)
      }

  @Test
  fun `handleUpdateBandwidthLimit is a no-op when no client is being managed`() = runTest {
    val clientEditor = FakeClientEditor()
    val viewModel =
        newViewModel(
            clientEditor = clientEditor,
            // TODO(Peter): Do we need test dispatchers?
            dispatchers = AppDispatchers.create(),
        )

    viewModel.handleUpdateBandwidthLimit(this, TransferAmount(amount = 1, unit = TransferUnit.MB))

    assertTrue(clientEditor.bandwidthLimitCalls.value.isEmpty())
  }

  @Test
  fun `handleUpdateBandwidthLimit delegates to the client editor for the managed client`() =
      runTest {
        val client = clientFor("1.1.1.1")
        val clientEditor = FakeClientEditor()
        val viewModel =
            newViewModel(
                clientEditor = clientEditor,
                // TODO(Peter): Do we need test dispatchers?
                dispatchers = AppDispatchers.create(),
            )
        val limit = TransferAmount(amount = 5, unit = TransferUnit.MB)

        viewModel.handleOpenManage(client, ConnectionViewManagement.BANDWIDTH_LIMIT)
        viewModel.handleUpdateBandwidthLimit(this, limit)

        val calls = clientEditor.bandwidthLimitCalls.first { it.isNotEmpty() }
        assertEquals(listOf(client to limit), calls)
      }
}
