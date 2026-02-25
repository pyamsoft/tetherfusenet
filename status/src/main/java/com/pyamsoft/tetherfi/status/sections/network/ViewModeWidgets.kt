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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.status.sections.StatusItem
import com.pyamsoft.tetherfi.ui.R as R2
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.dialog.SlowSpeedsUpsell
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.rememberPortNumber
import com.pyamsoft.tetherfi.ui.rememberServerHostname
import com.pyamsoft.tetherfi.ui.rememberServerPassword
import com.pyamsoft.tetherfi.ui.rememberServerSSID

@Composable
internal fun ViewProxy(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
) {
  val connection by serverViewState.connection.collectAsStateWithLifecycle()
  val ipAddress = rememberServerHostname(connection)

  val isHttpEnabled by serverViewState.isHttpEnabled.collectAsStateWithLifecycle()
  val httpPortNumber by serverViewState.httpPort.collectAsStateWithLifecycle()
  val httpPort = rememberPortNumber(httpPortNumber)

  val isSocksEnabled by serverViewState.isSocksEnabled.collectAsStateWithLifecycle()
  val socksPortNumber by serverViewState.socksPort.collectAsStateWithLifecycle()
  val socksPort = rememberPortNumber(socksPortNumber)

  Column(
      modifier = modifier,
  ) {
    StatusItem(
        modifier = Modifier.padding(vertical = MaterialTheme.keylines.baseline),
        title = stringResource(R.string.viewmode_hotspot_hostname),
        value = ipAddress,
        valueStyle =
            MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.W400,
                fontFamily = FontFamily.Monospace,
            ),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isHttpEnabled) {
        StatusItem(
            modifier = Modifier.padding(end = MaterialTheme.keylines.content),
            title = stringResource(R.string.hotspot_proxy_http_port),
            value = httpPort,
            valueStyle =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.W400,
                    fontFamily = FontFamily.Monospace,
                ),
        )
      }

      if (isSocksEnabled) {
        StatusItem(
            title = stringResource(R.string.hotspot_proxy_socks_port),
            value = socksPort,
            valueStyle =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.W400,
                    fontFamily = FontFamily.Monospace,
                ),
        )
      }
    }
  }
}

@Composable
internal fun ViewPassword(
    modifier: Modifier = Modifier,
    state: StatusViewState,
    serverViewState: ServerViewState,
    onTogglePasswordVisibility: () -> Unit,
) {
  val hapticManager = LocalHapticManager.current
  val group by serverViewState.group.collectAsStateWithLifecycle()
  val isPasswordVisible by state.isPasswordVisible.collectAsStateWithLifecycle()
  val password = rememberServerPassword(group, isPasswordVisible)

  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusItem(
        modifier = Modifier.padding(end = MaterialTheme.keylines.content),
        title = stringResource(R.string.viewmode_hotspot_password),
        value = password,
        valueStyle =
            MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.W400,
                fontFamily = FontFamily.Monospace,
            ),
    )

    if (group is BroadcastNetworkStatus.GroupInfo.Connected) {
      IconToggleButton(
          checked = isPasswordVisible,
          onCheckedChange = { newVisible ->
            if (newVisible) {
              hapticManager?.toggleOn()
            } else {
              hapticManager?.toggleOff()
            }
            onTogglePasswordVisibility()
          },
      ) {
        Icon(
            painter =
                if (isPasswordVisible) IconPainters.visibilityOff() else IconPainters.visibility(),
            contentDescription =
                stringResource(
                    if (isPasswordVisible) R2.string.pass_visible else R2.string.pass_hidden
                ),
            tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
internal fun ViewSsid(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
) {
  val group by serverViewState.group.collectAsStateWithLifecycle()
  val ssid = rememberServerSSID(group)

  StatusItem(
      modifier = modifier.padding(end = MaterialTheme.keylines.content),
      title = stringResource(R.string.viewmode_hotspot_name),
      value = ssid,
      valueStyle =
          MaterialTheme.typography.titleLarge.copy(
              fontWeight = FontWeight.W400,
              fontFamily = FontFamily.Monospace,
          ),
  )
}

@Composable
internal fun ViewInstructions(
    modifier: Modifier = Modifier,
    onJumpToHowTo: () -> Unit,
    onViewSlowSpeedHelp: () -> Unit,
) {
  val handlePlaceholderLinkClicked by rememberUpdatedState { link: LinkAnnotation ->
    if (link is LinkAnnotation.Clickable) {
      onJumpToHowTo()
    }
  }

  val linkColor = MaterialTheme.colorScheme.primary

  val setupText = stringResource(R.string.viewmode_setup_instructions)
  val rawBlurb = stringResource(R.string.viewmode_setup_view_instructions, setupText)
  val text =
      remember(
          linkColor,
          rawBlurb,
          setupText,
      ) {
        val setupIndex = rawBlurb.indexOf(setupText)

        val linkStyle =
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )

        val spanStyles =
            listOf(
                AnnotatedString.Range(
                    linkStyle,
                    start = setupIndex,
                    end = setupIndex + setupText.length,
                ),
            )

        val visualString =
            AnnotatedString(
                rawBlurb,
                spanStyles = spanStyles,
            )

        // Can only add annotations to builders
        return@remember AnnotatedString.Builder(visualString)
            .apply {
              addLink(
                  clickable =
                      LinkAnnotation.Clickable(
                          tag = "Placeholder, onClick handled in code",
                          linkInteractionListener = { handlePlaceholderLinkClicked(it) },
                      ),
                  start = setupIndex,
                  end = setupIndex + setupText.length,
              )
            }
            .toAnnotatedString()
      }

  Column(
      modifier = modifier,
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        style =
            MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Center,
            ),
        text = text,
    )

    SlowSpeedsUpsell(
        style = MaterialTheme.typography.bodyMedium,
        onClick = onViewSlowSpeedHelp,
    )
  }
}
