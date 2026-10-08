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

package com.pyamsoft.tetherfi.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MainToolbar(
    modifier: Modifier = Modifier,
    appName: String,
) {
  val contentColor = LocalContentColor.current
  TopAppBar(
      modifier = modifier.fillMaxWidth().statusBarsPadding(),
      colors =
          TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Transparent,
              titleContentColor = contentColor,
              navigationIconContentColor = contentColor,
              actionIconContentColor = contentColor,
          ),
      title = {
        Text(
            text = appName,
        )
      },
  )
}

@Preview
@Composable
private fun PreviewMainToolbar() {
  MainToolbar(
      appName = "TEST",
  )
}
