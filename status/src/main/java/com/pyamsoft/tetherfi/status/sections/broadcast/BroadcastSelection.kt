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

package com.pyamsoft.tetherfi.status.sections.broadcast

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.ImageDefaults
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.ui.checkable.rememberCheckableColor
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.rememberCheckableIconColor
import com.pyamsoft.tetherfi.ui.textAlpha

@Composable
internal inline fun <reified T : Any> BroadcastSelection(
    modifier: Modifier = Modifier,
    isEditable: Boolean,
    currentSelection: T?,
    allSelections: Collection<T>,
    @StringRes title: Int,
    crossinline onSelect: (T) -> Unit,
    crossinline onResolveStrings: (T) -> Strings,
) {
  BroadcastSelection(
      modifier = modifier,
      isEditable = isEditable,
      currentSelection = currentSelection,
      allSelections = remember(allSelections) { allSelections.toTypedArray() },
      title = title,
      onSelect = { onSelect(it) },
      onResolveStrings = { onResolveStrings(it) },
  )
}

@Composable
internal fun <T : Any> BroadcastSelection(
    modifier: Modifier = Modifier,
    isEditable: Boolean,
    currentSelection: T?,
    allSelections: Array<T>,
    @StringRes title: Int,
    onSelect: (T) -> Unit,
    onResolveStrings: (T) -> Strings,
) {
  Column(
      modifier = modifier,
  ) {
    Text(
        modifier = Modifier.padding(horizontal = MaterialTheme.keylines.content),
        text = stringResource(title),
        style =
            MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.W700,
                color =
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = textAlpha(isEditable),
                    ),
            ),
    )

    for (selection in allSelections) {
      Selectable(
          modifier = Modifier.fillMaxWidth(),
          selection = selection,
          current = currentSelection,
          isEditable = isEditable,
          onClick = onSelect,
          onResolveStrings = onResolveStrings,
      )
    }
  }
}

@Composable
private fun <T : Any> Selectable(
    modifier: Modifier = Modifier,
    selection: T,
    current: T?,
    isEditable: Boolean,
    onClick: (T) -> Unit,
    onResolveStrings: (T) -> Strings,
) {
  val hapticManager = LocalHapticManager.current
  val isSelected = remember(selection, current) { selection == current }
  val iconColor = rememberCheckableIconColor(isEditable, isSelected)
  val handleResolveStrings by rememberUpdatedState(onResolveStrings)

  val strings = remember(selection) { handleResolveStrings(selection) }
  val title = stringResource(strings.title)
  val description = stringResource(strings.description)

  val color by
      rememberCheckableColor(
          enabled = isEditable,
          label = title,
          condition = isSelected,
          selectedColor = MaterialTheme.colorScheme.primary,
      )

  Column(
      modifier =
          modifier
              .clickable(enabled = isEditable) {
                hapticManager?.toggleOn()
                onClick(selection)
              }
              .padding(
                  horizontal = MaterialTheme.keylines.content,
                  vertical = MaterialTheme.keylines.typography,
              ),
  ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          modifier = Modifier.weight(1F),
          text = title,
          style =
              MaterialTheme.typography.bodyLarge.copy(
                  fontWeight = FontWeight.W700,
                  color =
                      color.copy(
                          alpha = textAlpha(isEditable),
                      ),
              ),
      )

      Icon(
          modifier = Modifier.size(ImageDefaults.IconSize),
          painter =
              if (isSelected) IconPainters.checkCircle() else IconPainters.radioButtonUnchecked(),
          contentDescription = title,
          tint = iconColor,
      )
    }

    Text(
        modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
        text = description,
        style =
            MaterialTheme.typography.bodySmall.copy(
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = textAlpha(isEditable),
                    ),
            ),
    )
  }
}
