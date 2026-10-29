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

package com.pyamsoft.tetherfi.main.blockers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import com.pyamsoft.tetherfi.main.R
import com.pyamsoft.tetherfi.ui.dialog.CardDialog

private enum class WifiBlockerContentTypes {
  NOTE,
  RESOLVE,
  OPEN_SETTINGS,
}

@Composable
internal fun WifiBlocker(
    modifier: Modifier = Modifier,
    appName: String,
    onOpenWifiSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  CardDialog(
      modifier = modifier,
      onDismiss = onDismiss,
  ) {
    Text(
        modifier =
            Modifier.padding(horizontal = MaterialTheme.keylines.content)
                .padding(top = MaterialTheme.keylines.content),
        text = stringResource(R.string.block_wifi_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    LazyColumn(
        modifier =
            Modifier.weight(
                weight = 1F,
                fill = false,
            ),
    ) {
      item(
          contentType = WifiBlockerContentTypes.NOTE,
      ) {
        Text(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content),
            text = stringResource(R.string.block_wifi_description, appName),
            style = MaterialTheme.typography.bodyLarge,
        )
      }

      item(
          contentType = WifiBlockerContentTypes.RESOLVE,
      ) {
        Text(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content),
            text = stringResource(R.string.block_wifi_instruction),
            style = MaterialTheme.typography.bodyLarge,
        )
      }

      item(
          contentType = WifiBlockerContentTypes.OPEN_SETTINGS,
      ) {
        Box(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content)
                    .fillParentMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
          Button(
              onClick = {
                hapticManager?.confirmButtonPress()
                onOpenWifiSettings()
              },
          ) {
            Text(
                text = stringResource(R.string.block_wifi_open_settings),
            )
          }
        }
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
            text = stringResource(android.R.string.ok),
        )
      }
    }
  }
}

@Preview
@Composable
private fun PreviewWifiBlocker() {
  WifiBlocker(
      appName = "TEST",
      onDismiss = {},
      onOpenWifiSettings = {},
  )
}
