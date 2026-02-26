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

package com.pyamsoft.tetherfi.server.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.CheckResult
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ExpertPreferences
import io.ktor.network.selector.Selectable
import io.ktor.network.sockets.Socket
import java.net.DatagramSocket
import java.nio.channels.SocketChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

// https://github.com/pyamsoft/tetherfusenet/issues/154
// https://github.com/pyamsoft/tetherfusenet/issues/331
@Singleton
internal class AndroidSocketBinder
@Inject
internal constructor(
    private val passthrough: PassthroughSocketBinder,
    private val preferences: ExpertPreferences,
    private val context: Context,
    private val enforcer: ThreadEnforcer,
) : SocketBinder {

  private val connectivityManager by lazy {
    enforcer.assertOffMainThread()
    context.getSystemService<ConnectivityManager>().requireNotNull()
  }

  @CheckResult
  private fun createMobileDataNetworkCallback(
      preferredNetwork: PreferredNetwork,
      networkState: MutableStateFlow<Network?>,
  ): NetworkCallback {
    return object : NetworkCallback() {
      override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Timber.d { "Preferred network available: $network ($preferredNetwork)" }
        networkState.value = network
      }

      override fun onUnavailable() {
        super.onUnavailable()
        Timber.w { "Preferred network not available! ($preferredNetwork)" }
        networkState.value = null
      }
    }
  }

  /**
   * God this was hard to find
   *
   * https://stackoverflow.com/questions/73413465/android-transport-cellular-network-not-available-if-wifi-is-connected-how-do-we
   * https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,%20android.net.ConnectivityManager.NetworkCallback)
   */
  private fun requestMobileDataNetwork(
      preferredNetwork: PreferredNetwork,
      callback: NetworkCallback,
  ) {
    val request =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .run {
              when (preferredNetwork) {
                PreferredNetwork.NONE -> {
                  Timber.w {
                    "requestMobileDataNetwork called with PreferredNetwork.NONE. This should not happen!"
                  }
                  return@run this
                }

                PreferredNetwork.WIFI -> {
                  Timber.d { "Prefer Wi-Fi connection for transport" }
                  return@run addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }

                PreferredNetwork.CELLULAR -> {
                  Timber.d { "Prefer Cellular Data connection for transport" }
                  return@run addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                }
              }
            }
            .build()

    Timber.d { "Resolving preferred network for transport: $preferredNetwork" }
    connectivityManager.requestNetwork(request, callback)
  }

  @CheckResult
  private suspend fun getPreferredNetwork(): PreferredNetwork {
    enforcer.assertOffMainThread()
    return preferences.listenForPreferredNetwork().first()
  }

  override suspend fun withMobileDataNetworkActive(
      block: suspend (SocketBinder.NetworkBinder) -> Unit
  ) {
    val preferred = getPreferredNetwork()
    if (preferred == PreferredNetwork.NONE) {
      // User does not want to bind, do absolutely nothing
      passthrough.withMobileDataNetworkActive(block)
    } else {
      val networkState = MutableStateFlow<Network?>(null)
      val callback = createMobileDataNetworkCallback(preferred, networkState)
      try {
        // Request first to keep the network alive
        Timber.d { "Open preferred network for binding sockets... ($preferred)" }
        requestMobileDataNetwork(preferred, callback)

        // We are the bound network now
        block(PreferredNetworkBinder(networkState))
      } finally {
        // Once done we unregister
        Timber.d { "Close preferred network ($preferred)" }
        connectivityManager.unregisterNetworkCallback(callback)
      }
    }
  }

  private data class PreferredNetworkBinder(private val preferredNetwork: StateFlow<Network?>) :
      SocketBinder.NetworkBinder {

    override suspend fun bindToNetwork(socket: Socket) {
      if (socket is Selectable) {
        val channel = socket.channel
        if (channel is SocketChannel) {
          val network = preferredNetwork.first()
          if (network != null) {
            bindToNetwork(channel, network)
          }
        } else {
          Timber.w { "Cannot attempt bindSocket - Channel is not SocketChannel: $channel" }
        }
      } else {
        Timber.w { "Cannot attempt bindSocket - Socket is not selectable: $socket" }
      }
    }

    override suspend fun bindToNetwork(datagramSocket: DatagramSocket) {
      val network = preferredNetwork.first()
      if (network != null) {
        bindToNetwork(datagramSocket, network)
      }
    }
  }

  companion object {

    @JvmStatic
    private fun bindToNetwork(datagram: DatagramSocket, network: Network) {
      try {
        // IF you are connected to a VPN, binding to a socket may not work unless you "whitelist"
        // TetherFuseNet in your VPN settings
        network.bindSocket(datagram)
      } catch (e: Throwable) {
        Timber.w {
          "Error binding datagram socket to network $network, continue anyway!: ${e.message.orEmpty()}"
        }
      }
    }

    @JvmStatic
    private fun bindToNetwork(channel: SocketChannel, network: Network) {
      try {
        // IF you are connected to a VPN, binding to a socket may not work unless you "whitelist"
        // TetherFuseNet in your VPN settings
        network.bindSocket(channel.socket())
      } catch (e: Throwable) {
        Timber.w {
          "Error binding socket to network $network, continue anyway!: ${e.message.orEmpty()}"
        }
      }
    }
  }
}