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

package com.pyamsoft.tetherfi.server

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.tetherfi.server.battery.BatteryOptimizer
import com.pyamsoft.tetherfi.server.battery.BatteryOptimizerImpl
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClientTracker
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ClientEditor
import com.pyamsoft.tetherfi.server.clients.ClientEraser
import com.pyamsoft.tetherfi.server.clients.ClientManagerImpl
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.StartedClients
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import com.pyamsoft.tetherfi.server.lock.CPULocker
import com.pyamsoft.tetherfi.server.lock.Locker
import com.pyamsoft.tetherfi.server.lock.LockerImpl
import com.pyamsoft.tetherfi.server.lock.ScreenLocker
import com.pyamsoft.tetherfi.server.lock.WiFiLocker
import com.pyamsoft.tetherfi.server.network.AndroidSocketBinder
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.prereq.background.BackgroundDataGuard
import com.pyamsoft.tetherfi.server.prereq.background.BackgroundDataGuardImpl
import com.pyamsoft.tetherfi.server.prereq.location.AndroidLocationChecker
import com.pyamsoft.tetherfi.server.prereq.location.LocationChecker
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuard
import com.pyamsoft.tetherfi.server.prereq.permission.PermissionGuardImpl
import com.pyamsoft.tetherfi.server.prereq.vpn.AndroidVpnChecker
import com.pyamsoft.tetherfi.server.prereq.vpn.VpnChecker
import com.pyamsoft.tetherfi.server.prereq.wifi.AndroidWifiChecker
import com.pyamsoft.tetherfi.server.prereq.wifi.WifiChecker
import com.pyamsoft.tetherfi.server.proxy.AndroidSocketTagger
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.WifiSharedProxy
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.proxy.manager.factory.DefaultProxyManagerFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
abstract class ServerAppModule {

  @Binds
  @CheckResult
  internal abstract fun bindSocketTagger(impl: AndroidSocketTagger): SocketTagger

  @Binds
  @CheckResult
  internal abstract fun bindShutdownConsumer(
      impl: EventBus<ServerShutdownEvent>
  ): EventConsumer<ServerShutdownEvent>

  // Prereqs
  @Binds
  @CheckResult
  internal abstract fun bindPermissionGuard(impl: PermissionGuardImpl): PermissionGuard

  @Binds
  @CheckResult
  internal abstract fun bindBackgroundDataGuard(impl: BackgroundDataGuardImpl): BackgroundDataGuard

  @Binds @CheckResult internal abstract fun bindVpnChecker(impl: AndroidVpnChecker): VpnChecker

  @Binds
  @CheckResult
  internal abstract fun bindLocationChecker(impl: AndroidLocationChecker): LocationChecker

  @Binds @CheckResult internal abstract fun bindWifiChecker(impl: AndroidWifiChecker): WifiChecker

  // OS level
  @Binds
  @CheckResult
  internal abstract fun bindBatteryOptimizer(impl: BatteryOptimizerImpl): BatteryOptimizer

  // Client tracking
  @Binds
  @CheckResult
  internal abstract fun bindBlockedClients(impl: ClientManagerImpl): BlockedClients

  @Binds @CheckResult internal abstract fun bindSeenClients(impl: ClientManagerImpl): AllowedClients

  @Binds @CheckResult internal abstract fun bindClientEraser(impl: ClientManagerImpl): ClientEraser

  @Binds @CheckResult internal abstract fun bindClientEditor(impl: ClientManagerImpl): ClientEditor

  @Binds
  @CheckResult
  internal abstract fun bindClientResolver(impl: ClientManagerImpl): ClientResolver

  @Binds
  @CheckResult
  internal abstract fun bindStartedClients(impl: ClientManagerImpl): StartedClients

  @Binds
  @CheckResult
  internal abstract fun bindBlockedClientTracker(impl: ClientManagerImpl): BlockedClientTracker

  // Proxy
  @Binds @CheckResult internal abstract fun bindProxy(impl: WifiSharedProxy): SharedProxy

  // Network Binder
  @Binds
  @CheckResult
  @ServerInternalApi
  internal abstract fun bindNetworkBinder(impl: AndroidSocketBinder): SocketBinder

  @Binds
  @CheckResult
  @ServerInternalApi
  internal abstract fun bindProxyManagerFactory(
      impl: DefaultProxyManagerFactory
  ): ProxyManager.Factory

  // Lockers
  @Binds
  @IntoSet
  @ServerInternalApi
  internal abstract fun bindScreenLocker(impl: ScreenLocker): Locker

  @Binds @IntoSet @ServerInternalApi internal abstract fun bindCPULocker(impl: CPULocker): Locker

  @Binds @IntoSet @ServerInternalApi internal abstract fun bindWiFiLocker(impl: WiFiLocker): Locker

  @Binds internal abstract fun bindLocker(impl: LockerImpl): Locker

  @Module
  companion object {

    @Provides
    @JvmStatic
    @Singleton
    internal fun provideShutdownEventBus(): EventBus<ServerShutdownEvent> {
      return EventBus.create()
    }
  }
}
