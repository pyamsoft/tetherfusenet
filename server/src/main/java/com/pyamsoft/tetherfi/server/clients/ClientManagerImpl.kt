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

package com.pyamsoft.tetherfi.server.clients

import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.LintIgnoreTooManyFunctions
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.AppDispatchers
import com.pyamsoft.tetherfi.core.InAppRatingPreferences
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.TweakPreferences
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Singleton
internal class ClientManagerImpl
@Inject
internal constructor(
    private val inAppRatingPreferences: InAppRatingPreferences,
    private val clock: Clock,
    private val shutdownBus: EventBus<ServerShutdownEvent>,
    private val tweakPreferences: TweakPreferences,
    private val dispatchers: AppDispatchers,
) :
    BlockedClientTracker,
    BlockedClients,
    AllowedClients,
    ClientEraser,
    StartedClients,
    ClientResolver,
    ClientEditor {

  private val blockedClients = MutableStateFlow<Map<String, TetherClient>>(emptyMap())
  private val allowedClients = MutableStateFlow<Map<String, TetherClient>>(emptyMap())

  private val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

  @CheckResult
  private fun markLastSeenNow(client: TetherClient): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(mostRecentlySeen = LocalDateTime.now(clock))
        is HostNameClient -> client.copy(mostRecentlySeen = LocalDateTime.now(clock))
      }

  @CheckResult
  private fun editNickName(client: TetherClient, nickName: String): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(nickName = nickName)
        is HostNameClient -> client.copy(nickName = nickName)
      }

  @CheckResult
  private fun editTransferLimit(client: TetherClient, limit: TransferAmount?): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(transferLimit = limit)
        is HostNameClient -> client.copy(transferLimit = limit)
      }

  @CheckResult
  private fun editBandwidthLimit(client: TetherClient, limit: TransferAmount?): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(bandwidthLimit = limit)
        is HostNameClient -> client.copy(bandwidthLimit = limit)
      }

  @CheckResult
  private fun updateTransferReport(client: TetherClient, report: ByteTransferReport): TetherClient =
      when (client) {
        is IpAddressClient -> client.copy(totalBytes = client.mergeReport(report))
        is HostNameClient -> client.copy(totalBytes = client.mergeReport(report))
      }

  private fun onNewClientSeen(client: TetherClient) {
    Timber.d { "First time seeing client: $client" }
    inAppRatingPreferences.markDeviceConnected()
  }

  @VisibleForTesting
  internal fun purgeOldClients(cutoffTime: LocalDateTime) {
    Timber.d { "Attempt to purge old clients before $cutoffTime" }

    // "Live" client must have activity within 2 minutes
    val newClients = allowedClients.updateAndGet { list ->
      list.filter { entry ->
        val client = entry.value
        val newEnough = client.mostRecentlySeen >= cutoffTime
        if (!newEnough) {
          Timber.d { "Client is too old: $client. Last seen ${client.mostRecentlySeen}" }
        }
        return@filter newEnough
      }
    }
    blockedClients.update { blocked ->
      blocked.filter { entry ->
        // If this blocked client is still found in the "new client" list, keep it,
        // otherwise filter it out
        val stillAlive = newClients.get(entry.key)
        return@filter stillAlive != null
      }
    }

    // Don't call shutdownWithNoClients here, we want to call it only on its own schedule
  }

  private suspend fun shutdownWithNoClients() {
    if (isShutdownWithNoClientsEnabled()) {
      if (allowedClients.value.isEmpty()) {
        Timber.d { "No clients are connected. Shutdown Proxy!" }
        shutdownBus.emit(ServerShutdownEvent(throwable = null))
      }
    }
  }

  private fun CoroutineScope.watchForOldClients() {
    val scope = this

    jobs.add(
      scope.launch(context = dispatchers.default) {
          Timber.d { "Watch client count and purge old clients" }
          onTimerElapsed(OLD_CLIENT_TIMER_PERIOD) { purgeOldClients(it) }
        }
    )
  }

  @CheckResult
  private suspend fun isShutdownWithNoClientsEnabled(): Boolean {
    return tweakPreferences.listenForShutdownWithNoClients().first()
  }

  private suspend fun CoroutineScope.watchForNoClients() {
    val scope = this

    // Start new if needed
    if (isShutdownWithNoClientsEnabled()) {
      // Remember when we started watching
      //
      // We do this weird check because in development I noticed when starting the server
      // once a random ShutdownWithNoClients was published during startup, which caused
      // the app to break since Broadcast started but Proxy never started
      val startedAt = LocalDateTime.now(clock)

      jobs.add(
        scope.launch(context = dispatchers.default) {
            Timber.d { "Watch client count and shutdown if none" }
            onTimerElapsed(NO_CLIENTS_TIMER_PERIOD) { cutoff ->
              if (startedAt >= cutoff) {
                Timber.w { "Shutdown check received but client started AFTER cutoff - invalid" }
              } else {
                shutdownWithNoClients()
              }
            }
          },
      )
    }
  }

  @CheckResult
  private fun isBlockedClient(client: TetherClient): Boolean {
    return blockedClients.value.contains(client.key())
  }

  private suspend inline fun onTimerElapsed(period: Duration, block: (LocalDateTime) -> Unit) {
    try {
      while (true) {
        delay(period)
        val cutoffTime = LocalDateTime.now(clock).minusMinutes(period.inWholeMinutes)

        Timber.d { "Timer elapsed. Cutoff: $cutoffTime" }
        block(cutoffTime)
      }
    } finally {
      Timber.d { "Timer completed." }
    }
  }

  @CheckResult
  private fun checkBlocked(client: TetherClient): Boolean {
    // Check Blocklist
    if (isBlockedClient(client)) {
      Timber.w { "Hard blocked client: $client" }
      return true
    }

    // Check we are not over the transfer limit
    if (client.isOverTransferLimit()) {
      Timber.w { "Transfer limited client: $client" }
      return true
    }

    return false
  }

  @CheckResult
  private fun Map<String, TetherClient>.addClient(
      key: String,
      client: TetherClient,
  ): Map<String, TetherClient> {
    return this.toMutableMap().apply { put(key, client) }
  }

  @CheckResult
  private fun Map<String, TetherClient>.removeClient(key: String): Map<String, TetherClient> {
    return this.toMutableMap().apply { remove(key) }
  }

  private fun handleClientUpdate(
      client: TetherClient,
      onClientUpdated: (TetherClient) -> TetherClient,
  ) {
    val key = client.key()
    allowedClients.update { clients ->
      val existing = clients[key]
      if (existing != null) {
        return@update clients.addClient(key, onClientUpdated(existing))
      } else {
        return@update clients
      }
    }
  }

  override suspend fun started() =
    withContext(context = dispatchers.default) {
        watchForOldClients()
        watchForNoClients()
      }

  override fun block(client: TetherClient) {
    blockedClients.update { blocked ->
      val key = client.key()
      val existing = blocked[key]

      if (existing == null) {
        return@update blocked.addClient(key, client)
      } else {
        return@update blocked
      }
    }
  }

  override fun unblock(client: TetherClient) {
    blockedClients.update { it.removeClient(client.key()) }
  }

  override fun isBlocked(client: TetherClient): Boolean {
    return checkBlocked(client)
  }

  override fun seen(client: TetherClient) {
    handleClientUpdate(client) { markLastSeenNow(it) }
  }

  override fun reportTransfer(
      client: TetherClient,
      report: ByteTransferReport,
  ) {
    handleClientUpdate(client) { updateTransferReport(it, report) }
  }

  override suspend fun updateNickName(client: TetherClient, nickName: String) =
    withContext(context = dispatchers.default) {
        handleClientUpdate(client) { editNickName(it, nickName) }
      }

  override suspend fun updateTransferLimit(client: TetherClient, limit: TransferAmount?) =
    withContext(context = dispatchers.default) {
        handleClientUpdate(client) { editTransferLimit(it, limit) }
      }

  override suspend fun updateBandwidthLimit(client: TetherClient, limit: TransferAmount?) =
    withContext(context = dispatchers.default) {
        handleClientUpdate(client) { editBandwidthLimit(it, limit) }
      }

  override fun clear() {
    Timber.d { "Clear client tracker" }

    allowedClients.value = emptyMap()
    blockedClients.value = emptyMap()
    jobs.forEach { it.cancel() }
    jobs.clear()
  }

  override fun listenForClients(): Flow<Collection<TetherClient>> {
    return allowedClients.map { it.values }
  }

  override fun listenForBlocked(): Flow<Collection<TetherClient>> {
    return blockedClients.map { it.values }
  }

  override fun ensure(hostNameOrIp: String): TetherClient {
    return allowedClients
        .updateAndGet { clients ->
          // Don't allow duplicates in the client list
          // ConnectionScreen crashes on initial state switch from empty to >0 clients
          // because a Key is double-added briefly
          if (clients.containsKey(hostNameOrIp)) {
            return@updateAndGet clients
          }

          val client =
              TetherClient.create(
                  hostNameOrIp = hostNameOrIp,
                  clock = clock,
              )

          return@updateAndGet clients.addClient(hostNameOrIp, client).also {
            onNewClientSeen(client)
          }
        }
        .getValue(hostNameOrIp)
        .requireNotNull { "Unable to ensure TetherClient exists: $hostNameOrIp" }
  }

  companion object {
    /** Purge old or inactive clients after 5 minutes of no activity */
    private val OLD_CLIENT_TIMER_PERIOD = 5.minutes

    /** Shut down the hotspot after 10 minutes of absolutely zero client activity */
    private val NO_CLIENTS_TIMER_PERIOD = 10.minutes
  }
}
