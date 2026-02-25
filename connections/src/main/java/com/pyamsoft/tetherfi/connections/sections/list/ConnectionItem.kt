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

package com.pyamsoft.tetherfi.connections.sections.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.PopupProperties
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults
import com.pyamsoft.pydroid.ui.haptics.HapticManager
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.connections.R
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.TransferAmount
import com.pyamsoft.tetherfi.server.clients.TransferUnit
import com.pyamsoft.tetherfi.server.clients.key
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.test.TEST_HOSTNAME
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.jetbrains.annotations.TestOnly

private val FIRST_SEEN_DATE_FORMATTER =
    object : ThreadLocal<DateTimeFormatter>() {

      override fun initialValue(): DateTimeFormatter {
        return DateTimeFormatter.ofLocalizedDateTime(
            FormatStyle.MEDIUM,
            FormatStyle.MEDIUM,
        )
      }
    }

@Composable
internal fun ConnectionItem(
    modifier: Modifier = Modifier,
    blocked: List<TetherClient>,
    client: TetherClient,
    onToggleBlock: (TetherClient) -> Unit,
    onManageNickName: (TetherClient) -> Unit,
    onManageTransferLimit: (TetherClient) -> Unit,
    onManageBandwidthLimit: (TetherClient) -> Unit,
) {
  val hapticManager = LocalHapticManager.current
  val key = remember(client) { client.key() }
  val seenTime =
      remember(client) {
        FIRST_SEEN_DATE_FORMATTER.get().requireNotNull().format(client.mostRecentlySeen)
      }
  val isOverLimit = remember(client) { client.isOverTransferLimit() }

  val isNotBlocked =
      remember(
          client,
          blocked,
      ) {
        val isBlocked = blocked.firstOrNull { it.key() == client.key() }
        return@remember isBlocked == null
      }

  Box(
      modifier = modifier.padding(bottom = MaterialTheme.keylines.content),
  ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
      Column(
          modifier = Modifier.padding(MaterialTheme.keylines.content),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(
              modifier = Modifier.weight(1F),
          ) {
            Name(
                client = client,
                key = key,
            )
          }

          OptionsMenu(
              client = client,
              hapticManager = hapticManager,
              onManageNickName = onManageNickName,
              onManageTransferLimit = onManageTransferLimit,
              onManageBandwidthLimit = onManageBandwidthLimit,
          )

          Switch(
              checked = isNotBlocked,
              onCheckedChange = { newBlocked ->
                if (newBlocked) {
                  hapticManager?.toggleOn()
                } else {
                  hapticManager?.toggleOff()
                }
                onToggleBlock(client)
              },
          )
        }

        Text(
            modifier = Modifier.padding(bottom = MaterialTheme.keylines.typography),
            text = stringResource(R.string.connection_last_seen, seenTime),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )

        Transfer(
            client = client,
            isOverLimit = isOverLimit,
        )
      }
    }
  }
}

@Composable
private fun OptionsMenu(
    client: TetherClient,
    hapticManager: HapticManager?,
    onManageNickName: (TetherClient) -> Unit,
    onManageTransferLimit: (TetherClient) -> Unit,
    onManageBandwidthLimit: (TetherClient) -> Unit,
) {
  val (show, setShow) = remember { mutableStateOf(false) }

  val handleDismiss by rememberUpdatedState { setShow(false) }

  IconButton(
      modifier = Modifier.padding(horizontal = MaterialTheme.keylines.baseline),
      onClick = {
        hapticManager?.actionButtonPress()
        setShow(true)
      },
  ) {
    Icon(
        contentDescription = stringResource(R.string.connection_options),
        painter = IconPainters.moreVert(),
    )
  }

  DropdownMenu(
      expanded = show,
      properties = remember { PopupProperties(focusable = true) },
      onDismissRequest = { handleDismiss() },
  ) {
    OptionsMenuItems(
        onManageNickName = {
          hapticManager?.actionButtonPress()
          onManageNickName(client)
          handleDismiss()
        },
        onManageTransferLimit = {
          hapticManager?.actionButtonPress()
          onManageTransferLimit(client)
          handleDismiss()
        },
        onManageBandwidthLimit = {
          hapticManager?.actionButtonPress()
          onManageBandwidthLimit(client)
          handleDismiss()
        },
    )
  }
}

@Composable
private fun OptionsMenuItems(
    onManageNickName: () -> Unit,
    onManageTransferLimit: () -> Unit,
    onManageBandwidthLimit: () -> Unit,
) {
  DropdownMenuItem(
      onClick = onManageNickName,
      text = {
        Text(
            text = stringResource(R.string.option_set_nickname),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )

  DropdownMenuItem(
      onClick = onManageTransferLimit,
      text = {
        Text(
            text = stringResource(R.string.option_set_transfer),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )

  DropdownMenuItem(
      onClick = onManageBandwidthLimit,
      text = {
        Text(
            text = stringResource(R.string.option_set_bandwidth),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
  )
}

@Composable
private fun Name(
    client: TetherClient,
    key: String,
) {
  client.nickName.also { nickName ->
    val name = remember(nickName, key) { nickName.ifBlank { key } }
    Text(
        text = name,
        style = MaterialTheme.typography.titleLarge,
    )

    if (nickName.isNotBlank()) {
      Text(
          text = key,
          style = MaterialTheme.typography.bodySmall,
          color =
              MaterialTheme.colorScheme.onSurfaceVariant.copy(
                  alpha = TypographyDefaults.ALPHA_DISABLED,
              ),
      )
    }
  }
}

@Composable
private fun Transfer(
    itemModifier: Modifier = Modifier,
    client: TetherClient,
    isOverLimit: Boolean,
) {
  Limit(
      modifier = itemModifier,
      limit = client.bandwidthLimit,
      label = stringResource(R.string.bandwidth_label),
      limitLabelResId = R.string.bandwidth_limit,
      // We don't go OVER a bandwidth limit, we just run into it
      isOverLimit = false,
  )

  Limit(
      modifier = itemModifier,
      limit = client.transferLimit,
      label = stringResource(R.string.transfer_label),
      limitLabelResId = R.string.transfer_limit,
      isOverLimit = isOverLimit,
  )

  Text(
      modifier = itemModifier.padding(top = MaterialTheme.keylines.baseline),
      text =
          stringResource(
              R.string.connection_total_from_internet,
              client.transferFromInternet.display,
          ),
      style =
          MaterialTheme.typography.bodySmall.copy(
              color =
                  MaterialTheme.colorScheme.onSurface.copy(
                      alpha = TypographyDefaults.ALPHA_DISABLED,
                  ),
          ),
  )

  Text(
      modifier = itemModifier,
      text =
          stringResource(R.string.connection_total_to_internet, client.transferToInternet.display),
      style =
          MaterialTheme.typography.bodySmall.copy(
              color =
                  MaterialTheme.colorScheme.onSurface.copy(
                      alpha = TypographyDefaults.ALPHA_DISABLED,
                  ),
          ),
  )
}

@Composable
private fun Limit(
    modifier: Modifier = Modifier,
    limit: TransferAmount?,
    label: String,
    @StringRes limitLabelResId: Int,
    isOverLimit: Boolean,
) {
  limit?.also { target ->
    val displayLimit =
        if (isOverLimit) {
          stringResource(R.string.transfer_over_limit, target.display)
        } else {
          target.display
        }

    val color =
        if (isOverLimit) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurface.copy(
              alpha = TypographyDefaults.ALPHA_DISABLED,
          )
        }

    Text(
        modifier = modifier,
        text =
            stringResource(
                limitLabelResId,
                label,
                displayLimit,
            ),
        style =
            MaterialTheme.typography.bodySmall.copy(
                color = color,
            ),
    )
  }
}

@TestOnly
@Composable
private fun PreviewConnectionItem(
    nickName: String,
    transferLimit: TransferAmount?,
    bandwidthLimit: TransferAmount?,
    totalBytes: ByteTransferReport,
) {
  ConnectionItem(
      blocked = remember { mutableStateListOf() },
      client =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = nickName,
              transferLimit = transferLimit,
              bandwidthLimit = bandwidthLimit,
              totalBytes = totalBytes,
          ),
      onToggleBlock = {},
      onManageTransferLimit = {},
      onManageNickName = {},
      onManageBandwidthLimit = {},
  )
}

@Preview
@Composable
private fun PreviewConnectionItemDefault() {
  PreviewConnectionItem(
      nickName = "",
      transferLimit = null,
      bandwidthLimit = null,
      totalBytes = ByteTransferReport.EMPTY,
  )
}

@Preview
@Composable
private fun PreviewConnectionItemWithName() {
  PreviewConnectionItem(
      nickName = "TEST",
      transferLimit = null,
      bandwidthLimit = null,
      totalBytes = ByteTransferReport.EMPTY,
  )
}

@Preview
@Composable
private fun PreviewConnectionItemWithLimit() {
  PreviewConnectionItem(
      nickName = "",
      transferLimit = TransferAmount(10L, TransferUnit.MB),
      totalBytes = ByteTransferReport.EMPTY,
      bandwidthLimit = null,
  )
}

@Preview
@Composable
private fun PreviewConnectionItemUnderLimit() {
  PreviewConnectionItem(
      nickName = "TEST",
      transferLimit = TransferAmount(10L, TransferUnit.MB),
      totalBytes =
          ByteTransferReport(
              internetToProxy = 5L,
              proxyToInternet = 5L,
          ),
      bandwidthLimit = null,
  )
}

@Preview
@Composable
private fun PreviewConnectionItemOverLimit() {
  PreviewConnectionItem(
      nickName = "TEST",
      transferLimit = TransferAmount(5L, TransferUnit.MB),
      totalBytes =
          ByteTransferReport(
              internetToProxy = (10L * 1024L * 1024L),
              proxyToInternet = (10L * 1024L * 1024L),
          ),
      bandwidthLimit = null,
  )
}

@Preview
@Composable
private fun PreviewConnectionItemWithLimitWithBandwidth() {
  PreviewConnectionItem(
      nickName = "",
      transferLimit = TransferAmount(10L, TransferUnit.MB),
      totalBytes = ByteTransferReport.EMPTY,
      bandwidthLimit = TransferAmount(5L, TransferUnit.MB),
  )
}

@Preview
@Composable
private fun PreviewConnectionItemUnderLimitWithBandwidth() {
  PreviewConnectionItem(
      nickName = "TEST",
      transferLimit = TransferAmount(10L, TransferUnit.MB),
      totalBytes =
          ByteTransferReport(
              internetToProxy = 5L,
              proxyToInternet = 5L,
          ),
      bandwidthLimit = TransferAmount(5L, TransferUnit.MB),
  )
}

@Preview
@Composable
private fun PreviewConnectionItemOverLimitWithBandwidth() {
  PreviewConnectionItem(
      nickName = "TEST",
      transferLimit = TransferAmount(5L, TransferUnit.MB),
      bandwidthLimit = TransferAmount(5L, TransferUnit.MB),
      totalBytes =
          ByteTransferReport(
              internetToProxy = (10L * 1024L * 1024L),
              proxyToInternet = (10L * 1024L * 1024L),
          ),
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionItemOptionsMenuItems() {
  Column {
    OptionsMenuItems(
        onManageNickName = {},
        onManageTransferLimit = {},
        onManageBandwidthLimit = {},
    )
  }
}
