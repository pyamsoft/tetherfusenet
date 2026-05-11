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

package com.pyamsoft.tetherfi.server.broadcast.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Parcelable
import androidx.annotation.CheckResult
import androidx.core.content.ContextCompat
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.bus.internal.DefaultEventBus
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.broadcast.BroadcastEvent
import com.pyamsoft.tetherfi.server.broadcast.BroadcastObserver
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
internal class WifiDirectReceiver
@Inject
internal constructor(
    private val enforcer: ThreadEnforcer,
    private val context: Context,
    private val shutdownBus: EventBus<ServerShutdownEvent>,
) : BroadcastReceiver(), WifiDirectRegister, BroadcastObserver {

  private val receiverScope by lazy {
    CoroutineScope(
        context = SupervisorJob() + Dispatchers.Default + CoroutineName(this::class.java.name),
    )
  }

  private val eventBus = DefaultEventBus<WidiNetworkEvent>()
  private val registered = MutableStateFlow(false)

  private suspend fun handleStateChangedAction(intent: Intent) {
    when (val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 0)) {
      WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
        Timber.d { "WiFi Direct: Enabled" }
        eventBus.emit(WidiNetworkEvent.WifiEnabled)
      }
      WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
        Timber.d { "WiFi Direct: Disabled" }
        eventBus.emit(WidiNetworkEvent.WifiDisabled)

        // Fire the shutdown event to the service
        //
        // The service shutdown will properly clean up things like this WDN, as well as wakelocks
        // and notifications
        shutdownBus.emit(ServerShutdownEvent(throwable = null))
      }
      else -> Timber.w { "Unknown Wifi p2p state: $p2pState" }
    }
  }

  private suspend fun handleConnectionChangedAction(intent: Intent) {
    val hostName = resolveWifiGroupHostname(intent)
    if (hostName.isNotBlank()) {
      eventBus.emit(WidiNetworkEvent.ConnectionChanged(hostName))
    }
  }

  private suspend fun handleDiscoveryChangedAction() {
    eventBus.emit(WidiNetworkEvent.DiscoveryChanged)
  }

  private suspend fun handlePeersChangedAction() {
    eventBus.emit(WidiNetworkEvent.PeersChanged)
  }

  private suspend fun handleThisDeviceChangedAction() {
    eventBus.emit(WidiNetworkEvent.ThisDeviceChanged)
  }

  override fun listenNetworkEvents(): Flow<BroadcastEvent> {
    return eventBus.map { event ->
      when (event) {
        is WidiNetworkEvent.ConnectionChanged ->
            BroadcastEvent.ConnectionChanged(
                hostName = event.hostName,
            )
        is WidiNetworkEvent.PeersChanged -> BroadcastEvent.RequestPeers
        else -> BroadcastEvent.Other
      }
    }
  }

  private fun unregister() {
    enforcer.assertOnMainThread()

    Timber.d { "Unregister Wifi Receiver" }
    context.unregisterReceiver(this)
  }

  override suspend fun register() {
    val self = this

    withContext(context = Dispatchers.Default) {
      if (registered.compareAndSet(expect = false, update = true)) {
        try {
          // Hold this here until the coroutine is cancelled
          coroutineScope {
            withContext(context = Dispatchers.Main) {
              Timber.d { "Register Wifi Receiver" }
              ContextCompat.registerReceiver(
                  context,
                  self,
                  INTENT_FILTER,
                  ContextCompat.RECEIVER_NOT_EXPORTED,
              )
            }

            // And suspend until we are done
            Timber.d { "Await Receiver cancellation..." }
            awaitCancellation()
          }
        } finally {
          withContext(context = NonCancellable) {
            if (registered.compareAndSet(expect = true, update = false)) {
              withContext(context = Dispatchers.Main) { unregister() }
            }
          }
        }
      }
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    // Go async in case scope work takes a long time
    val pending = goAsync()

    // Use Default here instead of ProxyDispatcher
    receiverScope.launch(context = Dispatchers.Default) {
      try {
        when (val action = intent.action) {
          WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> handleStateChangedAction(intent)
          WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChangedAction(intent)
          WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> handleDiscoveryChangedAction()
          WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> handlePeersChangedAction()
          WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> handleThisDeviceChangedAction()
          else -> {
            Timber.w { "Unhandled intent action: $action" }
          }
        }
      } finally {
        withContext(context = Dispatchers.Main) {
          // Mark BR as finished
          pending.finish()
        }
      }
    }
  }

  companion object {

    @CheckResult
    private inline fun <reified T : Parcelable> Intent.resolveParcelableExtra(name: String): T? {
      val self = this
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        self.getParcelableExtra(name, T::class.java)
      } else {
        @Suppress("DEPRECATION") self.getParcelableExtra(name)
      }
    }

    @CheckResult
    private fun resolveWifiGroupHostname(intent: Intent): String {
      val p2pInfo: WifiP2pInfo? = intent.resolveParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
      if (p2pInfo == null) {
        Timber.w { "No P2P Info in connection intent" }
        return ""
      }

      val address = p2pInfo.groupOwnerAddress
      if (address == null) {
        Timber.w { "No Group owner address in connection intent" }
        return ""
      }

      return address.hostAddress.orEmpty()
    }

    private val INTENT_FILTER =
        IntentFilter().apply {
          addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
          addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
  }
}
