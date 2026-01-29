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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.http

import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.session.tcp.AbstractTcpSessionTransport
import com.pyamsoft.tetherfi.server.proxy.session.tcp.TransportWriteCommand
import com.pyamsoft.tetherfi.server.proxy.session.tcp.relayData
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeFully
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

internal class HttpTransport
@Inject
internal constructor(
    private val requestParser: RequestParser,
    private val enforcer: ThreadEnforcer,
) : AbstractTcpSessionTransport<HttpProxyRequest>() {

  override val proxyType = SharedProxy.Type.HTTP

  /**
   * HTTPS Connections are encrypted and so we cannot see anything further past the initial CONNECT
   * call.
   *
   * Establish the connection to a site and then continue processing the connection data
   */
  private suspend fun establishHttpsConnection(
      input: ByteReadChannel,
      output: ByteWriteChannel,
      request: HttpProxyRequest,
  ) {
    // We exhaust the input here because the client is sending CONNECT data to what it thinks is a
    // server but its actually us, and we don't care how they connect
    //
    // we assume the connect will work and then tell the client so they can start sending the
    // actual data
    var throwaway: String?
    do {
      throwaway = input.readLineStrict()
    } while (!throwaway.isNullOrBlank())

    debugLog { "Establish HTTPS CONNECT tunnel ${request.raw}" }
    writeHttpConnectSuccess(output)
  }

  /**
   * Send the first communication request
   *
   * This was not an HTTPS CONNECT request, so we just pass it along to our HTTP client
   */
  private suspend fun replayHttpCommunication(
      output: ByteWriteChannel,
      request: HttpProxyRequest,
  ) {
    // TODO(Peter): If the output socket is already closed this will fail and throw.
    //   realistically, will the output socket ever be closed for the initial connection?
    debugLog { "Rewrote initial HTTP request: ${request.raw} -> ${request.httpRequest}" }
    output.writeFully(writeHttpMessageAndAwaitMore(request.httpRequest))
  }

  override suspend fun writeProxyOutput(
      output: ByteWriteChannel,
      request: HttpProxyRequest,
      command: TransportWriteCommand,
  ) =
      when (command) {
        TransportWriteCommand.INVALID -> writeProxyHttpError(output)
        TransportWriteCommand.ERROR -> writeProxyHttpError(output)
        TransportWriteCommand.BLOCK -> writeHttpClientBlocked(output)
      }

  /**
   * Parse the first line of content which may be a connect call
   *
   * We must do this unbuffered because we only want the first line to determine which host and what
   * port we are connecting on
   *
   * If we buffer we may end up reading the whole input which can be huge, and OOM us.
   */
  override suspend fun parseRequest(
      input: ByteReadChannel,
      output: ByteWriteChannel,
  ): HttpProxyRequest {
    val line = input.readLineStrict()

    // No line, no go
    if (line.isNullOrBlank()) {
      warnLog { "No input read from proxy" }
      return INVALID_REQUEST
    }

    // Given the line, it needs to be in an expected format or we can't do it
    return requestParser.parse(line)
  }

  suspend fun exchangeInternet(
      scope: CoroutineScope,
      serverDispatcher: ServerDispatcher,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      internetInput: ByteReadChannel,
      internetOutput: ByteWriteChannel,
      request: HttpProxyRequest,
      client: TetherClient,
      onReport: suspend (ByteTransferReport) -> Unit,
  ) {
    enforcer.assertOffMainThread()

    try {
      if (request.isHttpsConnectRequest()) {
        // Establish an HTTPS connection by faking the CONNECT response
        // Send a 200 to the connecting client so that they will then continue to
        // send the actual HTTP data to the real endpoint
        establishHttpsConnection(
            input = proxyInput,
            output = proxyOutput,
            request = request,
        )
      } else {
        // Send initial HTTP communication, since we consumed it above
        replayHttpCommunication(
            output = internetOutput,
            request = request,
        )
      }

      relayData(
          scope = scope,
          client = client,
          serverDispatcher = serverDispatcher,
          internetInput = internetInput,
          internetOutput = internetOutput,
          proxyInput = proxyInput,
          proxyOutput = proxyOutput,
          onReport = onReport,
      )
    } catch (e: Throwable) {
      e.ifNotCancellation {
        if (e is SocketTimeoutException) {
          warnLog { "Proxy:Internet socket timeout! $request $client" }
        } else {
          errorLog(e) { "Error occurred during internet exchange: $request $client" }
          writeProxyOutput(proxyOutput, request, TransportWriteCommand.ERROR)
        }
      }
    }
  }

  companion object {

    private val INVALID_REQUEST =
        HttpProxyRequest(
            file = "",
            port = 0,
            method = "",
            version = "",
            host = "",
            raw = "",
            valid = false,
        )
  }
}
