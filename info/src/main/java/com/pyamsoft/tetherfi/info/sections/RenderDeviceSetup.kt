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

package com.pyamsoft.tetherfi.info.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.info.InfoViewOptionsType
import com.pyamsoft.tetherfi.info.InfoViewState
import com.pyamsoft.tetherfi.info.MutableInfoViewState
import com.pyamsoft.tetherfi.info.R
import com.pyamsoft.tetherfi.server.broadcast.BroadcastType
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.icons.IconPainters
import com.pyamsoft.tetherfi.ui.rememberPortNumber
import com.pyamsoft.tetherfi.ui.rememberServerHostname
import com.pyamsoft.tetherfi.ui.rememberServerPassword
import com.pyamsoft.tetherfi.ui.rememberServerRawPassword
import com.pyamsoft.tetherfi.ui.rememberServerSSID
import com.pyamsoft.tetherfi.ui.test.TestServerState
import com.pyamsoft.tetherfi.ui.test.makeTestServerState
import org.jetbrains.annotations.TestOnly

private enum class DeviceSetupContentTypes {
  SETTINGS,
  CONNECT,
}

internal fun LazyListScope.renderDeviceSetup(
    itemModifier: Modifier = Modifier,
    appName: String,
    state: InfoViewState,
    serverViewState: ServerViewState,
    onShowQRCode: () -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleShowOptions: (InfoViewOptionsType) -> Unit,
) {
  item(
      contentType = DeviceSetupContentTypes.SETTINGS,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()
    if (broadcastType == BroadcastType.WIFI_DIRECT) {
      // Only render this instruction for Wi-Fi Direct
      OtherInstruction(
          modifier = itemModifier,
      ) {
        Text(
            text = stringResource(R.string.open_wifi_settings),
            style = MaterialTheme.typography.bodyLarge,
        )
      }
    } else if (broadcastType == BroadcastType.RNDIS) {
      // Only render this instruction for RNDIS
      OtherInstruction(
          modifier = itemModifier,
      ) {
        Text(
            text = stringResource(R.string.open_connection_settings),
            style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }

  item(
      contentType = DeviceSetupContentTypes.CONNECT,
  ) {
    val broadcastType by serverViewState.broadcastType.collectAsStateWithLifecycle()
    OtherInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      Column {
        if (broadcastType == BroadcastType.WIFI_DIRECT) {
          // Only render this instruction for Wi-Fi Direct
          Text(
              text = stringResource(R.string.connect_to_hotspot, appName),
              style =
                  MaterialTheme.typography.labelMedium.copy(
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
          )

          Row {
            val group by serverViewState.group.collectAsStateWithLifecycle()
            val ssid = rememberServerSSID(group)

            val password = rememberServerRawPassword(group)
            val isNetworkReadyForQRCode =
                remember(
                    ssid,
                    password,
                ) {
                  ssid.isNotBlank() && password.isNotBlank()
                }

            Text(
                text = stringResource(R.string.label_hotspot_name),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )

            Text(
                modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                text = ssid,
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.W700,
                        fontFamily = FontFamily.Monospace,
                    ),
            )

            if (isNetworkReadyForQRCode) {
              // Don't use IconButton because we don't care about minimum touch target size
              Box(
                  modifier =
                      Modifier.padding(start = MaterialTheme.keylines.baseline)
                          .clickable { onShowQRCode() }
                          .padding(MaterialTheme.keylines.typography),
                  contentAlignment = Alignment.Center,
              ) {
                Icon(
                    modifier = Modifier.size(16.dp),
                    painter = IconPainters.qrCode(),
                    contentDescription = stringResource(com.pyamsoft.tetherfi.ui.R.string.qr_code),
                    tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          Row {
            val group by serverViewState.group.collectAsStateWithLifecycle()
            val isPasswordVisible by state.isPasswordVisible.collectAsStateWithLifecycle()
            val password = rememberServerPassword(group, isPasswordVisible)
            val rawPassword = rememberServerRawPassword(group)

            val hapticManager = LocalHapticManager.current

            Text(
                text = stringResource(com.pyamsoft.tetherfi.ui.R.string.password),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Text(
                modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                text = password,
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.W700,
                        fontFamily = FontFamily.Monospace,
                    ),
            )

            if (rawPassword.isNotBlank()) {
              // Don't use IconButton because we don't care about minimum touch target size
              Box(
                  modifier =
                      Modifier.padding(start = MaterialTheme.keylines.baseline)
                          .clickable {
                            if (isPasswordVisible) {
                              hapticManager?.toggleOff()
                            } else {
                              hapticManager?.toggleOn()
                            }
                            onTogglePasswordVisibility()
                          }
                          .padding(MaterialTheme.keylines.typography),
                  contentAlignment = Alignment.Center,
              ) {
                Icon(
                    modifier = Modifier.size(16.dp),
                    painter =
                        if (isPasswordVisible) IconPainters.visibilityOff()
                        else IconPainters.visibility(),
                    contentDescription =
                        stringResource(
                            if (isPasswordVisible) com.pyamsoft.tetherfi.ui.R.string.pass_visible
                            else com.pyamsoft.tetherfi.ui.R.string.pass_hidden
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }

        Text(
            modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
            text = stringResource(R.string.configure_the_proxy_settings),
            style = MaterialTheme.typography.bodyLarge,
        )

        Column(
            modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
        ) {
          val showHttpOptions by state.showHttpOptions.collectAsStateWithLifecycle()

          val isHttpEnabled by serverViewState.isHttpEnabled.collectAsStateWithLifecycle()
          if (isHttpEnabled) {
            Row(
                modifier = Modifier.clickable { onToggleShowOptions(InfoViewOptionsType.HTTP) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                  text = stringResource(R.string.view_http_options),
                  style =
                      MaterialTheme.typography.labelLarge.copy(
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
              )

              Icon(
                  modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                  painter =
                      if (showHttpOptions) IconPainters.keyboardArrowRight()
                      else IconPainters.keyboardArrowDown(),
                  contentDescription = stringResource(R.string.view_http_options),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          AnimatedVisibility(
              visible = showHttpOptions,
          ) {
            Column(
                modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
            ) {
              Text(
                  text = stringResource(R.string.http_manual_proxy),
                  style =
                      MaterialTheme.typography.labelMedium.copy(
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
              )

              Row(
                  modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                val connection by serverViewState.connection.collectAsStateWithLifecycle()
                val ipAddress = rememberServerHostname(connection)

                Text(
                    text = stringResource(R.string.label_hotspot_hostname),
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
                Text(
                    modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                    text = ipAddress,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.W700,
                            fontFamily = FontFamily.Monospace,
                        ),
                )
              }

              Row(
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                val httpPortNumber by serverViewState.httpPort.collectAsStateWithLifecycle()
                val httpPort = rememberPortNumber(httpPortNumber)

                Text(
                    text = stringResource(R.string.label_hotspot_http_port),
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )

                Text(
                    modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                    text = httpPort,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.W700,
                            fontFamily = FontFamily.Monospace,
                        ),
                )
              }
            }
          }
        }

        val isSocksEnabled by serverViewState.isSocksEnabled.collectAsStateWithLifecycle()
        if (isSocksEnabled) {
          Column(
              modifier = Modifier.padding(top = MaterialTheme.keylines.baseline),
          ) {
            val showSocksOptions by state.showSocksOptions.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier.clickable { onToggleShowOptions(InfoViewOptionsType.SOCKS) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                  text = stringResource(R.string.view_socks_options),
                  style =
                      MaterialTheme.typography.labelLarge.copy(
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
              )

              Icon(
                  modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                  painter =
                      if (showSocksOptions) IconPainters.keyboardArrowRight()
                      else IconPainters.keyboardArrowDown(),
                  contentDescription = stringResource(R.string.view_http_options),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            AnimatedVisibility(
                visible = showSocksOptions,
            ) {
              Column(
                  modifier = Modifier.padding(top = MaterialTheme.keylines.content),
              ) {
                Text(
                    text = stringResource(R.string.socks_manual_proxy),
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )

                Row(
                    modifier = Modifier.padding(top = MaterialTheme.keylines.typography),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  val connection by serverViewState.connection.collectAsStateWithLifecycle()
                  val ipAddress = rememberServerHostname(connection)

                  Text(
                      text = stringResource(R.string.label_hotspot_hostname),
                      style =
                          MaterialTheme.typography.bodyLarge.copy(
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          ),
                  )
                  Text(
                      modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                      text = ipAddress,
                      style =
                          MaterialTheme.typography.bodyLarge.copy(
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              fontWeight = FontWeight.W700,
                              fontFamily = FontFamily.Monospace,
                          ),
                  )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  val socksPortNumber by serverViewState.socksPort.collectAsStateWithLifecycle()
                  val socksPort = rememberPortNumber(socksPortNumber)

                  Text(
                      text = stringResource(R.string.label_hotspot_socks_port),
                      style =
                          MaterialTheme.typography.bodyLarge.copy(
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          ),
                  )

                  Text(
                      modifier = Modifier.padding(start = MaterialTheme.keylines.typography),
                      text = socksPort,
                      style =
                          MaterialTheme.typography.bodyLarge.copy(
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              fontWeight = FontWeight.W700,
                              fontFamily = FontFamily.Monospace,
                          ),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@TestOnly
@Composable
private fun PreviewDeviceSetup(
    state: InfoViewState,
    server: TestServerState,
    http: Boolean,
    socks: Boolean,
) {
  LazyColumn {
    renderDeviceSetup(
        appName = "TEST",
        serverViewState = makeTestServerState(server, http, socks),
        state = state,
        onTogglePasswordVisibility = {},
        onShowQRCode = {},
        onToggleShowOptions = {},
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupEmptyHttp() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.EMPTY,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActiveHttp() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.CONNECTED,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActivePassword() {
  PreviewDeviceSetup(
      state = MutableInfoViewState().apply { isPasswordVisible.value = true },
      server = TestServerState.CONNECTED,
      http = true,
      socks = false,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupEmptySocks() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.EMPTY,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActiveSocks() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.CONNECTED,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActivePasswordSocks() {
  PreviewDeviceSetup(
      state = MutableInfoViewState().apply { isPasswordVisible.value = true },
      server = TestServerState.CONNECTED,
      http = false,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupEmptyBoth() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.EMPTY,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActiveBoth() {
  PreviewDeviceSetup(
      state = MutableInfoViewState(),
      server = TestServerState.CONNECTED,
      http = true,
      socks = true,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewDeviceSetupActivePasswordBoth() {
  PreviewDeviceSetup(
      state = MutableInfoViewState().apply { isPasswordVisible.value = true },
      server = TestServerState.CONNECTED,
      http = true,
      socks = true,
  )
}
