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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.five

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.core.cast
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
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.DEBUG_SOCKS_REPLIES
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.INVALID_IPV4_BYTES
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.INVALID_IPV6_BYTES
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.INVALID_PORT
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.getJavaInetSocketAddress
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.SOCKSCommand
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.five.AddressResolver.resolvePacketDestination
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.five.SOCKS5Implementation.AcceptedAuthenticationMethods.ERROR_NO_ACCEPTABLE_AUTH_METHODS
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.build
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writePacket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlinx.io.writeUShort

/** https://www.rfc-editor.org/rfc/rfc1928 */
@Singleton
internal class SOCKS5Implementation
@Inject
internal constructor(
    @Named("app_scope") appScope: CoroutineScope,
    private val enforcer: ThreadEnforcer,
    socketTagger: SocketTagger,
) :
    BaseSOCKSImplementation<SOCKS5AddressType, SOCKS5Implementation.Responder>(
        appScope = appScope,
        socketTagger = socketTagger,
    ) {

  override suspend fun usingResponder(
      proxyOutput: ByteWriteChannel,
      block: suspend Responder.() -> Unit,
  ) {
    Responder(proxyOutput).also { block(it) }
  }

  override suspend fun udpAssociate(
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
      addressType: SOCKS5AddressType,
      responder: Responder,
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
                      .udp()
                      .configure {
                        reuseAddress = true
                        // As of KTOR-3.0.0, this is not supported and crashes at runtime
                        // reusePort = true
                      }
                      .also { socketTagger.tagSocket() }
                      .let { b ->
                        b.bind(
                            localAddress =
                                InetSocketAddress(
                                    hostname = connectionInfo.hostName,
                                    port = 0,
                                ),
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
                } catch (e: Throwable) {
                  if (e is TimeoutCancellationException) {
                    Timber.w { "Timeout while waiting for socket udp_assoc()" }
                    responder.sendRefusal()

                    // Rethrow a cancellation exception
                    throw e
                  } else {
                    e.ifNotCancellation {
                      Timber.e(e) { "Error during socket udp_assoc()" }
                      responder.sendError()
                      return@create
                    }
                  }
                }

            bound.use { serverSocket ->
              val udpServer =
                  UDPRelayServer(
                      appScope = appScope,
                      enforcer = enforcer,
                      socketTagger = socketTagger,
                      timeout = timeout,
                      networkBinder = networkBinder,
                      socketCreator = socketCreator,
                      socketTracker = socketTracker,
                      serverDispatcher = serverDispatcher,
                      proxyConnectionInfo = proxyConnectionInfo,
                      serverSocket = serverSocket,
                      client = client,
                  )

              val relayConnection =
                  udpServer.relay(
                      scope = scope,
                      onError = onError,
                      onReport = onReport,
                  )
              try {
                // Once the bind is open, we send the initial reply telling the client
                // the IP and the port
                responder.sendBindInitialized(
                    addressType = addressType,
                    bound = serverSocket.localAddress.cast(),
                )

                // Wait for the relay to finish
                relayConnection.join()
              } finally {
                relayConnection.cancel()
                Timber.d { "UDP association complete" }
              }
            }
          },
      )

  override suspend fun handleSocksCommand(
      scope: CoroutineScope,
      socketCreator: SocketCreator,
      timeout: ServerSocketTimeout,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      networkBinder: SocketBinder.NetworkBinder,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      proxyConnectionInfo: ProxyConnectionInfo,
      connectionInfo: BroadcastNetworkStatus.ConnectionInfo.Connected,
      client: TetherClient,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) =
      withContext(context = serverDispatcher.primary) {
        val methodCount = proxyInput.readByte()
        val methods = ByteArray(methodCount.toInt()) { proxyInput.readByte() }

        // Check that we both support the "no-auth" method
        val selectedMethod = AcceptedAuthenticationMethods.METHOD_NO_AUTHENTICATION
        if (!methods.contains(selectedMethod)) {
          usingResponder(proxyOutput) { sendNoValidAuthentication() }
          return@withContext
        }

        // Send the method selection message
        usingResponder(proxyOutput) { sendAuthMethodSelection(selectedMethod) }

        val versionByte = proxyInput.readByte()
        if (versionByte != SOCKS_VERSION_BYTE) {
          Timber.w { "Invalid SOCKS version byte: $versionByte" }
          usingResponder(proxyOutput) { sendRefusal() }
          return@withContext
        }

        val commandByte = proxyInput.readByte()
        val command = SOCKSCommand.fromByte(commandByte)
        if (command == null) {
          Timber.w {
            "Invalid command byte: $commandByte, expected CONNECT, BIND, or UDP_ASSOCIATE"
          }
          usingResponder(proxyOutput) { sendCommandUnsupported() }
          return@withContext
        }

        val reserved = proxyInput.readByte()
        if (reserved != RESERVED_BYTE) {
          Timber.w { "Expected reserve byte, but got data: $reserved" }
          usingResponder(proxyOutput) { sendRefusal() }
          return@withContext
        }

        val packetDestination =
            resolvePacketDestination(
                serverDispatcher = serverDispatcher,
                SourceOrByteReadChannel.FromByteReadChannel(proxyInput),
                onInvalidAddressType = {
                  Timber.w { "Invalid address type: $it" }
                  usingResponder(proxyOutput) { sendRefusal() }
                },
                onInvalidDestinationAddress = { usingResponder(proxyOutput) { sendError(it) } },
            )

        if (packetDestination == null) {
          return@withContext
        }

        val responder = Responder(proxyOutput)
        return@withContext performSOCKSCommand(
            scope = scope,
            socketCreator = socketCreator,
            timeout = timeout,
            socketTracker = socketTracker,
            networkBinder = networkBinder,
            serverDispatcher = serverDispatcher,
            proxyInput = proxyInput,
            proxyOutput = proxyOutput,
            proxyConnectionInfo = proxyConnectionInfo,
            responder = responder,
            client = client,
            command = command,
            connectionInfo = connectionInfo,
            destinationAddress = packetDestination.address,
            destinationPort = packetDestination.port,
            addressType = packetDestination.addressType,
            onError = onError,
            onReport = onReport,
        )
      }

  @JvmInline
  internal value class Responder
  internal constructor(
      private val proxyOutput: ByteWriteChannel,
  ) : BaseSOCKSImplementation.Responder<SOCKS5AddressType> {

    private suspend inline fun sendPacket(builder: Sink.() -> Unit) {
      val packet =
          Buffer()
              .apply {
                // VN
                writeByte(SOCKS_VERSION_BYTE)

                // Builder
                builder()
              }
              .build()

      if (DEBUG_SOCKS_REPLIES) {
        Timber.d {
          val peek = packet.peek()
          val version = peek.readByte()
          val authOrReplyCode = peek.readByte()
          if (peek.exhausted()) {
            "SOCKS5 AUTH: VERSION=$version AUTH=$authOrReplyCode"
          } else {
            var all = "SOCKS5 REPLY: VERSION=$version REPLY=$authOrReplyCode RSV=${peek.readByte()}"
            val type = peek.readByte()
            all += " ADDR_TYPE=$type "
            val addr =
                when (type) {
                  1.toByte() -> {
                    InetAddress.getByAddress(peek.readByteArray(4)).hostName
                  }

                  4.toByte() -> {
                    InetAddress.getByAddress(peek.readByteArray(16)).hostName
                  }

                  else -> {
                    val addrLen = peek.readByte()
                    val bytes = peek.readByteArray(addrLen.toInt())
                    InetAddress.getByAddress(bytes).hostName
                  }
                }
            "$all ADDR=$addr PORT=${peek.readShort()}"
          }
        }
      }

      proxyOutput.apply {
        writePacket(packet)
        flush()
      }
    }

    private suspend fun sendPacket(
        addressType: SOCKS5AddressType,
        replyCode: Byte,
        port: UShort,
        address: ByteArray,
    ) {
      sendPacket {
        // CD
        writeByte(replyCode)

        // RSV
        writeByte(RESERVED_BYTE)

        // Address type
        writeByte(addressType.byte)

        // BND.ADDR
        if (addressType == SOCKS5AddressType.DOMAIN_NAME) {
          // Write a length byte to tell the client how long the domain name is
          writeByte(address.size.toByte())
          if (address.isNotEmpty()) {
            writeFully(address)
          }
        } else {
          // For non-domain address types, the type will inform the client how many bytes it should
          // expect
          writeFully(address)
        }

        // BND.PORT
        writeUShort(port)
      }
    }

    override suspend fun sendConnectSuccess(
        addressType: SOCKS5AddressType,
        remote: InetSocketAddress?,
    ) {
      return sendPacket(
          addressType = addressType,
          replyCode = SUCCESS_CODE,
          address = getDestinationAddress(addressType, remote),
          port = getDestinationPort(remote),
      )
    }

    override suspend fun sendBindInitialized(
        addressType: SOCKS5AddressType,
        bound: InetSocketAddress?,
    ) {
      return sendPacket(
          addressType = addressType,
          replyCode = SUCCESS_CODE,
          address = getDestinationAddress(addressType, bound),
          port = getDestinationPort(bound),
      )
    }

    suspend fun sendError(addressType: SOCKS5AddressType) {
      return sendPacket(
          addressType = addressType,
          replyCode = ERROR_GENERAL_SERVER_FAIL,
          address = getInvalidAddress(addressType),
          port = INVALID_PORT,
      )
    }

    override suspend fun sendError() {
      return sendError(
          // Since the generic refusal is ONLY delivered when something goes wrong
          // parsing the input, we can send back essentially whatever here, as its
          // not part of the protocol that the consumer will read the address type byte.
          addressType = SOCKS5AddressType.IPV4,
      )
    }

    private suspend fun sendRefusal(addressType: SOCKS5AddressType) {
      return sendPacket(
          addressType = addressType,
          replyCode = ERROR_CONNECTION_REFUSED,
          address = getInvalidAddress(addressType),
          port = INVALID_PORT,
      )
    }

    override suspend fun sendRefusal() {
      return sendRefusal(
          // Since the generic refusal is ONLY delivered when something goes wrong
          // parsing the input, we can send back essentially whatever here, as its
          // not part of the protocol that the consumer will read the address type byte.
          addressType = SOCKS5AddressType.IPV4,
      )
    }

    suspend fun sendCommandUnsupported() {
      // This fails so early, we don't know the typ
      val addressType = SOCKS5AddressType.IPV4
      return sendPacket(
          addressType = addressType,
          replyCode = ERROR_COMMAND_NOT_SUPPORTED,
          address = getInvalidAddress(addressType),
          port = INVALID_PORT,
      )
    }

    suspend fun sendNoValidAuthentication() {
      return sendPacket { writeByte(ERROR_NO_ACCEPTABLE_AUTH_METHODS) }
    }

    suspend fun sendAuthMethodSelection(method: Byte) {
      return sendPacket { writeByte(method) }
    }

    companion object {

      // Granted
      private const val SUCCESS_CODE: Byte = 0

      private const val ERROR_GENERAL_SERVER_FAIL: Byte = 1
      private const val ERROR_CONNECTION_REFUSED: Byte = 5
      private const val ERROR_COMMAND_NOT_SUPPORTED: Byte = 7

      private val INVALID_DOMAIN_NAME_BYTES = byteArrayOf(0)

      @JvmStatic
      @CheckResult
      internal fun getDestinationPort(address: InetSocketAddress?): UShort {
        // A short max is 32767 but ports can go up to 65k
        // Sometimes the short value is negative, in that case, we
        // "fix" it by converting back to an unsigned number
        return address?.port?.toUShort() ?: INVALID_PORT
      }

      @JvmStatic
      @CheckResult
      internal fun getInvalidAddress(addressType: SOCKS5AddressType): ByteArray =
          when (addressType) {
            SOCKS5AddressType.IPV4 -> INVALID_IPV4_BYTES
            SOCKS5AddressType.DOMAIN_NAME -> INVALID_DOMAIN_NAME_BYTES
            SOCKS5AddressType.IPV6 -> INVALID_IPV6_BYTES
          }

      @JvmStatic
      @CheckResult
      internal fun getDestinationAddress(
          addressType: SOCKS5AddressType,
          address: InetSocketAddress?,
      ): ByteArray {
        return address?.getJavaInetSocketAddress()?.address ?: getInvalidAddress(addressType)
      }
    }
  }

  /**
   * We do NOT support Password or GSSAPI, so we are NOT a compliant implementation, (and neither
   * are many of the simple projects on GitHub :) )
   *
   * https://www.rfc-editor.org/rfc/rfc1928 page:3
   */
  private object AcceptedAuthenticationMethods {
    const val METHOD_NO_AUTHENTICATION: Byte = 0

    const val ERROR_NO_ACCEPTABLE_AUTH_METHODS = 0xFF.toByte()
  }

  companion object {
    private const val SOCKS_VERSION_BYTE: Byte = 5
    private const val RESERVED_BYTE: Byte = 0
  }
}