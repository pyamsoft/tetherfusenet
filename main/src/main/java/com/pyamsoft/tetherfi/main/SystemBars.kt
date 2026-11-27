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

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun ComponentActivity.SystemBars(
    isDarkMode: Boolean,
) {
  val view = LocalView.current
  val w = window
  val controller =
      remember(
          w,
          view,
      ) {
        WindowInsetsControllerCompat(w, view)
      }
  LaunchedEffect(
      isDarkMode,
      controller,
  ) {
    val statusStyle =
        if (isDarkMode) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)

    val navStyle =
        if (isDarkMode) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(
        statusBarStyle = statusStyle,
        navigationBarStyle = navStyle,
    )
    controller.isAppearanceLightStatusBars = isDarkMode
    controller.isAppearanceLightNavigationBars = !isDarkMode
  }
}
