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

package com.pyamsoft.tetherfi.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.pyamsoft.tetherfi.ui.R

object IconPainters {

  @Composable fun info() = painterResource(R.drawable.info_24px)

  @Composable fun check() = painterResource(R.drawable.check_24px)

  @Composable fun checkCircle() = painterResource(R.drawable.check_circle_24px)

  @Composable fun radioButtonUnchecked() = painterResource(R.drawable.radio_button_unchecked_24px)

  @Composable fun visibility() = painterResource(R.drawable.visibility_24px)

  @Composable fun visibilityOff() = painterResource(R.drawable.visibility_off_24px)

  @Composable fun qrCode() = painterResource(R.drawable.qr_code_24px)

  @Composable fun keyboardArrowRight() = painterResource(R.drawable.keyboard_arrow_right_24px)

  @Composable fun keyboardArrowDown() = painterResource(R.drawable.keyboard_arrow_down_24px)

  @Composable fun settings() = painterResource(R.drawable.settings_24px)

  @Composable fun moreVert() = painterResource(R.drawable.more_vert_24px)

  @Composable fun warning() = painterResource(R.drawable.warning_24px)

  @Composable fun refresh() = painterResource(R.drawable.refresh_24px)

  @Composable fun devices() = painterResource(R.drawable.devices_24px)

  @Composable fun mobile() = painterResource(R.drawable.mobile_24px)
}
