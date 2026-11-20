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

package com.pyamsoft.tetherfi.status.sections.network

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.text.trimmedLength
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.haptics.LocalHapticManager
import com.pyamsoft.tetherfi.server.ServerDefaults
import com.pyamsoft.tetherfi.status.R
import com.pyamsoft.tetherfi.status.StatusViewState
import com.pyamsoft.tetherfi.ui.R as R2
import com.pyamsoft.tetherfi.ui.ServerViewState
import com.pyamsoft.tetherfi.ui.icons.IconPainters

private const val MAX_SSID_ALLOWED_LENGTH = 32

@Composable
internal fun EditProxyPort(
    modifier: Modifier = Modifier,
    serverViewState: ServerViewState,
    onPortChanged: (Int) -> Unit,
) {
  val port by serverViewState.port.collectAsStateWithLifecycle()

  val portState = rememberTextFieldState(port.toString())
  val portString = portState.text

  val isValid =
      remember(portString) {
        val portInt = portString.toString().toIntOrNull()
        return@remember portInt != null && portInt in 1025..65000
      }

  val label = stringResource(R.string.hotspot_proxy_port)

  val handlePortChanged by rememberUpdatedState(onPortChanged)
  LaunchedEffect(portString) {
    val portInt = portString.toString().toIntOrNull()
    if (portInt != null) {
      handlePortChanged(portInt)
    }
  }

  StatusEditor(
      modifier = modifier,
      title = label,
      state = portState,
      keyboardOptions =
          KeyboardOptions(
              keyboardType = KeyboardType.Number,
          ),
      trailingIcon = {
        ValidIcon(
            isValid = isValid,
            description =
                stringResource(
                    R.string.editmode_label_map,
                    label,
                    stringResource(
                        if (isValid) R.string.editmode_label_valid
                        else R.string.editmode_label_invalid,
                    ),
                ),
        )
      },
  )
}

@Composable
internal fun EditPassword(
    modifier: Modifier = Modifier,
    state: StatusViewState,
    onPasswordChanged: (CharSequence) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
) {
  val userDefinedPassword = state.password
  val canUseCustomConfig = remember { ServerDefaults.canUseCustomConfig() }
  val systemDefined = stringResource(R.string.editmode_system_defined)

  val hotspotPassword =
      if (canUseCustomConfig) userDefinedPassword else rememberTextFieldState(systemDefined)
  val passwordText = hotspotPassword.text
  val isValid =
      remember(
          canUseCustomConfig,
          passwordText,
      ) {
        !canUseCustomConfig || passwordText.trimmedLength() in 8..63
      }

  val isPasswordVisible by state.isPasswordVisible.collectAsStateWithLifecycle()

  val hapticManager = LocalHapticManager.current

  // Password is always visible when set by system
  val isPasswordCurrentlyVisible =
      remember(
          canUseCustomConfig,
          isPasswordVisible,
      ) {
        if (canUseCustomConfig) {
          isPasswordVisible
        } else {
          true
        }
      }

  val trailingIcon: (@Composable () -> Unit)? =
      if (canUseCustomConfig) {
        {
          Row(
              modifier = Modifier.padding(horizontal = MaterialTheme.keylines.content),
              verticalAlignment = Alignment.CenterVertically,
          ) {
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
                      if (isPasswordVisible) IconPainters.visibilityOff()
                      else IconPainters.visibility(),
                  contentDescription =
                      stringResource(
                          R.string.editmode_label_map,
                          stringResource(R2.string.password),
                          stringResource(
                              if (isValid) R.string.editmode_label_visible
                              else R.string.editmode_label_hidden,
                          ),
                      ),
              )
            }

            ValidIcon(
                isValid = isValid,
                description =
                    stringResource(
                        R.string.editmode_label_map,
                        stringResource(R2.string.password),
                        stringResource(
                            if (isValid) R.string.editmode_label_valid
                            else R.string.editmode_label_invalid,
                        ),
                    ),
            )
          }
        }
      } else {
        null
      }

  val handlePasswordChanged by rememberUpdatedState(onPasswordChanged)

  LaunchedEffect(
      passwordText,
      canUseCustomConfig,
  ) {
    if (canUseCustomConfig) {
      handlePasswordChanged(passwordText)
    }
  }

  StatusEditor(
      modifier = modifier,
      enabled = canUseCustomConfig,
      title = stringResource(R.string.editmode_hotspot_password),
      state = hotspotPassword,
      textObfuscationMode =
          if (isPasswordCurrentlyVisible) TextObfuscationMode.Visible
          else TextObfuscationMode.System,
      keyboardOptions =
          KeyboardOptions(
              keyboardType = KeyboardType.Password,
          ),
      trailingIcon = trailingIcon,
  )
}

@Composable
internal fun EditSsid(
    modifier: Modifier = Modifier,
    state: StatusViewState,
    onSsidChanged: (CharSequence) -> Unit,
) {
  val userDefinedSsid = state.ssid
  val canUseCustomConfig = remember { ServerDefaults.canUseCustomConfig() }
  val systemDefined = stringResource(R.string.editmode_system_defined)

  val hotspotSsid =
      if (canUseCustomConfig) userDefinedSsid else rememberTextFieldState(systemDefined)
  val ssidText = hotspotSsid.text

  val isValid =
      remember(
          canUseCustomConfig,
          ssidText,
      ) {
        if (canUseCustomConfig) {
          if (ssidText.isBlank()) {
            return@remember false
          }

          // SSID must be less than 32 bytes long
          // This is enforced by some IEEE standard
          // http://standards.ieee.org/getieee802/download/802.11-2012.pdf
          val fullSsid = ServerDefaults.asWifiSsid(ssidText.toString())
          val ssidBytes = fullSsid.toByteArray(charset = Charsets.UTF_8)
          return@remember (ssidBytes.size < MAX_SSID_ALLOWED_LENGTH)
        }

        return@remember true
      }

  val leadingIcon: (@Composable () -> Unit)? =
      if (canUseCustomConfig) {
        {
          val textStyle = LocalTextStyle.current
          Text(
              modifier =
                  Modifier.padding(
                      start = MaterialTheme.keylines.content,
                      end = MaterialTheme.keylines.typography,
                  ),
              text = remember { ServerDefaults.WIFI_SSID_PREFIX },
              style =
                  textStyle.copy(
                      fontFamily = FontFamily.Monospace,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
          )
        }
      } else {
        null
      }

  val trailingIcon: (@Composable () -> Unit)? =
      if (canUseCustomConfig) {
        {
          ValidIcon(
              isValid = isValid,
              description =
                  stringResource(
                      R.string.editmode_label_map,
                      stringResource(R.string.editmode_type_ssid),
                      stringResource(
                          if (isValid) R.string.editmode_label_valid
                          else R.string.editmode_label_invalid,
                      ),
                  ),
          )
        }
      } else {
        null
      }

  val handleSsidChanged by rememberUpdatedState(onSsidChanged)

  LaunchedEffect(
      ssidText,
      canUseCustomConfig,
  ) {
    if (canUseCustomConfig) {
      handleSsidChanged(ssidText)
    }
  }

  StatusEditor(
      modifier = modifier,
      enabled = canUseCustomConfig,
      title = stringResource(R.string.editmode_hotspot_name),
      state = hotspotSsid,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
  )
}
