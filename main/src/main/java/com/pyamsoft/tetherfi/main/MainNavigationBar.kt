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

import androidx.annotation.CheckResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.tetherfi.ui.surfaceAlpha

@Composable
@CheckResult
fun rememberAllDestinations(): List<MainView> {
  return remember { MainView.entries.toMutableStateList() }
}

@Composable
fun MainNavigationBar(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    allDestinations: List<MainView>,
    onDestinationChanged: (MainView) -> Unit,
) {
  val currentPage = pagerState.currentPage
  BottomAppBar(
      modifier = modifier.fillMaxWidth(),
      containerColor = Color.Transparent,
      contentColor = LocalContentColor.current,
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      for (index in allDestinations.indices) {
        val tab = allDestinations[index]
        val isSelected =
            remember(
                index,
                currentPage,
            ) {
              index == currentPage
            }

        MainDestination(
            destination = tab,
            isSelected = isSelected,
            onSelected = { onDestinationChanged(tab) },
        )
      }
    }
  }
}

@Composable
private fun MainDestination(
    modifier: Modifier = Modifier,
    destination: MainView,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
  val destinationName = stringResource(destination.displayNameRes)
  val tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = surfaceAlpha(isSelected))

  Column(
      modifier =
          modifier
              .clip(MaterialTheme.shapes.small)
              .clickable { onSelected() }
              .padding(all = MaterialTheme.keylines.baseline),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        contentDescription = destinationName,
        painter = painterResource(destination.icon),
        tint = tint,
    )
    AnimatedVisibility(
        visible = isSelected,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
      Text(
          text = destinationName,
          style = MaterialTheme.typography.labelSmall,
          color = tint,
      )
    }
  }
}

@Preview
@Composable
private fun PreviewMainNavigationBar() {
  val allDestinations = rememberAllDestinations()
  MainNavigationBar(
      pagerState =
          rememberPagerState(
              initialPage = 0,
              initialPageOffsetFraction = 0F,
              pageCount = { allDestinations.size },
          ),
      allDestinations = allDestinations,
      onDestinationChanged = {},
  )
}
