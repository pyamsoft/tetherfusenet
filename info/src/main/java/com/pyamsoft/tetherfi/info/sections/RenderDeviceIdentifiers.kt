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

package com.pyamsoft.tetherfi.info.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.ImageDefaults
import com.pyamsoft.pydroid.ui.theme.ZeroSize
import com.pyamsoft.tetherfi.info.R
import com.pyamsoft.tetherfi.ui.icons.IconPainters

private enum class DeviceIdentifiersContentTypes {
  THIS,
  OTHER,
}

internal fun LazyListScope.renderDeviceIdentifiers(
    itemModifier: Modifier = Modifier,
) {
  item(
      contentType = DeviceIdentifiersContentTypes.THIS,
  ) {
    ThisInstruction(
        modifier = itemModifier,
        small = true,
    ) {
      Text(
          text = stringResource(R.string.this_device),
          style =
              MaterialTheme.typography.labelMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
  }

  item(
      contentType = DeviceIdentifiersContentTypes.OTHER,
  ) {
    OtherInstruction(
        modifier = itemModifier,
        small = true,
    ) {
      Text(
          text = stringResource(R.string.other_device),
          style =
              MaterialTheme.typography.labelMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
  }
}

@Composable
private fun ThisDevice(
    modifier: Modifier = Modifier,
    small: Boolean,
) {
  val adjustment = MaterialTheme.keylines.typography
  val sizeAdjustment =
      remember(
          small,
          adjustment,
      ) {
        if (small) adjustment else ZeroSize
      }

  Icon(
      modifier =
          modifier
              .padding(start = if (small) 2.dp else ZeroSize)
              .size(ImageDefaults.IconSize - sizeAdjustment)
              .padding(end = if (small) 2.dp else ZeroSize),
      painter = IconPainters.mobile(),
      contentDescription = stringResource(R.string.this_device),
      tint = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun OtherDevice(
    modifier: Modifier = Modifier,
    small: Boolean,
) {
  val adjustment = MaterialTheme.keylines.typography
  val sizeAdjustment =
      remember(
          small,
          adjustment,
      ) {
        if (small) adjustment else ZeroSize
      }

  Icon(
      modifier =
          modifier
              .padding(start = if (small) 2.dp else ZeroSize)
              .size(ImageDefaults.IconSize - sizeAdjustment)
              .padding(end = if (small) 2.dp else ZeroSize),
      painter = IconPainters.devices(),
      contentDescription = stringResource(R.string.other_device),
      tint = MaterialTheme.colorScheme.tertiary,
  )
}

@Composable
internal fun ThisInstruction(
    modifier: Modifier = Modifier,
    small: Boolean = false,
    content: @Composable () -> Unit,
) {
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
      ThisDevice(small = small)
    }

    Box(
        modifier = Modifier.weight(1F).padding(start = MaterialTheme.keylines.content),
    ) {
      content()
    }
  }
}

@Composable
internal fun OtherInstruction(
    modifier: Modifier = Modifier,
    small: Boolean = false,
    content: @Composable () -> Unit,
) {
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
      OtherDevice(small = small)
    }

    Box(
        modifier = Modifier.weight(1F).padding(start = MaterialTheme.keylines.content),
    ) {
      content()
    }
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceIdentifiers() {
  LazyColumn { renderDeviceIdentifiers() }
}
