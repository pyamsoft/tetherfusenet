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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.ui.dialog.CardDialog
import com.pyamsoft.tetherfi.ui.test.TEST_HOSTNAME
import java.time.Clock
import org.jetbrains.annotations.TestOnly

@Composable
internal fun NickNameDialog(
    modifier: Modifier = Modifier,
    client: TetherClient,
    onDismiss: () -> Unit,
    onUpdateNickName: (String) -> Unit,
) {
  // Initialize this to the current name
  // This way we can track changes quickly without needing to update the model
  val nickNameState = remember(client) { TextFieldState(initialText = client.nickName) }

  CardDialog(
      modifier = modifier,
      onDismiss = onDismiss,
  ) {
    Column(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
    ) {
      OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          state = nickNameState,
          lineLimits = TextFieldLineLimits.SingleLine,
      )

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
            onClick = {
              onUpdateNickName(nickNameState.text.toString())
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

@TestOnly
@Composable
private fun PreviewNickNameDialog(nickName: String) {
  NickNameDialog(
      client =
          TetherClient.testCreate(
              hostNameOrIp = TEST_HOSTNAME,
              clock = Clock.systemDefaultZone(),
              nickName = nickName,
              transferLimit = null,
              bandwidthLimit = null,
              totalBytes = ByteTransferReport.EMPTY,
          ),
      onDismiss = {},
      onUpdateNickName = {},
  )
}

@Preview
@Composable
private fun PreviewNickNameDialogEmpty() {
  PreviewNickNameDialog(
      nickName = "",
  )
}

@Preview
@Composable
private fun PreviewNickNameDialogName() {
  PreviewNickNameDialog(
      nickName = "TEST",
  )
}
