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

package com.pyamsoft.tetherfi.status

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.defaults.TypographyDefaults
import com.pyamsoft.tetherfi.server.status.RunningStatus
import org.jetbrains.annotations.TestOnly

private enum class RenderHotspotStatusContentTypes {
  HOTSPOT_STATUS,
}

internal fun LazyListScope.renderHotspotStatus(
    itemModifier: Modifier = Modifier,
    wiDiStatus: RunningStatus,
    proxyStatus: RunningStatus,
    hotspotStatus: RunningStatus,
    onShowBroadcastError: () -> Unit,
    onShowProxyError: () -> Unit,
) {
  item(
      contentType = RenderHotspotStatusContentTypes.HOTSPOT_STATUS,
  ) {
    Card(
        modifier = itemModifier.fillMaxWidth().padding(vertical = MaterialTheme.keylines.content),
        border =
            BorderStroke(
                width = 4.dp,
                color =
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = TypographyDefaults.ALPHA_DISABLED,
                    ),
            ),
        shape = MaterialTheme.shapes.large,
    ) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.keylines.content),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        DisplayStatus(
            modifier = Modifier.weight(1F, fill = false),
            title = stringResource(R.string.title_broadcast_status),
            status = wiDiStatus,
            size = StatusSize.SMALL,
            onClickShowError = onShowBroadcastError,
        )

        DisplayStatus(
            modifier = Modifier.weight(1F, fill = false),
            title = stringResource(R.string.title_proxy_status),
            status = proxyStatus,
            size = StatusSize.SMALL,
            onClickShowError = onShowProxyError,
        )
      }

      Box(
          modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.keylines.content),
          contentAlignment = Alignment.Center,
      ) {
        DisplayStatus(
            title = stringResource(R.string.title_hotspot_status),
            status = hotspotStatus,
            size = StatusSize.NORMAL,
        )
      }
    }
  }
}

@TestOnly
@Composable
private fun PreviewRenderHotspotStatus(
    wiDiStatus: RunningStatus,
    proxyStatus: RunningStatus,
    hotspotStatus: RunningStatus,
) {
  LazyColumn {
    renderHotspotStatus(
        wiDiStatus = wiDiStatus,
        proxyStatus = proxyStatus,
        hotspotStatus = hotspotStatus,
        onShowBroadcastError = {},
        onShowProxyError = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusNotRunning() {
  PreviewRenderHotspotStatus(
      wiDiStatus = RunningStatus.NotRunning,
      proxyStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.NotRunning,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusWiDiStarting() {
  PreviewRenderHotspotStatus(
      wiDiStatus = RunningStatus.Starting,
      proxyStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Starting,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusWiDiRunning() {
  PreviewRenderHotspotStatus(
      wiDiStatus = RunningStatus.Running,
      proxyStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Starting,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusWiDiStopping() {
  PreviewRenderHotspotStatus(
      wiDiStatus = RunningStatus.Stopping,
      proxyStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Starting,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusWiDiError() {
  PreviewRenderHotspotStatus(
      wiDiStatus = RunningStatus.HotspotError(RuntimeException("TEST")),
      proxyStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.HotspotError(RuntimeException("TEST")),
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusProxyStarting() {
  PreviewRenderHotspotStatus(
      proxyStatus = RunningStatus.Starting,
      wiDiStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Starting,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusProxyRunning() {
  PreviewRenderHotspotStatus(
      proxyStatus = RunningStatus.Running,
      wiDiStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Starting,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusProxyStopping() {
  PreviewRenderHotspotStatus(
      proxyStatus = RunningStatus.Stopping,
      wiDiStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.Stopping,
  )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRenderHotspotStatusProxyError() {
  PreviewRenderHotspotStatus(
      proxyStatus = RunningStatus.ProxyError(RuntimeException("TEST")),
      wiDiStatus = RunningStatus.NotRunning,
      hotspotStatus = RunningStatus.ProxyError(RuntimeException("TEST")),
  )
}
