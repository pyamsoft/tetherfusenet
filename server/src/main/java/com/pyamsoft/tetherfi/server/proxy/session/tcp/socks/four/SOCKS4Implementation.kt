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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.four

import android.annotation.SuppressLint
import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
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
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.INVALID_PORT
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.BaseSOCKSImplementation.Responder.Companion.getJavaInetSocketAddress
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.SOCKSCommand
import com.pyamsoft.tetherfi.server.proxy.session.tcp.socks.readUntilNullTerminator
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.build
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readPacket
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writePacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlinx.io.writeUShort

/** https://www.openssh.com/txt/socks4.protocol */
@Singleton
internal class SOCKS4Implementation
@Inject
internal constructor(
    @Named("app_scope") appScope: CoroutineScope,
    socketTagger: SocketTagger,
) :
    BaseSOCKSImplementation<SOCKS4AddressType, SOCKS4Implementation.Responder>(
        appScope = appScope,
        socketTagger = socketTagger,
    ) {

  @SuppressLint("CheckResult")
  private suspend fun ignoreUserId(proxyInput: ByteReadChannel) {
    proxyInput.readUntilNullTerminator()
  }

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
      addressType: SOCKS4AddressType,
      responder: Responder,
      onError: suspend (Throwable) -> Unit,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) {
    Timber.w { "SOCKS4 implementation does not support UDP_ASSOCIATE" }
    usingResponder(proxyOutput) { sendRefusal() }
  }

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

        // We've already consumed the first byte of this and determined it was a SOCKS4 request

        // First byte command
        val commandByte = proxyInput.readByte()
        val command = SOCKSCommand.fromByte(commandByte)
        if (command == null) {
          Timber.w {
            "Invalid command byte: $commandByte, expected CONNECT, BIND, or UDP_ASSOCIATE"
          }
          usingResponder(proxyOutput) { sendRefusal() }
          return@withContext
        }

        // A short max is 32767 but ports can go up to 65k
        // Sometimes the short value is negative, in that case, we
        // "fix" it by converting back to an unsigned number
        val destinationPort = proxyInput.readShort().toUShort()

        // 4 bytes IP
        val destinationPacket = proxyInput.readPacket(4)
        val destinationIPBytes = destinationPacket.readByteArray()
        val versionLessDestinationAddress =
            try {
              InetAddress.getByAddress(destinationIPBytes)
            } catch (e: UnknownHostException) {
              Timber.e(e) { "Unable to parse IPv4 address $destinationIPBytes" }
              usingResponder(proxyOutput) { sendError() }
              return@withContext
            }

        // If this is null, its not an IPv4 address
        val ipv4DestinationAddress = versionLessDestinationAddress.cast<Inet4Address>()
        if (ipv4DestinationAddress == null) {
          Timber.w { "Destination address is not an IPv4 address: $versionLessDestinationAddress" }
          usingResponder(proxyOutput) { sendError() }
          return@withContext
        }

        // Ignore the user ID, we don't implement this
        // And strip off the final NULL byte
        ignoreUserId(proxyInput)

        // If this is a SOCKS4 address (3 zero bytes, read AGAIN and this up until a null byte is
        // the hostname
        val finalDestinationAddress: InetAddress
        if (ipv4DestinationAddress.isSOCKS4A()) {
          val hostname = proxyInput.readUntilNullTerminator()
          val versionLessHostname =
              try {
                InetAddress.getByName(hostname)
              } catch (e: UnknownHostException) {
                Timber.e(e) { "Unable to parse SOCKS4A IPv4 hostname $hostname" }
                usingResponder(proxyOutput) { sendError() }
                return@withContext
              }
          finalDestinationAddress = versionLessHostname
        } else {
          finalDestinationAddress = ipv4DestinationAddress
        }

        val responder = Responder(proxyOutput)
        return@withContext performSOCKSCommand(
            scope = scope,
            socketCreator = socketCreator,
            timeout = timeout,
            addressType = SOCKS4AddressType,
            socketTracker = socketTracker,
            networkBinder = networkBinder,
            serverDispatcher = serverDispatcher,
            proxyInput = proxyInput,
            proxyOutput = proxyOutput,
            proxyConnectionInfo = proxyConnectionInfo,
            responder = responder,
            client = client,
            destinationAddress = finalDestinationAddress,
            destinationPort = destinationPort,
            command = command,
            connectionInfo = connectionInfo,
            onError = onError,
            onReport = onReport,
        )
      }

  @JvmInline
  internal value class Responder
  internal constructor(
      private val proxyOutput: ByteWriteChannel,
  ) : BaseSOCKSImplementation.Responder<SOCKS4AddressType> {

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
          "SOCKS4 REPLY: VERSION=${peek.readByte()} REPLY=${peek.readByte()} PORT=${peek.readShort()} ADDR=${
                        InetAddress.getByAddress(
                            peek.readByteArray()
                        ).hostName
                    }"
        }
      }

      proxyOutput.apply {
        writePacket(packet)
        flush()
      }
    }

    private suspend fun sendPacket(replyCode: Byte, port: UShort, address: ByteArray) {
      sendPacket {
        // CD
        writeByte(replyCode)

        // DSTPORT
        writeUShort(port)

        // DSTIP
        writeFully(address)
      }
    }

    override suspend fun sendConnectSuccess(
        addressType: SOCKS4AddressType,
        remote: InetSocketAddress?,
    ) {
      return sendPacket(
          replyCode = SUCCESS_CODE,
          address = getDestinationAddress(remote),
          port = getDestinationPort(remote),
      )
    }

    override suspend fun sendBindInitialized(
        addressType: SOCKS4AddressType,
        bound: InetSocketAddress?,
    ) {
      return sendPacket(
          replyCode = SUCCESS_CODE,
          address = getDestinationAddress(bound),
          port = getDestinationPort(bound),
      )
    }

    override suspend fun sendRefusal() {
      return sendPacket(
          replyCode = ERROR_CODE,
          address = INVALID_IPV4_BYTES,
          port = INVALID_PORT,
      )
    }

    override suspend fun sendError() {
      // SOCKS4 does not differentiate between "something went wrong" and "no"
      return sendRefusal()
    }

    companion object {

      // Granted
      private const val SUCCESS_CODE: Byte = 90

      // SOCKS4 does not differentiate between "something went wrong" and "no"
      private const val ERROR_CODE: Byte = 91

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
      internal fun getDestinationAddress(address: InetSocketAddress?): ByteArray {
        return address?.getJavaInetSocketAddress()?.address ?: INVALID_IPV4_BYTES
      }
    }
  }

  companion object {

    private const val SOCKS_VERSION_BYTE: Byte = 0
  }
}