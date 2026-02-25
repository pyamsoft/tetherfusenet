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

package com.pyamsoft.tetherfi.status.sections.network

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pyamsoft.pydroid.ui.icons.IconPainters as IconPainters2
import com.pyamsoft.tetherfi.ui.icons.IconPainters

@Composable
internal fun ValidIcon(
    modifier: Modifier = Modifier,
    isValid: Boolean,
    description: String,
) {
  Icon(
      modifier = modifier,
      painter = if (isValid) IconPainters.check() else IconPainters2.close(),
      tint = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      contentDescription = description,
  )
}
