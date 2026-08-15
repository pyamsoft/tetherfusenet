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

package com.pyamsoft.tetherfi.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.arch.AbstractViewModeler
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.ui.theme.Theming
import com.pyamsoft.pydroid.util.AppDispatchers
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class ThemeViewModeler
@Inject
internal constructor(
    override val state: MutableThemeViewState,
    private val theming: Theming,
    private val dispatchers: AppDispatchers,
) : ThemeViewState by state, AbstractViewModeler<ThemeViewState>(state) {

  override fun registerSaveState(
      registry: SaveableStateRegistry
  ): List<SaveableStateRegistry.Entry> =
      mutableListOf<SaveableStateRegistry.Entry>().apply {
        val s = state

        registry.registerProvider(KEY_THEME_MODE) { s.mode.value.name }.also { add(it) }

        registry.registerProvider(KEY_THEME_MATERIAL_YOU) { s.isMaterialYou.value }.also { add(it) }
      }

  override fun consumeRestoredState(registry: SaveableStateRegistry) {
    val s = state
    registry
        .consumeRestored(KEY_THEME_MODE)
        ?.cast<String>()
        ?.let { Theming.Mode.valueOf(it) }
        ?.also { s.mode.value = it }

    registry.consumeRestored(KEY_THEME_MATERIAL_YOU)?.cast<Boolean>()?.also {
      s.isMaterialYou.value = it
    }
  }

  private fun bind(scope: CoroutineScope) {
    combineTransform(
            theming.listenForModeChanges(),
            theming.listenForMaterialYouChanges(),
        ) { mode, isMaterialYou ->
          emit(listOf(mode, isMaterialYou))
        }
        .flowOn(context = dispatchers.default)
        .also { f ->
          scope.launch(context = dispatchers.default) {
            f.collect { list ->
              val mode = list[0].cast<Theming.Mode>().requireNotNull()
              val isMaterialYou = list[1].cast<Boolean>().requireNotNull()
              state.mode.value = mode
              state.isMaterialYou.value = isMaterialYou
            }
          }
        }
  }

  fun init(activity: ComponentActivity) {
    bind(scope = activity.lifecycleScope)
  }

  companion object {

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_THEME_MATERIAL_YOU = "theme_material_you"
  }
}
