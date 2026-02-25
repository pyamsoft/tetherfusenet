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

package com.pyamsoft.tetherfi.main

import androidx.annotation.CheckResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.ui.icons.IconPainters

@Composable
@CheckResult
fun rememberAllTabs(): List<MainView> {
  return remember { MainView.entries.toMutableStateList() }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainTopBar(
    modifier: Modifier = Modifier,
    appName: String,
    pagerState: PagerState,
    allTabs: List<MainView>,
    onSettingsOpen: () -> Unit,
    onTabChanged: (MainView) -> Unit,
) {
  val hapticManager = LocalHapticManager.current

  Surface(
      modifier = modifier,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      color = MaterialTheme.colorScheme.primary,
      shape =
          MaterialTheme.shapes.large.copy(
              topStart = ZeroCornerSize,
              topEnd = ZeroCornerSize,
          ),
  ) {
    Column {
      val contentColor = LocalContentColor.current
      TopAppBar(
          modifier = Modifier.fillMaxWidth().statusBarsPadding(),
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
          actions = {
            IconButton(
                onClick = {
                  hapticManager?.actionButtonPress()
                  onSettingsOpen()
                },
            ) {
              Icon(
                  painter = IconPainters.settings(),
                  contentDescription = "Open Settings",
              )
            }
          },
      )

      val currentPage = pagerState.currentPage
      SecondaryScrollableTabRow(
          modifier = Modifier.fillMaxWidth(),
          selectedTabIndex = currentPage,
          containerColor = Color.Transparent,
          contentColor = LocalContentColor.current,
          indicator = {
            TabRowDefaults.SecondaryIndicator(
                modifier =
                    Modifier.tabIndicatorOffset(
                        selectedTabIndex = currentPage,
                    ),
                height = MaterialTheme.keylines.typography,
                color = MaterialTheme.colorScheme.onPrimary,
            )
          },
      ) {
        for (index in allTabs.indices) {
          val tab = allTabs[index]
          val isSelected =
              remember(
                  index,
                  currentPage,
              ) {
                index == currentPage
              }

          MainTab(
              tab = tab,
              isSelected = isSelected,
              onSelected = { onTabChanged(tab) },
          )
        }
      }
    }
  }
}

@Composable
private fun MainTab(
    modifier: Modifier = Modifier,
    tab: MainView,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
  val textStyle = LocalTextStyle.current
  val tabName = stringResource(tab.displayNameRes)

  Tab(
      modifier = modifier,
      selected = isSelected,
      onClick = onSelected,
      text = {
        Text(
            text = tabName,
            style =
                textStyle.copy(
                    fontWeight = if (isSelected) FontWeight.W700 else null,
                ),
        )
      },
  )
}

@Preview
@Composable
private fun PreviewMainTopBar() {
  val allTabs = rememberAllTabs()
  MainTopBar(
      appName = "TEST",
      pagerState =
          rememberPagerState(
              initialPage = 0,
              initialPageOffsetFraction = 0F,
              pageCount = { allTabs.size },
          ),
      allTabs = allTabs,
      onSettingsOpen = {},
      onTabChanged = {},
  )
}
