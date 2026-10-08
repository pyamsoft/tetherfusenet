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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.util.collectAsStateListWithLifecycle
import com.pyamsoft.tetherfi.connections.sections.list.renderConnectionList
import com.pyamsoft.tetherfi.connections.sections.renderExcuse
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.TransferAmount
import com.pyamsoft.tetherfi.server.clients.TransferUnit
import com.pyamsoft.tetherfi.ui.LANDSCAPE_MAX_WIDTH
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.renderLinks
import com.pyamsoft.tetherfi.ui.renderPYDroidExtras
import com.pyamsoft.tetherfi.ui.test.TEST_HOSTNAME
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.annotations.TestOnly

private enum class ConnectionScreenContentTypes {
  BOTTOM_SPACER,
}

@Composable
fun ConnectionScreen(
    modifier: Modifier = Modifier,
    appName: String,
    state: ConnectionViewState,
    lazyListState: LazyListState,
    serverViewState: ServerViewState,
    onToggleBlock: (TetherClient) -> Unit,
    onOpenManageNickName: (TetherClient) -> Unit,
    onCloseManageNickName: () -> Unit,
    onUpdateNickName: (String) -> Unit,
    onOpenManageTransferLimit: (TetherClient) -> Unit,
    onCloseManageTransferLimit: () -> Unit,
    onUpdateTransferLimit: (TransferAmount?) -> Unit,
    onOpenManageBandwidthLimit: (TetherClient) -> Unit,
    onCloseManageBandwidthLimit: () -> Unit,
    onUpdateBandwidthLimit: (TransferAmount?) -> Unit,
) {
  val group by serverViewState.group.collectAsStateWithLifecycle()
  val clients = state.connections.collectAsStateListWithLifecycle()
  val blocked = state.blocked.collectAsStateListWithLifecycle()

  val manageNickName by state.managingNickName.collectAsStateWithLifecycle()
  val manageTransfer by state.managingTransferLimit.collectAsStateWithLifecycle()
  val manageBandwidth by state.managingBandwidthLimit.collectAsStateWithLifecycle()

  LazyColumn(
      modifier = modifier,
      state = lazyListState,
      contentPadding = PaddingValues(horizontal = MaterialTheme.keylines.content),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    renderPYDroidExtras(
        modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
    )

    renderConnectionList(
        modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
        group = group,
        clients = clients,
        blocked = blocked,
        onToggleBlock = onToggleBlock,
        onManageNickName = onOpenManageNickName,
        onManageTransferLimit = onOpenManageTransferLimit,
        onManageBandwidthLimit = onOpenManageBandwidthLimit,
    )

    renderExcuse(
        modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
    )

    renderLinks(
        modifier = Modifier.widthIn(max = LANDSCAPE_MAX_WIDTH),
        appName = appName,
    )

    item(
        contentType = ConnectionScreenContentTypes.BOTTOM_SPACER,
    ) {
      Spacer(
          modifier = Modifier.padding(top = MaterialTheme.keylines.content),
      )
    }
  }

  manageNickName?.also { manage ->
    NickNameDialog(
        client = manage,
        onUpdateNickName = onUpdateNickName,
        onDismiss = { onCloseManageNickName() },
    )
  }

  manageTransfer?.also { manage ->
    TransferDialog(
        client = manage,
        onUpdateLimit = onUpdateTransferLimit,
        onDismiss = { onCloseManageTransferLimit() },
    )
  }

  manageBandwidth?.also { manage ->
    BandwidthLimitDialog(
        client = manage,
        onUpdateLimit = onUpdateBandwidthLimit,
        onDismiss = { onCloseManageBandwidthLimit() },
    )
  }
}

@TestOnly
@Composable
private fun PreviewConnectionScreen(
    serverViewState: TestServerState,
    nickName: TetherClient?,
    transfer: TetherClient?,
    bandwidth: TetherClient?,
    clientCount: Int,
) {
  ConnectionScreen(
      appName = "TEST",
      lazyListState = rememberLazyListState(),
      state =
          object : ConnectionViewState {
            override val connections =
                MutableStateFlow(
                    mutableListOf<TetherClient>().apply {
                      for (i in 0 until clientCount) {
                        val client =
                            TetherClient.testCreate(
                                hostNameOrIp = "127.0.0.${2 + i}",
                                clock = Clock.systemDefaultZone(),
                                nickName = "TEST ${i + 1}",
                                transferLimit = null,
                                bandwidthLimit = null,
                                totalBytes = ByteTransferReport.EMPTY,
                            )
                        add(client)
                      }
                    },
                )
            override val blocked =
                MutableStateFlow(
                    mutableListOf<TetherClient>().apply {
                      for (i in 0 until clientCount) {
                        if (i % 2 == 0) {
                          continue
                        }

                        val client =
                            TetherClient.testCreate(
                                hostNameOrIp = "127.0.0.${2 + i}",
                                clock = Clock.systemDefaultZone(),
                                nickName = "TEST ${i + 1}",
                                transferLimit = null,
                                bandwidthLimit = null,
                                totalBytes = ByteTransferReport.EMPTY,
                            )
                        add(client)
                      }
                    },
                )
            override val managingNickName = MutableStateFlow(nickName)
            override val managingTransferLimit = MutableStateFlow(transfer)
            override val managingBandwidthLimit = MutableStateFlow(bandwidth)
          },
      serverViewState =
          makeTestServerState(
              serverViewState,
              isHttpEnabled = true,
              isSocksEnabled = true,
          ),
      onCloseManageNickName = {},
      onUpdateNickName = {},
      onUpdateTransferLimit = {},
      onCloseManageTransferLimit = {},
      onToggleBlock = {},
      onOpenManageNickName = {},
      onOpenManageTransferLimit = {},
      onUpdateBandwidthLimit = {},
      onOpenManageBandwidthLimit = {},
      onCloseManageBandwidthLimit = {},
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenEmpty() {
  PreviewConnectionScreen(
      nickName = null,
      transfer = null,
      bandwidth = null,
      clientCount = 0,
      serverViewState = TestServerState.EMPTY,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenEmptyManageNickName() {
  PreviewConnectionScreen(
      nickName =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "TEST",
              transferLimit = null,
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      transfer = null,
      bandwidth = null,
      clientCount = 0,
      serverViewState = TestServerState.EMPTY,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenEmptyManageTransfer() {
  PreviewConnectionScreen(
      nickName = null,
      transfer =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "",
              transferLimit = null,
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      clientCount = 0,
      bandwidth = null,
      serverViewState = TestServerState.EMPTY,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveNoClients() {
  PreviewConnectionScreen(
      nickName = null,
      transfer = null,
      clientCount = 0,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveNoClientsManageNickName() {
  PreviewConnectionScreen(
      nickName =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "TEST",
              transferLimit = null,
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      transfer = null,
      clientCount = 0,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveNoClientsManageTransfer() {
  PreviewConnectionScreen(
      nickName = null,
      transfer =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "",
              transferLimit = TransferAmount(10L, TransferUnit.MB),
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      clientCount = 0,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveWithClients() {
  PreviewConnectionScreen(
      nickName = null,
      transfer = null,
      clientCount = 5,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveWithClientsManageNickName() {
  PreviewConnectionScreen(
      nickName =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "TEST",
              transferLimit = null,
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      transfer = null,
      clientCount = 5,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionScreenActiveWithClientsManageTransfer() {
  PreviewConnectionScreen(
      nickName = null,
      transfer =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = "",
              transferLimit = TransferAmount(10L, TransferUnit.MB),
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      clientCount = 5,
      bandwidth = null,
      serverViewState = TestServerState.CONNECTED,
  )
}
