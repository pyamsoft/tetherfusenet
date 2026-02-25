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

package com.pyamsoft.tetherfi.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.app.rememberDialogProperties
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.broadcast.rndis.RNDISInitializeException
import com.pyamsoft.tetherfi.ui.IconButtonContent
import com.pyamsoft.tetherfi.ui.R
import com.pyamsoft.tetherfi.ui.icons.IconPainters

private enum class ServerErrorDialogContentTypes {
  TITLE,
  EXTRA,
  TRACE,
}

@Composable
fun ServerErrorTile(onShowError: () -> Unit, content: IconButtonContent) {
  val hapticManager = LocalHapticManager.current

  val handleClick by rememberUpdatedState {
    hapticManager?.actionButtonPress()
    onShowError()
  }

  content(Modifier.clickable { handleClick() }) {
    Icon(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
        painter = IconPainters.warning(),
        contentDescription = stringResource(R.string.something_went_wrong),
        tint = MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
fun ServerErrorDialog(
    modifier: Modifier = Modifier,
    title: String,
    error: Throwable,
    onDismiss: () -> Unit,
) {
  Dialog(properties = rememberDialogProperties(), onDismissRequest = onDismiss) {
    Column(
        modifier =
            modifier
                // Top already has padding for some reason?
                .padding(horizontal = MaterialTheme.keylines.content)
                .padding(bottom = MaterialTheme.keylines.content)
    ) {
      DialogToolbar(
          modifier = Modifier.fillMaxWidth(),
          onClose = onDismiss,
          title = {
            // Intentionally blank
          },
      )

      // TODO(Peter): Checkbox to show stacktrace
      Card(
          shape =
              MaterialTheme.shapes.large.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize),
          elevation = CardDefaults.elevatedCardElevation(),
          colors = CardDefaults.elevatedCardColors(),
      ) {
        LazyColumn {
          item(contentType = ServerErrorDialogContentTypes.TITLE) {
            Text(
                modifier = Modifier.padding(MaterialTheme.keylines.content),
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
          }

          if (error is RNDISInitializeException) {
            item(contentType = ServerErrorDialogContentTypes.EXTRA) {
              val trace = remember(error) { error.candidateMessage }
              Text(
                  modifier = Modifier.padding(MaterialTheme.keylines.content),
                  text = trace,
                  style =
                      MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
              )
            }
          }

          item(contentType = ServerErrorDialogContentTypes.TRACE) {
            val trace = remember(error) { error.stackTraceToString() }
            Text(
                modifier = Modifier.padding(MaterialTheme.keylines.content),
                text = trace,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
          }
        }
      }
    }
  }
}

@Preview
@Composable
private fun PreviewServerErrorDialog() {
  ServerErrorDialog(title = "TEST", error = IllegalStateException("TEST ERROR"), onDismiss = {})
}
