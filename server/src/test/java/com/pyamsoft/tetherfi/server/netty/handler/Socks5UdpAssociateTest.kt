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

package com.pyamsoft.tetherfi.server.netty.handler

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.netty.TestSetup
import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import java.io.DataInputStream
import java.net.BindException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.junit.Test

class Socks5UdpAssociateTest {

  private class Association(
      val control: Socket,
      val relay: InetSocketAddress,
  ) : AutoCloseable {

    override fun close() {
      control.close()
    }
  }

  private class EchoServer(bindAddress: InetAddress) : AutoCloseable {

    private val socket = DatagramSocket(InetSocketAddress(bindAddress, 0))
    val address: InetSocketAddress by lazy {
      socket.localSocketAddress.cast<InetSocketAddress>().requireNotNull()
    }

    // Run a packet exchange in the background
    private val worker = thread {
      val buf = ByteArray(PACKET_SIZE)
      try {
        while (true) {
          val packet = DatagramPacket(buf, buf.size)
          socket.receive(packet)
          socket.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
        }
      } catch (_: SocketException) {
        // Closed
      }
    }

    override fun close() {
      socket.close()
      worker.join()
    }
  }

  @CheckResult
  private fun findLanAddress(): InetAddress? {
    return NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLinkLocalAddress }
  }

  @CheckResult
  private fun connectSocket(): Socket {
    val socket = Socket()
    try {
      socket.soTimeout = TIMEOUT_MS
      socket.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), TIMEOUT_MS)
      return socket
    } catch (e: ConnectException) {
      socket.close()
      throw e
    }
  }

  @CheckResult
  private suspend fun connectControl(): Socket {
    while (true) {
      try {
        return connectSocket()
      } catch (_: ConnectException) {
        delay(100.milliseconds)
      }
    }
  }

  @CheckResult
  private fun associate(control: Socket): Association {
    val output = control.getOutputStream()
    val input = DataInputStream(control.getInputStream())

    output.write(byteArrayOf(5, 1, 0))
    // Must read the greeting for UDP ASSOC
    val greeting = ByteArray(2).also { input.readFully(it) }
    assertContentEquals(byteArrayOf(5, 0), greeting)

    output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
    // Must read the reply for UDP ASSOC
    val reply = ByteArray(10).also { input.readFully(it) }
    assertContentEquals(byteArrayOf(5, 0, 0, 1), reply.copyOfRange(0, 4))

    val bindAddr = InetAddress.getByAddress(reply.copyOfRange(4, 8))
    val bindPort = ByteBuffer.wrap(reply, 8, 2).short.toInt() and 0xFFFF

    return Association(control = control, relay = InetSocketAddress(bindAddr, bindPort))
  }

  private suspend fun awaitRelayClosed(relay: InetSocketAddress) {
    while (true) {
      try {
        DatagramSocket(InetSocketAddress(relay.port)).close()
        return
      } catch (_: BindException) {
        delay(10.milliseconds)
      }
    }
  }

  @CheckResult
  private fun header(destination: InetSocketAddress): ByteArray {
    return ByteBuffer.allocate(10)
        .put(byteArrayOf(0, 0, 0, 1))
        .put(destination.address.address)
        .putShort(destination.port.toShort())
        .array()
  }

  @CheckResult
  private fun clientSocket(): DatagramSocket {
    return DatagramSocket(InetSocketAddress(PROXY_HOST, 0)).apply { soTimeout = TIMEOUT_MS }
  }

  private fun DatagramSocket.sendTo(relay: InetSocketAddress, bytes: ByteArray) {
    send(DatagramPacket(bytes, bytes.size, relay))
  }

  @CheckResult
  private fun DatagramSocket.receiveBytes(): ByteArray {
    val buf = ByteArray(PACKET_SIZE)
    val packet = DatagramPacket(buf, buf.size)
    receive(packet)
    return buf.copyOf(packet.length)
  }

  private fun withProxy(block: suspend () -> Unit) = runBlockingWithDelays {
    TestSetup.withNetty(
        hostName = PROXY_HOST,
        port = PROXY_PORT,
        // TODO(Peter): Do we need test dispatchers?
        dispatchers = AppDispatchers.create(),
    ) {
      block()
    }
  }

  @Test
  fun `test SOCKS5 UDP associate relays a datagram and its reply`() {
    val lan = findLanAddress()
    if (lan == null) {
      Timber.w { "Unable to find IPv4 LAN IP" }
      return
    }

    withProxy {
      EchoServer(lan).use { echo ->
        val control = connectControl()
        associate(control).use { association ->
          clientSocket().use { client ->
            val payload = "hello udp".toByteArray(Charsets.US_ASCII)
            client.sendTo(association.relay, header(echo.address) + payload)

            assertContentEquals(header(echo.address) + payload, client.receiveBytes())
          }
        }
      }
    }
  }

  @Test
  fun `test SOCKS5 UDP associate stops relaying once the control connection closes`() {
    val lan = findLanAddress()
    if (lan == null) {
      Timber.w { "Unable to find IPv4 LAN IP" }
      return
    }

    withProxy {
      EchoServer(lan).use { echo ->
        clientSocket().use { client ->
          val control = connectControl()
          val association = associate(control)
          association.close()

          awaitRelayClosed(association.relay)

          client.sendTo(association.relay, header(echo.address) + byteArrayOf(1))
          assertFailsWith<SocketTimeoutException> { client.receiveBytes() }
        }
      }
    }
  }

  @Test
  fun `test SOCKS5 UDP associate refuses loopback destinations`() = withProxy {
    EchoServer(InetAddress.getByName(PROXY_HOST)).use { echo ->
      val control = connectControl()
      associate(control).use { association ->
        clientSocket().use { client ->
          client.sendTo(association.relay, header(echo.address) + byteArrayOf(1))

          assertEquals(-1, association.control.getInputStream().read())
        }
      }
    }
  }

  companion object {
    private const val PROXY_HOST = "127.0.0.1"
    private const val PROXY_PORT = 8229
    private const val TIMEOUT_MS = 2000
    private const val PACKET_SIZE = 1500
  }
}
