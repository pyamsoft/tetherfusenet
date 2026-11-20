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

package com.pyamsoft.tetherfi.status.sections.network

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

@Composable
internal fun StatusEditor(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String,
    state: TextFieldState,
    textObfuscationMode: TextObfuscationMode = TextObfuscationMode.Visible,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
  val textStyle =
      LocalTextStyle.current.copy(
          fontFamily = FontFamily.Monospace,
      )

  OutlinedSecureTextField(
      modifier = modifier,
      enabled = enabled,
      keyboardOptions = keyboardOptions,
      state = state,
      textStyle = textStyle,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      textObfuscationMode = textObfuscationMode,
      label = {
        Text(
            text = title,
        )
      },
  )
}
