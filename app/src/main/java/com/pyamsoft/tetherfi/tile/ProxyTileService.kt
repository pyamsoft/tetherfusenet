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

@file:LintIgnoreTooManyFunctions

package com.pyamsoft.tetherfi.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.LintIgnoreSwallowedException
import com.pyamsoft.pydroid.core.LintIgnoreTooGenericExceptionCaught
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.R
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.tile.TileHandler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class ProxyTileService internal constructor() : TileService() {

  @Inject @JvmField internal var dispatchers: AppDispatchers? = null
  @Inject @JvmField internal var tileHandler: TileHandler? = null

  @Inject @JvmField internal var tileActivityLauncher: ProxyTileActivityLauncher? = null

  @Inject @JvmField internal var tileStatus: TileStatus? = null

  private var scope: CoroutineScope? = null

  @CheckResult
  private fun makeScope(): CoroutineScope {
    val dis = dispatchers.requireNotNull()
    return CoroutineScope(
        context = SupervisorJob() + dis.default + CoroutineName(this::class.java.name),
    )
  }

  @CheckResult
  private fun ensureScope(): CoroutineScope {
    return (scope ?: makeScope()).also { scope = it }
  }

  private inline fun withTile(crossinline block: (Tile) -> Unit) {
    val tile = qsTile
    if (tile != null) {
      block(tile)

      // Make sure we call this or nothing actually happens
      try {
        tile.updateTile()
      } catch (@LintIgnoreSwallowedException @LintIgnoreTooGenericExceptionCaught e: Throwable) {
        // Sometimes this just crashes because of an NPE
        // fun I know.
        Timber.e(e) { "Unable to update tile :(" }
      }
    } else {
      Timber.w { "Cannot update tile, no QS Tile, try requesting LS update" }
      updateTile(application)
    }
  }

  private fun setTileStatus(status: RunningStatus) {
    val state: Int
    val title: String
    val description: String
    when (status) {
      is RunningStatus.Error -> {
        state = Tile.STATE_INACTIVE
        title = "ERROR"
        description = status.throwable.message ?: "An unexpected error occurred"
      }

      is RunningStatus.NotRunning -> {
        state = Tile.STATE_INACTIVE
        title = getString(R.string.app_name)
        description = "Click to start Hotspot"
      }

      is RunningStatus.Running -> {
        state = Tile.STATE_ACTIVE
        title = getString(R.string.app_name)
        description = "Hotspot Running"
      }

      is RunningStatus.Starting -> {
        state = Tile.STATE_INACTIVE
        title = getString(R.string.app_name)
        description = "Starting..."
      }

      is RunningStatus.Stopping -> {
        state = Tile.STATE_ACTIVE
        title = getString(R.string.app_name)
        description = "Stopping..."
      }
    }

    withTile { tile ->
      tile.state = state
      tile.label = title
      tile.contentDescription = description

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        tile.stateDescription = description
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        tile.subtitle = description
      }
    }
  }

  private fun handleNetworkErrorState(err: RunningStatus.Error) {
    setTileStatus(err)
  }

  private fun handleNetworkNotRunningState() {
    setTileStatus(RunningStatus.NotRunning)
  }

  private fun handleNetworkRunningState() {
    setTileStatus(RunningStatus.Running)
  }

  private fun handleNetworkStartingState() {
    setTileStatus(RunningStatus.Starting)
  }

  private fun handleNetworkStoppingState() {
    setTileStatus(RunningStatus.Stopping)
  }

  private fun withHandler(block: (TileHandler) -> Unit) {
    if (tileHandler == null) {
      // Need to constantly re-bind here because each time this is called, the tile service may have
      // changed
      //
      // We also must inject via our own SubComponent to ensure that Dagger re-creates and
      // re-injects each time. If we inject directly from the AppComponent, Dagger internally tracks
      // the injection and does not inject again even though the service lifecycle requires it.
      Timber.d { "Injecting handler!" }

      // This can crash if the TileService spins up BEFORE the application class is created
      // /vitals/crashes/fc7545d4e783dd108d01983e58d70f43/details?days=28&versionCode=53
      try {
        ObjectGraph.ApplicationScope.retrieve(this)
            .plusTileService()
            .create(
                service = this,
            )
            .inject(this)
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        Timber.e(e) { "Failed to inject Tile handler, application not ready!" }
      }
    }

    tileHandler?.also { block(it) }
  }

  override fun onClick() {
    Timber.d { "Tile Clicked!" }
    tileActivityLauncher
        .requireNotNull()
        .launchTileActivity(
            action = ProxyTileAction.TOGGLE,
        )
  }

  override fun onStartListening() {
    Timber.d { "Tile starts listening!" }

    // Mark tile alive
    tileStatus?.markAlive()

    withHandler { handler ->
      when (val status = handler.getOverallStatus()) {
        is RunningStatus.Error -> handleNetworkErrorState(status)
        is RunningStatus.NotRunning -> handleNetworkNotRunningState()
        is RunningStatus.Running -> handleNetworkRunningState()
        is RunningStatus.Starting -> handleNetworkStartingState()
        is RunningStatus.Stopping -> handleNetworkStoppingState()
      }
    }
  }

  override fun onTileAdded() {
    super.onTileAdded()

    Timber.d { "Tile added!" }

    // Mark tile alive
    tileStatus?.markAlive()
  }

  override fun onTileRemoved() {
    super.onTileRemoved()

    Timber.d { "Tile removed!" }

    // Mark tile destroyed
    tileStatus?.markDead()
  }

  override fun onCreate() {
    super.onCreate()

    Timber.d { "Tile created!" }

    // Mark tile alive
    tileStatus?.markAlive()

    withHandler { handler ->
      handler.bind(
          scope = ensureScope(),
          onNetworkError = { err -> handleNetworkErrorState(err) },
          onNetworkNotRunning = { handleNetworkNotRunningState() },
          onNetworkRunning = { handleNetworkRunningState() },
          onNetworkStarting = { handleNetworkStartingState() },
          onNetworkStopping = { handleNetworkStoppingState() },
      )
    }
  }

  override fun onDestroy() {
    super.onDestroy()

    Timber.d { "Tile destroyed!" }

    // Cancel everything because this scope is dead
    scope?.cancel()

    scope = null
    tileHandler = null
    tileActivityLauncher = null
    tileStatus = null

    dispatchers = null
  }

  companion object {

    @JvmStatic
    fun updateTile(context: Context) {
      val appContext = context.applicationContext
      val component =
          ComponentName(
              appContext,
              ProxyTileService::class.java,
          )

      try {
        requestListeningState(
            appContext,
            component,
        )
      } catch (@LintIgnoreTooGenericExceptionCaught e: Throwable) {
        // https://github.com/pyamsoft/tetherfusenet/issues/229
        Timber.e(e) { "Error trying to update Tile state. Android OS error?" }
      }
    }
  }
}
