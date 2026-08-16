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

package com.pyamsoft.tetherfi

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.service.quicksettings.TileService
import androidx.annotation.CheckResult
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.notify.NotifyGuard
import com.pyamsoft.pydroid.ui.theme.Theming
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.behavior.BehaviorAppModule
import com.pyamsoft.tetherfi.core.CoreAppModule
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.foreground.ForegroundServiceComponent
import com.pyamsoft.tetherfi.foreground.ProxyForegroundService
import com.pyamsoft.tetherfi.main.MainActivity
import com.pyamsoft.tetherfi.main.MainComponent
import com.pyamsoft.tetherfi.server.ExpertPreferences
import com.pyamsoft.tetherfi.server.ProxyPreferences
import com.pyamsoft.tetherfi.server.ServerAppModule
import com.pyamsoft.tetherfi.server.StatusPreferences
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.WifiPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastServerAppModule
import com.pyamsoft.tetherfi.service.ServiceAppModule
import com.pyamsoft.tetherfi.status.PermissionRequests
import com.pyamsoft.tetherfi.status.PermissionResponse
import com.pyamsoft.tetherfi.tile.ProxyTileActivity
import com.pyamsoft.tetherfi.tile.ProxyTileComponent
import com.pyamsoft.tetherfi.tile.ProxyTileService
import com.pyamsoft.tetherfi.tile.ProxyTileServiceComponent
import com.pyamsoft.tetherfi.tile.TileAppModule
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "tetherfi_preferences",
        corruptionHandler =
            ReplaceFileCorruptionHandler { err ->
              Timber.e(err) { "File corruption detected, start with empty Preferences" }
              return@ReplaceFileCorruptionHandler emptyPreferences()
            },
        produceMigrations = { migrationContext ->
          listOf(
              // NOTE(Peter): Since our shared preferences was the DEFAULT process one, loading up
              //              a migration without specifying all keys will also migrate
              //              PYDROID SPECIFIC PREFERENCES which is what we do NOT want to do.
              //              We instead maintain ONLY a list of the known app preference keys
              SharedPreferencesMigration(
                  keysToMigrate =
                      setOf(
                          PreferenceKeys.SSID.name,
                          PreferenceKeys.PASSWORD.name,
                          PreferenceKeys.PORT.name,
                          PreferenceKeys.NETWORK_BAND.name,
                          PreferenceKeys.IN_APP_HOTSPOT_USED.name,
                          PreferenceKeys.IN_APP_DEVICES_CONNECTED.name,
                          PreferenceKeys.IN_APP_APP_OPENED.name,
                          PreferenceKeys.IN_APP_RATING_SHOWN_VERSION.name,
                          PreferenceKeys.START_IGNORE_VPN.name,
                          PreferenceKeys.START_IGNORE_LOCATION.name,
                          PreferenceKeys.SHUTDOWN_NO_CLIENTS.name,
                          PreferenceKeys.KEEP_SCREEN_ON.name,
                          PreferenceKeys.BROADCAST_TYPE.name,
                          PreferenceKeys.PREFERRED_NETWORK.name,
                          PreferenceKeys.SOCKET_TIMEOUT.name,
                      ),
                  produceSharedPreferences = {
                    PreferenceManager.getDefaultSharedPreferences(
                        migrationContext.applicationContext
                    )
                  },
              )
          )
        },
    )

@Singleton
@Component(
    modules =
        [
            TFAppComponent.Provider::class,
            ServerAppModule::class,
            ServiceAppModule::class,
            BroadcastServerAppModule::class,
            TileAppModule::class,
            CoreAppModule::class,
            BehaviorAppModule::class,
        ],
)
internal interface TFAppComponent {

  @CheckResult fun plusMain(): MainComponent.Factory

  @CheckResult fun plusForeground(): ForegroundServiceComponent.Factory

  @CheckResult fun plusTile(): ProxyTileComponent.Factory

  @CheckResult fun plusTileService(): ProxyTileServiceComponent.Factory

  @Component.Factory
  interface Factory {

    @CheckResult
    fun create(
        @Named("debug") @BindsInstance debug: Boolean,
        @Named("in_app_debug") @BindsInstance inAppDebug: Flow<Boolean>,
        @Named("app_scope") @BindsInstance scope: CoroutineScope,
        @BindsInstance application: Application,
        @BindsInstance imageLoader: ImageLoader,
        @BindsInstance theming: Theming,
        @BindsInstance enforcer: ThreadEnforcer,
        @BindsInstance dispatchers: AppDispatchers,
    ): TFAppComponent
  }

  @Module
  abstract class Provider {

    @Binds internal abstract fun bindTweakPreferences(impl: PreferencesImpl): TweakPreferences

    @Binds internal abstract fun bindWifiPreferences(impl: PreferencesImpl): WifiPreferences

    @Binds internal abstract fun bindExpertPreferences(impl: PreferencesImpl): ExpertPreferences

    @Binds internal abstract fun bindProxyPreferences(impl: PreferencesImpl): ProxyPreferences

    @Binds internal abstract fun bindStatusPreferences(impl: PreferencesImpl): StatusPreferences

    @Binds
    internal abstract fun bindInAppRatingPreferences(impl: PreferencesImpl): InAppRatingPreferences

    @Binds
    internal abstract fun bindPermissionRequestConsumer(
        impl: EventBus<PermissionRequests>
    ): EventConsumer<PermissionRequests>

    @Binds
    internal abstract fun bindPermissionResponseConsumer(
        impl: EventBus<PermissionResponse>
    ): EventConsumer<PermissionResponse>

    @Module
    companion object {

      @Provides
      @JvmStatic
      internal fun provideContext(application: Application): Context {
        return application
      }

      @Provides
      @JvmStatic
      @Named("version")
      internal fun provideVersion(): Int {
        return BuildConfig.VERSION_CODE
      }

      @Provides
      @JvmStatic
      @Named("tile_activity")
      internal fun provideProxyTileActivityClass(): Class<out Activity> {
        return ProxyTileActivity::class.java
      }

      @Provides
      @JvmStatic
      @Named("main_activity")
      internal fun provideMainActivityClass(): Class<out Activity> {
        return MainActivity::class.java
      }

      @Provides
      @JvmStatic
      @Named("service")
      internal fun provideProxyForegroundServiceClass(): Class<out Service> {
        return ProxyForegroundService::class.java
      }

      @Provides
      @JvmStatic
      @Named("app_name")
      internal fun provideAppNameRes(): Int {
        return R.string.app_name
      }

      @Provides
      @JvmStatic
      @Named("app_icon")
      internal fun provideAppIconRes(): Int {
        return R.mipmap.ic_launcher
      }

      @Provides
      @JvmStatic
      @Named("app_icon_foreground")
      internal fun provideAppIconForegroundRes(): Int {
        return R.mipmap.ic_launcher_foreground
      }

      @Provides
      @JvmStatic
      @Singleton
      internal fun provideNotifyGuard(context: Context): NotifyGuard {
        return NotifyGuard.createDefault(context)
      }

      @Provides
      @JvmStatic
      @Singleton
      internal fun provideDataStore(context: Context): DataStore<Preferences> {
        return context.applicationContext.dataStore
      }

      @Provides
      @JvmStatic
      internal fun provideForegroundServiceClass(): Class<out Service> {
        return ProxyForegroundService::class.java
      }

      @Provides
      @JvmStatic
      internal fun provideTileServiceClass(): Class<out TileService> {
        return ProxyTileService::class.java
      }

      @Provides
      @JvmStatic
      @Singleton
      internal fun providePermissionRequestBus(): EventBus<PermissionRequests> {
        return EventBus.create()
      }

      @Provides
      @JvmStatic
      @Singleton
      internal fun providePermissionResponseBus(): EventBus<PermissionResponse> {
        return EventBus.create()
      }

      @Provides
      @JvmStatic
      @Singleton
      internal fun provideClock(): Clock {
        return Clock.systemDefaultZone()
      }
    }
  }
}
