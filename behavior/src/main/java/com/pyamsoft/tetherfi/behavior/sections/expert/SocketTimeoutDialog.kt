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

package com.pyamsoft.tetherfi.behavior.sections.expert

import androidx.annotation.CheckResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.behavior.BehaviorViewState
import com.pyamsoft.tetherfi.behavior.R
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.ui.dialog.CardDialog

private enum class SocketTimeoutDialogContentTypes {
  DIALOG_LIMITS,
}

private data class SocketDisplayTimeout(
    val key: String,
    val timeout: ServerSocketTimeout,
    val title: String,
    val description: String,
)

@Composable
@CheckResult
private fun rememberDisplayTimeouts(): List<SocketDisplayTimeout> {
  return ServerSocketTimeout.Defaults.entries
      .map { t ->
        val title: String
        val description: String
        when (t) {
          ServerSocketTimeout.Defaults.INFINITE -> {
            title = stringResource(R.string.expert_timeout_yolo_title)
            description = stringResource(R.string.expert_timeout_yolo_description)
          }
          ServerSocketTimeout.Defaults.SUPERFAST -> {
            title = stringResource(R.string.expert_timeout_toofast_title)
            description = stringResource(R.string.expert_timeout_toofast_description)
          }
          ServerSocketTimeout.Defaults.FAST -> {
            title = stringResource(R.string.expert_timeout_fast_title)
            description = stringResource(R.string.expert_timeout_fast_description)
          }
          ServerSocketTimeout.Defaults.BALANCED -> {
            title = stringResource(R.string.expert_timeout_balance_title)
            description = stringResource(R.string.expert_timeout_balance_description)
          }
          ServerSocketTimeout.Defaults.COMPAT -> {
            title = stringResource(R.string.expert_timeout_conservative_title)
            description = stringResource(R.string.expert_timeout_conservative_description)
          }
          ServerSocketTimeout.Defaults.NICE -> {
            title = stringResource(R.string.expert_timeout_longwait_title)
            description = stringResource(R.string.expert_timeout_longwait_description)
          }
        }

        return@map SocketDisplayTimeout(
            key = t.name,
            timeout = t,
            title = title,
            description = description,
        )
      }
      .sortedWith { o1, o2 ->
        // Infinite on bottom
        if (o1.timeout.timeoutDuration.isInfinite()) {
          return@sortedWith 1
        }

        // Infinite on bottom
        if (o2.timeout.timeoutDuration.isInfinite()) {
          return@sortedWith -1
        }

        return@sortedWith o1.timeout.timeoutDuration.compareTo(o2.timeout.timeoutDuration)
      }
}

@Composable
private fun DialogClose(
    modifier: Modifier = Modifier,
    onHideSocketTimeout: () -> Unit,
) {
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(
        modifier = Modifier.weight(1F),
    )
    TextButton(
        onClick = onHideSocketTimeout,
    ) {
      Text(
          text = stringResource(android.R.string.cancel),
      )
    }
  }
}

@Composable
private fun SocketTimeoutLimit(
    modifier: Modifier = Modifier,
    current: ServerSocketTimeout,
    timeout: ServerSocketTimeout,
    title: String,
    description: String,
    onSelect: (ServerSocketTimeout) -> Unit,
) {
  val isInfinite = remember(timeout) { timeout.timeoutDuration.isInfinite() }
  val isCurrent =
      remember(
          current,
          timeout,
      ) {
        current.timeoutDuration == timeout.timeoutDuration
      }

  Row(
      modifier =
          modifier
              .padding(top = MaterialTheme.keylines.content * if (isInfinite) 2 else 1)
              .clickable(
                  enabled = !isCurrent,
              ) {
                onSelect(timeout)
              }
              .padding(horizontal = MaterialTheme.keylines.content),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(
        modifier = Modifier.padding(end = MaterialTheme.keylines.baseline),
        selected = isCurrent,
        onClick = null,
    )

    Column(
        modifier = Modifier.weight(1F),
    ) {
      Text(
          modifier = Modifier.padding(bottom = MaterialTheme.keylines.typography),
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          fontWeight = FontWeight.W700,
      )

      Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun SocketTimeoutDialog(
    modifier: Modifier = Modifier,
    state: BehaviorViewState,
    onHideSocketTimeout: () -> Unit,
    onUpdateSocketTimeout: (ServerSocketTimeout) -> Unit,
) {
  val currentTimeout by state.socketTimeout.collectAsStateWithLifecycle()
  val timeouts = rememberDisplayTimeouts()

  CardDialog(
      modifier = modifier,
      onDismiss = onHideSocketTimeout,
  ) {
    LazyColumn(
        modifier = Modifier.weight(1F, fill = false),
    ) {
      items(
          items = timeouts,
          key = { it.key },
          contentType = { SocketTimeoutDialogContentTypes.DIALOG_LIMITS },
      ) { timeout ->
        SocketTimeoutLimit(
            modifier = Modifier.fillMaxWidth(),
            current = currentTimeout,
            timeout = timeout.timeout,
            title = timeout.title,
            description = timeout.description,
            onSelect = {
              onUpdateSocketTimeout(it)
              onHideSocketTimeout()
            },
        )
      }
    }

    DialogClose(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.keylines.content),
        onHideSocketTimeout = onHideSocketTimeout,
    )
  }
}
