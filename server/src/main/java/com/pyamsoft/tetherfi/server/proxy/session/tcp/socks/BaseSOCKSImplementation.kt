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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.socks

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import com.pyamsoft.tetherfi.server.SocketCreator
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.network.SocketBinder
import com.pyamsoft.tetherfi.server.proxy.ProxyConnectionInfo
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.SocketTracker
import com.pyamsoft.tetherfi.server.proxy.session.tcp.relayData
import com.pyamsoft.tetherfi.server.proxy.usingConnection
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.toJavaAddress
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import java.net.InetAddress
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal abstract class BaseSOCKSImplementation<
    AT : BaseSOCKSImplementation.SOCKSAddressType,
    R : BaseSOCKSImplementation.Responder<AT>,
>
protected constructor(
    protected val appScope: CoroutineScope,
    protected val socketTagger: SocketTagger,
) : SOCKSImplementation<R> {

  private suspend fun connect(
      scope: CoroutineScope,
      socketCreator: SocketCreator,
      timeout: ServerSocketTimeout,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      networkBinder: SocketBinder.NetworkBinder,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      client: TetherClient,
      destinationAddress: InetAddress,
      destinationPort: UShort,
      addressType: AT,
      responder: R,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) =
      socketCreator.create(
          type = SocketCreator.Type.CLIENT,
          onError = {
            // This error comes from the SelectorManager launch {} scope,
            // so everything may be dead. fallback to Dispatchers.IO since we cannot be guaranteed
            // that
            // our custom dispatcher pool is around
            appScope.launch(context = Dispatchers.IO) { onError(it) }
          },
          onBuild = { builder ->
            val connected =
                try {
                  builder
                      .tcp()
                      .configure {
                        reuseAddress = true
                        // As of KTOR-3.0.0, this is not supported and crashes at runtime
                        // reusePort = true
                      }
                      .also { socketTagger.tagSocket() }
                      .let { b ->
                        // SOCKS protocol says you MUST time out after 2 minutes
                        withTimeout(2.minutes) {
                          val remote =
                              InetSocketAddress(
                                  hostname = destinationAddress.hostName,
                                  port = destinationPort.toInt(),
                              )

                          // This function uses our custom build of KTOR
                          // which adds the [onBeforeConnect] hook to allow us
                          // to use the socket created BEFORE connection starts.
                          b.connectWithConfiguration(
                              remoteAddress = remote,
                              configure = {
                                // By default KTOR does not close sockets until "infinity" is
                                // reached.
                                val duration = timeout.timeoutDuration
                                if (!duration.isInfinite()) {
                                  socketTimeout = duration.inWholeMilliseconds
                                }
                              },
                              onBeforeConnect = { networkBinder.bindToNetwork(it) },
                          )
                        }
                      }
                      .also {
                        // Track this socket for when we fully shut down
                        socketTracker.track(it)
                      }
                } catch (e: Throwable) {
                  if (e is TimeoutCancellationException) {
                    Timber.w { "Timeout while waiting for socket connect()" }
                    responder.sendRefusal()

                    // Re-throw cancellation exceptions
                    throw e
                  } else {
                    e.ifNotCancellation {
                      Timber.e(e) { "Error during socket connect()" }
                      responder.sendRefusal()
                      return@create
                    }
                  }
                }

            connected.use { socket ->
              val remote = socket.remoteAddress
              Timber.d { "SOCKS CONNECTED: $remote" }
              try {
                // We've successfully connected, tell the client
                responder.sendConnectSuccess(
                    addressType = addressType,
                    remote = remote.cast<InetSocketAddress>(),
                )
              } catch (e: Throwable) {
                e.ifNotCancellation {
                  Timber.e(e) { "Error sending connect() SUCCESS notification" }
                  return@create
                }
              }

              socket.usingConnection(autoFlush = false) { internetInput, internetOutput ->
                try {
                  relayData(
                      scope = scope,
                      client = client,
                      proxyInput = proxyInput,
                      proxyOutput = proxyOutput,
                      internetInput = internetInput,
                      internetOutput = internetOutput,
                      serverDispatcher = serverDispatcher,
                      onReport = onReport,
                  )
                } finally {
                  internetOutput.flush()
                }
              }
            }
          },
      )

  private suspend fun bind(
      scope: CoroutineScope,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      connectionInfo: BroadcastNetworkStatus.ConnectionInfo.Connected,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      client: TetherClient,
      destinationAddress: InetAddress,
      addressType: AT,
      responder: R,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) =
      socketCreator.create(
          type = SocketCreator.Type.SERVER,
          onError = {
            // This error comes from the SelectorManager launch {} scope,
            // so everything may be dead. fallback to Dispatchers.IO since we cannot be guaranteed
            // that
            // our custom dispatcher pool is around
            appScope.launch(context = Dispatchers.IO) { onError(it) }
          },
          onBuild = { builder ->
            val bound =
                try {
                  builder
                      .tcp()
                      .configure {
                        reuseAddress = true
                        // As of KTOR-3.0.0, this is not supported and crashes at runtime
                        // reusePort = true
                      }
                      .also { socketTagger.tagSocket() }
                      .let { b ->
                        Timber.d { "SOCKS BIND -> ${connectionInfo.hostName}" }
                        b.bind(
                            hostname = connectionInfo.hostName,
                            port = 0,
                            configure = {
                              reuseAddress = true
                              // As of KTOR-3.0.0, this is not supported and crashes at runtime
                              // reusePort = true
                            },
                        )
                      }
                      .also {
                        // Track server socket
                        socketTracker.track(it)
                      }
                      .use { server ->

                        // SOCKS protocol says you MUST time out after 2 minutes
                        val boundSocket = scope.async { withTimeout(2.minutes) { server.accept() } }

                        // Once the bind is open, we send the initial reply telling the client
                        // the IP and the port
                        responder.sendBindInitialized(
                            addressType = addressType,
                            bound = server.localAddress.cast(),
                        )

                        boundSocket.await()
                      }
                } catch (e: Throwable) {
                  if (e is TimeoutCancellationException) {
                    Timber.w { "Timeout while waiting for socket bind()" }
                    responder.sendRefusal()

                    // Rethrow a cancellation exception
                    throw e
                  } else {
                    e.ifNotCancellation {
                      Timber.e(e) { "Error during socket bind()" }
                      responder.sendError()
                      return@create
                    }
                  }
                }

            // Track this socket for when we fully shut down
            socketTracker.track(bound)

            bound.use { socket ->
              val hostAddress = socket.remoteAddress.cast<InetSocketAddress>().requireNotNull()
              if (hostAddress.toJavaAddress() != destinationAddress) {
                Timber.w { "bind() address $hostAddress != original $destinationAddress" }
                responder.sendRefusal()
                return@create
              }

              try {
                responder.sendBindInitialized(
                    addressType = addressType,
                    bound = hostAddress,
                )
              } catch (e: Throwable) {
                e.ifNotCancellation {
                  Timber.e(e) { "Error sending bind() SUCCESS notification" }
                  responder.sendError()
                  return@create
                }
              }

              socket.usingConnection(autoFlush = false) { internetInput, internetOutput ->
                try {
                  relayData(
                      scope = scope,
                      client = client,
                      serverDispatcher = serverDispatcher,
                      proxyInput = proxyInput,
                      proxyOutput = proxyOutput,
                      internetInput = internetInput,
                      internetOutput = internetOutput,
                      onReport = onReport,
                  )
                } finally {
                  internetOutput.flush()
                }
              }
            }
          },
      )

  protected suspend fun performSOCKSCommand(
      scope: CoroutineScope,
      socketCreator: SocketCreator,
      timeout: ServerSocketTimeout,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      connectionInfo: BroadcastNetworkStatus.ConnectionInfo.Connected,
      networkBinder: SocketBinder.NetworkBinder,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      proxyConnectionInfo: ProxyConnectionInfo,
      client: TetherClient,
      command: SOCKSCommand,
      destinationPort: UShort,
      destinationAddress: InetAddress,
      addressType: AT,
      responder: R,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) =
      when (command) {
        SOCKSCommand.CONNECT -> {
          connect(
              scope = scope,
              socketCreator = socketCreator,
              socketTracker = socketTracker,
              networkBinder = networkBinder,
              serverDispatcher = serverDispatcher,
              proxyInput = proxyInput,
              proxyOutput = proxyOutput,
              responder = responder,
              client = client,
              destinationAddress = destinationAddress,
              destinationPort = destinationPort,
              addressType = addressType,
              timeout = timeout,
              onError = onError,
              onReport = onReport,
          )
        }

        SOCKSCommand.BIND -> {
          bind(
              scope = scope,
              socketCreator = socketCreator,
              socketTracker = socketTracker,
              serverDispatcher = serverDispatcher,
              connectionInfo = connectionInfo,
              proxyInput = proxyInput,
              proxyOutput = proxyOutput,
              responder = responder,
              client = client,
              destinationAddress = destinationAddress,
              addressType = addressType,
              onError = onError,
              onReport = onReport,
          )
        }

        SOCKSCommand.UDP_ASSOCIATE -> {
          udpAssociate(
              scope = scope,
              timeout = timeout,
              networkBinder = networkBinder,
              socketCreator = socketCreator,
              socketTracker = socketTracker,
              serverDispatcher = serverDispatcher,
              connectionInfo = connectionInfo,
              proxyInput = proxyInput,
              proxyOutput = proxyOutput,
              proxyConnectionInfo = proxyConnectionInfo,
              responder = responder,
              client = client,
              addressType = addressType,
              onError = onError,
              onReport = onReport,
          )
        }
      }

  protected abstract suspend fun udpAssociate(
      scope: CoroutineScope,
      timeout: ServerSocketTimeout,
      networkBinder: SocketBinder.NetworkBinder,
      socketCreator: SocketCreator,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      connectionInfo: BroadcastNetworkStatus.ConnectionInfo.Connected,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      proxyConnectionInfo: ProxyConnectionInfo,
      client: TetherClient,
      addressType: AT,
      responder: R,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  )

  internal interface SOCKSAddressType

  internal interface Responder<AT : SOCKSAddressType> : SOCKSImplementation.Responder {

    suspend fun sendRefusal()

    suspend fun sendError()

    suspend fun sendConnectSuccess(
        addressType: AT,
        remote: InetSocketAddress?,
    )

    suspend fun sendBindInitialized(
        addressType: AT,
        bound: InetSocketAddress?,
    )

    companion object {

      internal const val DEBUG_SOCKS_REPLIES = false

      /** The zero IP, we send to this IP for error commands */
      internal val INVALID_IPV6_BYTES = ByteArray(16)

      /** The zero IP, we send to this IP for error commands */
      internal val INVALID_IPV4_BYTES = ByteArray(4)

      /** Zero port sent for error commands */
      internal const val INVALID_PORT: UShort = 0U

      @CheckResult
      internal fun InetSocketAddress.getJavaInetSocketAddress(): InetAddress {
        return toJavaAddress()
            .cast<java.net.InetSocketAddress>()
            .requireNotNull { "Failed to cast to java.net.InetSocketAddress: $this" }
            .address
            .requireNotNull { "Failed to get IP address from $this" }
      }
    }
  }
}