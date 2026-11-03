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

package com.pyamsoft.tetherfi.ui.trouble

import android.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.ui.dialog.CardDialog

private enum class TroubleshootDialogContentTypes {
  STEPS,
}

@Composable
fun TroubleshootDialog(
    modifier: Modifier = Modifier,
    appName: String,
    broadcastType: BroadcastType?,
    isBroadcastError: Boolean,
    isProxyError: Boolean,
    throwable: Throwable?,
    onDismiss: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  CardDialog(
      modifier = modifier,
      onDismiss = onDismiss,
  ) {
    Column {
      LazyColumn(
          modifier =
              Modifier.weight(
                  weight = 1F,
                  fill = false,
              ),
      ) {
        item(
            contentType = TroubleshootDialogContentTypes.STEPS,
        ) {
          TroubleshootUnableToStart(
              modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.keylines.content),
              appName = appName,
              broadcastType = broadcastType,
              isBroadcastError = isBroadcastError,
              isProxyError = isProxyError,
              throwable = throwable,
          )
        }
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(MaterialTheme.keylines.baseline),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(
            modifier = Modifier.weight(1F),
        )

        TextButton(
            onClick = {
              hapticManager?.cancelButtonPress()
              onDismiss()
            },
        ) {
          Text(
              text = stringResource(R.string.cancel),
          )
        }
      }
    }
  }
}

@Composable
private fun PreviewTroubleshootDialog(
    broadcastType: BroadcastType,
    isBroadcastError: Boolean,
    isProxyError: Boolean,
) {
  TroubleshootDialog(
      appName = "TEST",
      broadcastType = broadcastType,
      isBroadcastError = isBroadcastError,
      isProxyError = isProxyError,
      throwable = RuntimeException("PREVIEW"),
      onDismiss = {},
  )
}

@Preview
@Composable
private fun PreviewTroubleshootDialogNone() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.WIFI_DIRECT,
      isBroadcastError = false,
      isProxyError = false,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootDialogBroadcast() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.WIFI_DIRECT,
      isBroadcastError = true,
      isProxyError = false,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootDialogProxy() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.WIFI_DIRECT,
      isBroadcastError = false,
      isProxyError = true,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootDialogBoth() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.WIFI_DIRECT,
      isBroadcastError = true,
      isProxyError = true,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootRNDISDialogNone() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.RNDIS,
      isBroadcastError = false,
      isProxyError = false,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootRNDISDialogBroadcast() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.RNDIS,
      isBroadcastError = true,
      isProxyError = false,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootRNDISDialogProxy() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.RNDIS,
      isBroadcastError = false,
      isProxyError = true,
  )
}

@Preview
@Composable
private fun PreviewTroubleshootRNDISDialogBoth() {
  PreviewTroubleshootDialog(
      broadcastType = BroadcastType.RNDIS,
      isBroadcastError = true,
      isProxyError = true,
  )
}
