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

package com.pyamsoft.tetherfi.ui.dialog

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pyamsoft.pydroid.theme.keylines
import com.pyamsoft.pydroid.ui.uri.rememberUriHandler
import com.pyamsoft.tetherfi.core.GITHUB_URL
import com.pyamsoft.tetherfi.ui.R
import com.pyamsoft.tetherfi.ui.icons.IconPainters

private enum class SlowSpeedsContentTypes {
  TITLE,
  ITEM,
  FAQ,
}

@Composable
private fun SlowSpeedsContent(
    modifier: Modifier = Modifier,
) {
  val slowSpeed = stringResource(R.string.hotspot_speed_what)
  val helpItems = stringArrayResource(R.array.hotspot_speed_items)
  val itemTextStyle = MaterialTheme.typography.bodyMedium

  LazyColumn(
      modifier = modifier,
  ) {
    item(
        contentType = SlowSpeedsContentTypes.TITLE,
    ) {
      val upsell = stringResource(R.string.hotspot_speed_sad, slowSpeed)

      Text(
          modifier = Modifier.padding(bottom = MaterialTheme.keylines.content),
          text = upsell,
          style = MaterialTheme.typography.titleMedium,
      )
    }

    items(
        items = helpItems,
        key = { it },
        contentType = { SlowSpeedsContentTypes.ITEM },
    ) { text ->
      Row(
          modifier = Modifier.padding(bottom = MaterialTheme.keylines.typography),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
            modifier =
                Modifier.padding(
                        vertical = MaterialTheme.keylines.baseline,
                    )
                    .padding(
                        start = MaterialTheme.keylines.typography,
                        end = MaterialTheme.keylines.content,
                    )
                    .size(8.dp),
            painter = IconPainters.radioButtonUnchecked(),
            contentDescription = text,
        )
        Text(
            text = text,
            style = itemTextStyle,
        )
      }
    }

    item(
        contentType = SlowSpeedsContentTypes.FAQ,
    ) {
      SlowSpeedsLink(
          modifier = Modifier.padding(top = MaterialTheme.keylines.content),
          slowSpeed = slowSpeed,
          style = itemTextStyle,
      )
    }
  }
}

@Composable
private fun SlowSpeedsLink(
    modifier: Modifier = Modifier,
    slowSpeed: String,
    style: TextStyle,
) {
  val uriHandler = rememberUriHandler()
  val handleLinkClicked by rememberUpdatedState { link: LinkAnnotation ->
    if (link is LinkAnnotation.Url) {
      uriHandler.openUri(link.url)
    }
  }

  val linkColor = MaterialTheme.colorScheme.primary

  val rawBlurb = stringResource(R.string.hotspot_speed_faq, slowSpeed)
  val text =
      remember(
          linkColor,
          rawBlurb,
          slowSpeed,
      ) {
        val slowSpeedIndex = rawBlurb.indexOf(slowSpeed)

        val linkStyle =
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )

        val spanStyles =
            listOf(
                AnnotatedString.Range(
                    linkStyle,
                    start = slowSpeedIndex,
                    end = slowSpeedIndex + slowSpeed.length,
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
                          url = "${GITHUB_URL}/wiki/Slow-Hotspot-Performance",
                          linkInteractionListener = { handleLinkClicked(it) },
                      ),
                  start = slowSpeedIndex,
                  end = slowSpeedIndex + slowSpeed.length,
              )
            }
            .toAnnotatedString()
      }

  Text(
      modifier = modifier,
      style = style,
      text = text,
  )
}

@Composable
fun SlowSpeedsDialog(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
  CardDialog(
      modifier = modifier,
      onDismiss = onDismiss,
  ) {
    DialogToolbar(
        modifier = Modifier.fillMaxWidth(),
        onClose = onDismiss,
        title = {
          // Intentionally blank
        },
    )
    SlowSpeedsContent(
        modifier = Modifier.padding(MaterialTheme.keylines.content),
    )
  }
}

@Composable
fun SlowSpeedsUpsell(
    modifier: Modifier = Modifier,
    style: TextStyle,
    onClick: () -> Unit,
) {
  val slowSpeed = stringResource(R.string.hotspot_speed_what)
  val upsell = stringResource(R.string.hotspot_speed_sad, slowSpeed)

  val handlePlaceholderLinkClicked by rememberUpdatedState { link: LinkAnnotation ->
    if (link is LinkAnnotation.Clickable) {
      onClick()
    }
  }

  val linkColor = MaterialTheme.colorScheme.primary
  val text =
      remember(
          linkColor,
          upsell,
          slowSpeed,
      ) {
        val slowSpeedIndex = upsell.indexOf(slowSpeed)

        val linkStyle =
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )

        val spanStyles =
            listOf(
                AnnotatedString.Range(
                    linkStyle,
                    start = slowSpeedIndex,
                    end = slowSpeedIndex + slowSpeed.length,
                ),
            )

        val visualString =
            AnnotatedString(
                upsell,
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
                  start = slowSpeedIndex,
                  end = slowSpeedIndex + slowSpeed.length,
              )
            }
            .toAnnotatedString()
      }

  Text(
      modifier = modifier,
      style = style,
      text = text,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewSlowSpeedsDialog() {
  SlowSpeedsDialog(
      onDismiss = {},
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewSlowSpeedsContent() {
  SlowSpeedsContent()
}

@Composable
@Preview(showBackground = true)
private fun PreviewSlowSpeedsLink() {
  SlowSpeedsLink(
      slowSpeed = stringResource(R.string.hotspot_speed_what),
      style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
@Preview(showBackground = true)
private fun PreviewSlowSpeedsUpsell() {
  SlowSpeedsUpsell(
      style = MaterialTheme.typography.bodyMedium,
      onClick = {},
  )
}
