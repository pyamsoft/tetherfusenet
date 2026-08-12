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

import android.annotation.SuppressLint
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.pyamsoft.pydroid.core.createThreadEnforcer
import com.pyamsoft.tetherfi.server.ServerDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class FakeDataStore(
  initial: Preferences = emptyPreferences(),
  private val dataError: Throwable? = null,
) : DataStore<Preferences> {

  var current: Preferences = initial
    private set

  var updateCalls: Int = 0
    private set

  var updateError: Throwable? = null

  override val data: Flow<Preferences>
    get() {
      return dataError.let { e ->
        if (e != null) {
          flow { throw e }
        } else {
          flowOf(current)
        }
      }
    }

  override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
    ++updateCalls

    // Consume the test error once set
    updateError?.let {
      updateError = null
      throw it
    }

    return transform(current).also { current = it }
  }
}

/**
 * Must NOT use delay as we want "real waiting" instead of consuming a coroutine thread
 */
private inline fun awaitCondition(
  timeout: Duration = 2.seconds,
  interval: Duration = 10.milliseconds,
  condition: () -> Boolean,
) {
  val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
  var c = condition()
  while (System.currentTimeMillis() < deadline) {
    if (c) {
      return
    }

    Thread.sleep(interval.inWholeMilliseconds)
    c = condition()
  }

  assertTrue(c, "Condition not met within $timeout timeout")
}

class PreferencesImplTest {

  @Test
  fun `getPreference falls back to the default and persists it when the data flow throws`() =
    runTest {
      val dataStore = FakeDataStore(dataError = IllegalStateException())
      val prefs =
        PreferencesImpl(enforcer = createThreadEnforcer(debug = false), dataStore = dataStore)

      val value = prefs.listenForSsidChanges().first()

      assertEquals(ServerDefaults.WIFI_SSID, value)
      assertEquals(ServerDefaults.WIFI_SSID, dataStore.current[PreferenceKeys.SSID])
    }

  @Test
  fun `setPreference falls back to the fallback value when the primary write throws`() {
    val dataStore = FakeDataStore()
    val prefs =
      PreferencesImpl(enforcer = createThreadEnforcer(debug = false), dataStore = dataStore)

    // Trigger the lazy "old pref migration" so that it doesn't capture the test error
    @Suppress("UnusedFlow")
    @SuppressLint("CheckResult")
    prefs.listenForSsidChanges()

    awaitCondition { dataStore.updateCalls >= 1 }

    dataStore.updateError = IllegalStateException()
    prefs.setSsid("TESTING_SSID")

    awaitCondition { dataStore.current[PreferenceKeys.SSID] != null }
    assertEquals(ServerDefaults.WIFI_SSID, dataStore.current[PreferenceKeys.SSID])
  }
}
