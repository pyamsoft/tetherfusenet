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

package com.pyamsoft.tetherfi.info.sections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.core.LintIgnoreLongMethod
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults
import com.pyamsoft.tetherfi.info.R
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState

private enum class AppSetupContentTypes {
  PREP,
  INTERNET,
  CONFIG,
  BATTERY,
  START,
}

@LintIgnoreLongMethod
internal fun LazyListScope.renderAppSetup(
    itemModifier: Modifier = Modifier,
    appName: String,
    serverViewState: ServerViewState,
) {
  renderConnectionPrep(
      itemModifier = itemModifier,
      appName = appName,
      serverViewState = serverViewState,
  )

  item(
      contentType = AppSetupContentTypes.INTERNET,
  ) {
    ThisInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      Column {
        Text(
            text = stringResource(R.string.connect_internet),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.connect_internet_options),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
      }
    }
  }

  item(
      contentType = AppSetupContentTypes.CONFIG,
  ) {
    if (ServerDefaults.canUseCustomConfig()) {
      ThisInstruction(
          modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
          small = true,
      ) {
        Text(
            text = stringResource(R.string.optionally_configure_hotspot),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = TypographyDefaults.ALPHA_DISABLED,
                        ),
                ),
        )
      }
    }
  }

  item(
      contentType = AppSetupContentTypes.BATTERY,
  ) {
    ThisInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
        small = true,
    ) {
      Text(
          text = stringResource(R.string.optionally_configure_power, appName),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color =
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(
                          alpha = TypographyDefaults.ALPHA_DISABLED,
                      ),
              ),
      )
    }
  }

  item(
      contentType = AppSetupContentTypes.START,
  ) {
    ThisInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      Column {
        Text(
            text = stringResource(R.string.start_the_hotspot, appName),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.check_hotspot_green),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
        )
      }
    }
  }
}

private fun LazyListScope.renderConnectionPrep(
    itemModifier: Modifier = Modifier,
    appName: String,
    serverViewState: ServerViewState,
) {
  item(
      contentType = AppSetupContentTypes.PREP,
  ) {
    val type by serverViewState.broadcastType.collectAsStateWithLifecycle()

    @StringRes val titleRes: Int
    @StringRes val descRes: Int
    when (type) {
      BroadcastType.WIFI_DIRECT -> {
        titleRes = R.string.turn_on_wi_fi
        descRes = R.string.wifi_must_be_on
      }

      BroadcastType.RNDIS -> {
        titleRes = R.string.connect_usb_ethernet
        descRes = R.string.usb_tethering_must_be_on
      }

      else -> {
        return@item
      }
    }

    ThisInstruction(
        modifier = itemModifier,
    ) {
      Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(descRes, appName),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
      }
    }
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewAppSetupWifiDirect() {
  LazyColumn {
    renderAppSetup(
        appName = "TEST",
        serverViewState =
            makeTestServerState(
                state = TestServerState.CONNECTED,
                broadcastType = BroadcastType.WIFI_DIRECT,
                isHttpEnabled = false,
                isSocksEnabled = false,
            ),
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewAppSetupRndis() {
  LazyColumn {
    renderAppSetup(
        appName = "TEST",
        serverViewState =
            makeTestServerState(
                state = TestServerState.CONNECTED,
                broadcastType = BroadcastType.RNDIS,
                isHttpEnabled = false,
                isSocksEnabled = false,
            ),
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewAppSetupNone() {
  LazyColumn {
    renderAppSetup(
        appName = "TEST",
        serverViewState =
            makeTestServerState(
                state = TestServerState.CONNECTED,
                broadcastType = null,
                isHttpEnabled = false,
                isSocksEnabled = false,
            ),
    )
  }
}
