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

package com.pyamsoft.tetherfi.ui.qr

import android.graphics.Bitmap
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import qrcode.QRCode

class QRCodeViewModeler
@Inject
internal constructor(
    override val state: MutableQRCodeViewState,
    @Named("ssid") ssid: String,
    @Named("password") password: String,
    private val dispatchers: AppDispatchers,
) : QRCodeViewState by state, AbstractViewModeler<QRCodeViewState>(state) {

  private val qrData by lazy {
    formatQRCode(
        ssid = ssid,
        password = password,
    )
  }

  @CheckResult
  private suspend fun renderQRCode(): Bitmap =
      withContext(context = dispatchers.default) {
        QRCode.ofSquares().build(qrData).render().nativeImage().cast<Bitmap>().requireNotNull()
      }

  fun load(scope: CoroutineScope) {
    scope.launch(context = dispatchers.default) { state.qrCode.value = renderQRCode() }
  }

  companion object {

    @JvmStatic
    @CheckResult
    private fun formatQRCode(
        ssid: String,
        password: String,
    ): String {
      return "WIFI:T:WPA;S:${ssid};P:${password};H:;;"
    }
  }
}
