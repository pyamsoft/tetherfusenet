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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.connections

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.clients.TransferAmount
import com.pyamsoft.tetherfi.server.clients.TransferUnit
import com.pyamsoft.tetherfi.ui.dialog.CardDialog
import org.jetbrains.annotations.TestOnly

@Composable
internal fun TransferDialog(
    modifier: Modifier = Modifier,
    client: TetherClient,
    onDismiss: () -> Unit,
    onUpdateLimit: (TransferAmount?) -> Unit,
) {
  TransferDialog(
      modifier = modifier,
      limit = client.transferLimit,
      label = stringResource(R.string.transfer_label),
      showMenu = false,
      isBandwidth = false,
      onDismiss = onDismiss,
      onUpdateLimit = onUpdateLimit,
  )
}

@Composable
internal fun BandwidthLimitDialog(
    modifier: Modifier = Modifier,
    client: TetherClient,
    onDismiss: () -> Unit,
    onUpdateLimit: (TransferAmount?) -> Unit,
) {
  TransferDialog(
      modifier = modifier,
      limit = client.bandwidthLimit,
      label = stringResource(R.string.bandwidth_label),
      showMenu = false,
      isBandwidth = true,
      onDismiss = onDismiss,
      onUpdateLimit = onUpdateLimit,
  )
}

@Composable
private fun TransferDialog(
    modifier: Modifier = Modifier,
    limit: TransferAmount?,
    showMenu: Boolean,
    label: String,
    isBandwidth: Boolean,
    onDismiss: () -> Unit,
    onUpdateLimit: (TransferAmount?) -> Unit,
) {
  val (showDropdown, setShowDropdown) = remember(showMenu) { mutableStateOf(showMenu) }

  // Initialize this to the current name
  // This way we can track changes quickly without needing to update the model
  val (enabled, setEnabled) = remember(limit) { mutableStateOf(limit != null) }
  val amountState =
      remember(limit) { TextFieldState(initialText = limit?.amount?.toString().orEmpty()) }

  // The default unit depends on the type of Dialog
  val defaultUnit =
      remember(isBandwidth) { if (isBandwidth) TransferUnit.BYTE else TransferUnit.KB }

  val (limitUnit, setLimitUnit) =
      remember(
          limit,
          defaultUnit,
      ) {
        mutableStateOf(limit?.unit ?: defaultUnit)
      }

  val amountText = amountState.text
  val amountValue = remember(amountText) { amountText.toString().toLongOrNull() }

  val hapticManager = LocalHapticManager.current
  val canSave =
      remember(amountValue, enabled) {
        if (!enabled) {
          return@remember true
        } else {
          return@remember amountValue != null
        }
      }
  val isError = remember(canSave) { !canSave }

  val handleDismissDropdown by rememberUpdatedState { setShowDropdown(false) }

  CardDialog(
      modifier = modifier,
      onDismiss = onDismiss,
  ) {
    Column(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            modifier = Modifier.weight(1F),
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color =
                if (enabled) {
                  MaterialTheme.colorScheme.onSurface
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        Switch(
            checked = enabled,
            onCheckedChange = { newEnabled ->
              if (newEnabled) {
                hapticManager?.toggleOn()
              } else {
                hapticManager?.toggleOff()
              }
              setEnabled(newEnabled)
            },
        )
      }

      Row(
          modifier = Modifier.padding(top = MaterialTheme.keylines.content),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        TextField(
            modifier = Modifier.weight(1F),
            state = amountState,
            enabled = enabled,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            isError = isError,
        )

        Column(
            modifier =
                Modifier.padding(
                    start = MaterialTheme.keylines.baseline,
                ),
        ) {
          Text(
              modifier =
                  Modifier.run {
                        if (!enabled) {
                          this
                        } else {
                          border(
                              width = 1.dp,
                              color = MaterialTheme.colorScheme.primary,
                              shape = MaterialTheme.shapes.small,
                          )
                        }
                      }
                      .padding(
                          horizontal = MaterialTheme.keylines.content,
                          vertical = MaterialTheme.keylines.baseline,
                      )
                      .clickable(enabled = enabled) { setShowDropdown(true) },
              text = limitUnit.displayName,
              style = MaterialTheme.typography.bodySmall,
              color =
                  MaterialTheme.colorScheme.onSurfaceVariant.run {
                    if (enabled) {
                      this
                    } else {
                      copy(alpha = TypographyDefaults.ALPHA_DISABLED)
                    }
                  },
          )

          TransferMenu(
              current = limitUnit,
              enabled = enabled,
              showDropdown = showDropdown,
              onDismiss = { handleDismissDropdown() },
              isBandwidth = isBandwidth,
              onSelect = { u ->
                hapticManager?.actionButtonPress()
                setLimitUnit(u)
                handleDismissDropdown()
              },
          )
        }
      }

      Row(
          modifier = Modifier.padding(top = MaterialTheme.keylines.content),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(
            modifier = Modifier.weight(1F),
        )

        TextButton(
            onClick = onDismiss,
        ) {
          Text(
              text = stringResource(android.R.string.cancel),
          )
        }
        Button(
            modifier = Modifier.padding(start = MaterialTheme.keylines.baseline),
            enabled = canSave,
            onClick = {
              val newLimit =
                  if (enabled) {
                    amountValue?.let { v ->
                      TransferAmount(
                          amount = v,
                          unit = limitUnit,
                      )
                    }
                  } else {
                    null
                  }
              onUpdateLimit(newLimit)
              onDismiss()
            },
        ) {
          Text(
              text = stringResource(android.R.string.ok),
          )
        }
      }
    }
  }
}

@Composable
private fun TransferMenu(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    current: TransferUnit,
    isBandwidth: Boolean,
    showDropdown: Boolean,
    onSelect: (TransferUnit) -> Unit,
    onDismiss: () -> Unit,
) {
  DropdownMenu(
      modifier = modifier,
      expanded = showDropdown,
      properties = remember { PopupProperties(focusable = true) },
      onDismissRequest = onDismiss,
  ) {
    TransferMenuItems(
        enabled = enabled,
        current = current,
        isBandwidth = isBandwidth,
        onSelect = onSelect,
    )
  }
}

@Composable
private fun TransferMenuItems(
    enabled: Boolean,
    isBandwidth: Boolean,
    current: TransferUnit,
    onSelect: (TransferUnit) -> Unit,
) {
  val availableUnits =
      remember(isBandwidth) {
        if (isBandwidth) {
          // Nothing in existance has a TB or PB rate, don't allow it
          return@remember TransferUnit.entries
              .filterNot { it == TransferUnit.TB }
              .filterNot { it == TransferUnit.PB }
        } else {
          // Don't let people pick bytes, who wants to limit bytes?
          return@remember TransferUnit.entries.filterNot { it == TransferUnit.BYTE }
        }
      }

  availableUnits.forEach { u ->
    DropdownMenuItem(
        onClick = { onSelect(u) },
        text = {
          val isSelected = remember(current, u) { u == current }

          Row(
              verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
                enabled = enabled,
                selected = isSelected,
                onClick = { onSelect(u) },
            )

            Text(
                modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                text = u.displayName,
                style = MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.run {
                      if (enabled) {
                        this
                      } else {
                        copy(alpha = TypographyDefaults.ALPHA_DISABLED)
                      }
                    },
            )
          }
        },
    )
  }
}

@TestOnly
@Composable
private fun PreviewTransferDialog(
    limit: TransferAmount?,
    showMenu: Boolean,
) {
  TransferDialog(
      limit = limit,
      label = "TEST",
      isBandwidth = true,
      showMenu = showMenu,
      onDismiss = {},
      onUpdateLimit = {},
  )
}

@Preview
@Composable
private fun PreviewTransferDialogEmpty() {
  PreviewTransferDialog(
      limit = null,
      showMenu = false,
  )
}

@Preview
@Composable
private fun PreviewTransferDialogEnabled() {
  PreviewTransferDialog(
      limit =
          TransferAmount(
              10L,
              TransferUnit.MB,
          ),
      showMenu = false,
  )
}

@Preview
@Composable
private fun PreviewTransferDialogOpen() {
  PreviewTransferDialog(
      limit =
          TransferAmount(
              10L,
              TransferUnit.MB,
          ),
      showMenu = true,
  )
}

@TestOnly
@Composable
private fun PreviewTransferMenuItems(
    enabled: Boolean,
    current: TransferUnit,
    allowLargeSizes: Boolean,
) {
  Column {
    TransferMenuItems(
        enabled = enabled,
        current = current,
        onSelect = {},
        isBandwidth = allowLargeSizes,
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsLargeEnabled() {
  PreviewTransferMenuItems(
      enabled = true,
      current = TransferUnit.KB,
      allowLargeSizes = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsLargeDisabled() {
  PreviewTransferMenuItems(
      enabled = false,
      current = TransferUnit.KB,
      allowLargeSizes = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsLargePicked() {
  PreviewTransferMenuItems(
      enabled = true,
      current = TransferUnit.MB,
      allowLargeSizes = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsSmallEnabled() {
  PreviewTransferMenuItems(
      enabled = true,
      current = TransferUnit.KB,
      allowLargeSizes = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsSmallDisabled() {
  PreviewTransferMenuItems(
      enabled = false,
      current = TransferUnit.KB,
      allowLargeSizes = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewTransferMenuItemsSmallPicked() {
  PreviewTransferMenuItems(
      enabled = true,
      current = TransferUnit.GB,
      allowLargeSizes = false,
  )
}
