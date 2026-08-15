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

package com.pyamsoft.tetherfi

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.CheckResult
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.pyamsoft.pydroid.theme.PYDroidTheme
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.pydroid.ui.haptics.rememberHapticManager
import com.pyamsoft.pydroid.ui.theme.Theming
import com.pyamsoft.pydroid.ui.uri.LocalExternalUriHandler
import com.pyamsoft.pydroid.ui.uri.rememberExternalUriHandler
import com.pyamsoft.pydroid.util.AppDispatchers

@Composable
@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
private fun rememberCanUseDynamic(isMaterialYou: Boolean): Boolean {
  return remember(isMaterialYou) { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isMaterialYou }
}

@Composable
@CheckResult
private fun themeColors(
    activity: Activity,
    isDarkMode: Boolean,
    isMaterialYou: Boolean,
): ColorScheme {
  val canUseDynamic = rememberCanUseDynamic(isMaterialYou)

  return remember(
      activity,
      canUseDynamic,
      isDarkMode,
  ) {
    if (isDarkMode) {

      if (canUseDynamic) {
        dynamicDarkColorScheme(activity)
      } else {
        val primary = Color(0xFFC1D02C)
        val onPrimary = Color(0xFF2F3300)
        val primaryContainer = Color(0xFF444B00)
        val onPrimaryContainer = Color(0xFFDDED49)
        val secondary = Color(0xFFC7C9A6)
        val onSecondary = Color(0xFF30321A)
        val secondaryContainer = Color(0xFF46492E)
        val onSecondaryContainer = Color(0xFFE4E5C1)
        val tertiary = Color(0xFFA2D0C1)
        val onTertiary = Color(0xFF07372D)
        val tertiaryContainer = Color(0xFF234E43)
        val onTertiaryContainer = Color(0xFFBEECDC)
        val error = Color(0xFFFFB4AB)
        val errorContainer = Color(0xFF93000A)
        val onError = Color(0xFF690005)
        val onErrorContainer = Color(0xFFFFDAD6)
        val background = Color(0xFF1C1C17)
        val onBackground = Color(0xFFE5E2DA)
        val surface = Color(0xFF1C1C17)
        val onSurface = Color(0xFFE5E2DA)
        val surfaceVariant = Color(0xFF47483B)
        val onSurfaceVariant = Color(0xFFC8C7B7)
        val outline = Color(0xFF929282)
        val inverseOnSurface = Color(0xFF1C1C17)
        val inverseSurface = Color(0xFFE5E2DA)
        val inversePrimary = Color(0xFF5B6300)
        val surfaceTint = Color(0xFFC1D02C)
        val outlineVariant = Color(0xFF47483B)
        val scrim = Color(0xFF000000)

        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            errorContainer = errorContainer,
            onError = onError,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceTint = surfaceTint,
            scrim = scrim,
        )
      }
    } else {
      if (canUseDynamic) {
        dynamicLightColorScheme(activity)
      } else {
        val primary = Color(0xFF5B6300)
        val onPrimary = Color(0xFFFFFFFF)
        val primaryContainer = Color(0xFFDDED49)
        val onPrimaryContainer = Color(0xFF1A1D00)
        val secondary = Color(0xFF5E6044)
        val onSecondary = Color(0xFFFFFFFF)
        val secondaryContainer = Color(0xFFE4E5C1)
        val onSecondaryContainer = Color(0xFF1B1D07)
        val tertiary = Color(0xFF3C665A)
        val onTertiary = Color(0xFFFFFFFF)
        val tertiaryContainer = Color(0xFFBEECDC)
        val onTertiaryContainer = Color(0xFF002019)
        val error = Color(0xFFBA1A1A)
        val errorContainer = Color(0xFFFFDAD6)
        val onError = Color(0xFFFFFFFF)
        val onErrorContainer = Color(0xFF410002)
        val background = Color(0xFFFEFFFE)
        val onBackground = Color(0xFF1C1C17)
        val surface = Color(0xFFFEFFD8)
        val onSurface = Color(0xFF1C1C17)
        val surfaceVariant = Color(0xFFE5E3D2)
        val onSurfaceVariant = Color(0xFF47483B)
        val outline = Color(0xFF787869)
        val inverseOnSurface = Color(0xFFF3F1E8)
        val inverseSurface = Color(0xFF31312B)
        val inversePrimary = Color(0xFFC1D02C)
        val surfaceTint = Color(0xFF5B6300)
        val outlineVariant = Color(0xFFC8C7B7)
        val scrim = Color(0xFF000000)

        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            errorContainer = errorContainer,
            onError = onError,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceTint = surfaceTint,
            scrim = scrim,
        )
      }
    }
  }
}

@Composable
fun ComponentActivity.TFTheme(
    dispatchers: AppDispatchers,
    theme: Theming.Mode,
    isMaterialYou: Boolean,
    content: @Composable () -> Unit,
) {
  val self = this

  val hapticManager =
      rememberHapticManager(
          dispatchers = dispatchers,
      )
  val isDarkMode = theme.getSystemDarkMode()
  val uriHandler = rememberExternalUriHandler()

  PYDroidTheme(
      colorScheme = themeColors(self, isDarkMode, isMaterialYou),
  ) {
    CompositionLocalProvider(
        // We update the LocalContentColor to match our onBackground. This allows the default
        // content color to be more appropriate to the theme background
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,

        // We provide the local haptic manager since PYDroid makes it optional
        LocalHapticManager provides hapticManager,

        // We provide external URI handler
        LocalExternalUriHandler provides uriHandler,

        // And the content, finally
        content = content,
    )
  }
}

@Composable
@CheckResult
fun Theming.Mode.getSystemDarkMode(): Boolean {
  val self = this
  val isDarkMode =
      remember(self) {
        when (self) {
          Theming.Mode.LIGHT -> false
          Theming.Mode.DARK -> true
          Theming.Mode.SYSTEM -> null
        }
      }

  return isDarkMode ?: isSystemInDarkTheme()
}
