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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview

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

@Composable
private fun MainDestination(
    modifier: Modifier = Modifier,
    destination: MainView,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
  val textStyle = LocalTextStyle.current
  val destinationName = stringResource(destination.displayNameRes)

  Text(
      modifier = modifier.clickable { onSelected() },
      text = destinationName,
      style =
          textStyle.copy(
              fontWeight = if (isSelected) FontWeight.W700 else null,
          ),
  )
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
