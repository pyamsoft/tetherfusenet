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

package com.pyamsoft.tetherfi.tile

import android.content.Intent
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.tetherfi.core.Timber

object TileActionResolver {

  @CheckResult
  private fun resolveTileActionFromExtras(intent: Intent): ProxyTileAction? {
    val rawValue = intent.getStringExtra(ProxyTileActionKeys.KEY_ACTION)
    if (rawValue != null) {
      try {
        return ProxyTileAction.valueOf(rawValue)
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "Error parsing ProxyTileActivity.Action param: $rawValue" }
      }
    }

    return null
  }

  @CheckResult
  private fun resolveTileActionFromAction(intent: Intent): ProxyTileAction? {
    val rawValue = intent.action
    if (rawValue != null) {
      return when (rawValue) {
        ProxyTileActionValues.VALUE_ACTION_START -> ProxyTileAction.START
        ProxyTileActionValues.VALUE_ACTION_STOP -> ProxyTileAction.STOP
        else -> null
      }
    }

    return null
  }

  @JvmStatic
  @CheckResult
  fun getTileAction(intent: Intent, fallback: ProxyTileAction): ProxyTileAction {
    val extraValue = resolveTileActionFromExtras(intent)
    if (extraValue != null) {
      Timber.d { "Tile Action from Intent Extra: $extraValue" }
      return extraValue
    }

    val actionValue = resolveTileActionFromAction(intent)
    if (actionValue != null) {
      Timber.d { "Tile Action from Intent Action: $actionValue" }
      return actionValue
    }

    // No value means TOGGLE
    Timber.d { "No tile action. fallback=$fallback" }
    return fallback
  }
}
