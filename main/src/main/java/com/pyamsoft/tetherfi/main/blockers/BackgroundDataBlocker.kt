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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
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

private enum class BackgroundDataBlockerContentTypes {
  EXPLAIN,
  PRIVACY_POLICY,
}

@Composable
internal fun BackgroundDataBlocker(
    modifier: Modifier = Modifier,
    appName: String,
    onDismiss: () -> Unit,
    onOpenBackgroundDataSettings: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  CardDialog(modifier = modifier, onDismiss = onDismiss) {
    Text(
        modifier =
            Modifier.padding(horizontal = MaterialTheme.keylines.content)
                .padding(top = MaterialTheme.keylines.content),
        text = stringResource(R.string.block_background_data_title),
        style = MaterialTheme.typography.headlineSmall,
    )

    LazyColumn(modifier = Modifier.weight(weight = 1F, fill = false)) {
      item(contentType = BackgroundDataBlockerContentTypes.EXPLAIN) {
        Text(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content),
            text = stringResource(R.string.block_background_data_description, appName, appName),
            style = MaterialTheme.typography.bodyLarge,
        )
      }

      item(contentType = BackgroundDataBlockerContentTypes.PRIVACY_POLICY) {
        ViewPrivacyPolicy(
            modifier =
                Modifier.padding(horizontal = MaterialTheme.keylines.content)
                    .padding(top = MaterialTheme.keylines.content),
            appName = appName,
        )
      }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.keylines.baseline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Spacer(modifier = Modifier.weight(1F))

      TextButton(
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          onClick = {
            hapticManager?.cancelButtonPress()
            onDismiss()
          },
      ) {
        Text(text = stringResource(android.R.string.cancel))
      }

      TextButton(
          onClick = {
            hapticManager?.confirmButtonPress()
            onOpenBackgroundDataSettings()
          },
      ) {
        Text(text = stringResource(R.string.block_background_data_open_settings))
      }
    }
  }
}

@Preview
@Composable
private fun PreviewBackgroundDataBlocker() {
  BackgroundDataBlocker(appName = "TEST", onDismiss = {}, onOpenBackgroundDataSettings = {})
}
