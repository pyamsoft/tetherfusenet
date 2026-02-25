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

package com.pyamsoft.tetherfi.status.sections.tiles.tile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.ui.IconButtonContent
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import kotlinx.coroutines.delay

@Composable
internal fun RefreshTile(
    modifier: Modifier = Modifier,
    onRefreshConnection: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  StatusTile(
      modifier = modifier,
      show = true,
  ) {
    AttemptRefreshButton(
        onClick = {
          hapticManager?.actionButtonPress()
          onRefreshConnection()
        },
    ) { modifier, iconButton ->
      Row(
          modifier = Modifier.fillMaxWidth().then(modifier),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        val color = LocalContentColor.current

        iconButton()

        Text(
            text = stringResource(R.string.tile_refresh_hotspot),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = color,
                ),
        )
      }
    }
  }
}

@Composable
private fun AttemptRefreshButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: IconButtonContent,
) {
  val (fakeSpin, setFakeSpin) = remember { mutableStateOf(false) }

  val handleResetFakeSpin by rememberUpdatedState { setFakeSpin(false) }

  val handleClick by rememberUpdatedState {
    setFakeSpin(true)
    onClick()
  }

  LaunchedEffect(fakeSpin) {
    if (fakeSpin) {
      delay(1000L)
      handleResetFakeSpin()
    }
  }

  content(
      Modifier.clickable { handleClick() },
  ) {
    IconButton(
        modifier = modifier,
        onClick = { handleClick() },
    ) {
      val angle by
          rememberInfiniteTransition(
                  label = "Refresh",
              )
              .animateFloat(
                  label = "Refresh Spin",
                  initialValue = 0F,
                  targetValue = 360F,
                  animationSpec =
                      infiniteRepeatable(
                          animation =
                              tween(
                                  durationMillis = 500,
                                  easing = LinearEasing,
                              ),
                          repeatMode = RepeatMode.Restart,
                      ),
              )

      Icon(
          modifier = Modifier.graphicsLayer { rotationZ = if (fakeSpin) angle else 0F },
          painter = IconPainters.refresh(),
          contentDescription = "Refresh",
          tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
