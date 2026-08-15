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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.uri.rememberUriHandler
import com.pyamsoft.tetherfi.core.GITHUB_URL
import com.pyamsoft.tetherfi.info.R
import com.pyamsoft.tetherfi.ui.dialog.SlowSpeedsUpsell

private enum class ConnectionCompleteContentTypes {
  SHARING,
  DONE,
  SLOW,
  FULL,
}

internal fun LazyListScope.renderConnectionComplete(
    itemModifier: Modifier = Modifier,
    appName: String,
    onShowSlowSpeedHelp: () -> Unit,
) {
  item(
      contentType = ConnectionCompleteContentTypes.SHARING,
  ) {
    ThisInstruction(
        modifier = itemModifier,
    ) {
      Text(
          text = stringResource(R.string.sharing_complete),
          style = MaterialTheme.typography.bodyLarge,
      )
    }
  }

  item(
      contentType = ConnectionCompleteContentTypes.DONE,
  ) {
    OtherInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      Text(
          text = stringResource(R.string.sharing_caveat, appName),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
  }

  item(
      contentType = ConnectionCompleteContentTypes.SLOW,
  ) {
    OtherInstruction(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    ) {
      SlowSpeedsUpsell(
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
          onClick = onShowSlowSpeedHelp,
      )
    }
  }

  item(
      contentType = ConnectionCompleteContentTypes.FULL,
  ) {
    FullConnectionInstructions(
        modifier = itemModifier.padding(top = MaterialTheme.keylines.content),
    )
  }
}

@Composable
private fun FullConnectionInstructions(
    modifier: Modifier = Modifier,
) {
  val uriHandler = rememberUriHandler()
  val handleLinkClicked by rememberUpdatedState { link: LinkAnnotation ->
    if (link is LinkAnnotation.Url) {
      uriHandler.openUri(link.url)
    }
  }

  val linkColor = MaterialTheme.colorScheme.primary

  val linkText = stringResource(R.string.proxy_having_link_text)
  val rawBlurb = stringResource(R.string.proxy_having_trouble, linkText)
  val linkBlurb =
      remember(
          linkColor,
          rawBlurb,
          linkText,
      ) {
        val linkIndex = rawBlurb.indexOf(linkText)

        val linkStyle =
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )

        val spanStyles =
            listOf(
                AnnotatedString.Range(
                    linkStyle,
                    start = linkIndex,
                    end = linkIndex + linkText.length,
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
                  url =
                      LinkAnnotation.Url(
                          url = "${GITHUB_URL}/wiki/Setup-A-Proxy",
                          linkInteractionListener = { handleLinkClicked(it) },
                      ),
                  start = linkIndex,
                  end = linkIndex + linkText.length,
              )
            }
            .toAnnotatedString()
      }

  Card(
      modifier = modifier.padding(vertical = MaterialTheme.keylines.content),
      border =
          BorderStroke(
              width = 2.dp,
              color = MaterialTheme.colorScheme.primaryContainer,
          ),
      shape = MaterialTheme.shapes.large,
  ) {
    Text(
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.keylines.content),
        text = linkBlurb,
        style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
@Preview(showBackground = true)
private fun PreviewConnectionComplete() {
  LazyColumn {
    renderConnectionComplete(
        appName = "TEST",
        onShowSlowSpeedHelp = {},
    )
  }
}
