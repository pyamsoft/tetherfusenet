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

package com.pyamsoft.tetherfi.info

import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.core.cast
import javax.inject.Inject
import kotlinx.coroutines.flow.update

class InfoViewModeler
@Inject
internal constructor(
    override val state: MutableInfoViewState,
) : InfoViewState by state, AbstractViewModeler<InfoViewState>(state) {

  override fun registerSaveState(
      registry: SaveableStateRegistry
  ): List<SaveableStateRegistry.Entry> =
      mutableListOf<SaveableStateRegistry.Entry>().apply {
        registry
            .registerProvider(KEY_SHOW_HTTP_OPTIONS) { state.showHttpOptions.value }
            .also { add(it) }

        registry
            .registerProvider(KEY_SHOW_SOCKS_OPTIONS) { state.showSocksOptions.value }
            .also { add(it) }
      }

  override fun consumeRestoredState(registry: SaveableStateRegistry) {
    registry.consumeRestored(KEY_SHOW_HTTP_OPTIONS)?.cast<Boolean>()?.also {
      state.showHttpOptions.value = it
    }

    registry.consumeRestored(KEY_SHOW_SOCKS_OPTIONS)?.cast<Boolean>()?.also {
      state.showSocksOptions.value = it
    }
  }

  fun handleTogglePasswordVisibility() {
    state.isPasswordVisible.update { !it }
  }

  fun handleToggleOptions(type: InfoViewOptionsType) =
      when (type) {
        InfoViewOptionsType.HTTP -> {
          state.showHttpOptions.update { !it }
        }

        InfoViewOptionsType.SOCKS -> {
          state.showSocksOptions.update { !it }
        }
      }

  companion object {

    private const val KEY_SHOW_HTTP_OPTIONS = "show_http_options"
    private const val KEY_SHOW_SOCKS_OPTIONS = "show_socks_options"
  }
}
