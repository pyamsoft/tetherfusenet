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

package com.pyamsoft.tetherfi.info

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoViewModelerTest {

  @Test
  fun `handleTogglePasswordVisibility flips the flag`() {
    val state = MutableInfoViewState()
    val viewModeler = InfoViewModeler(state = state)

    viewModeler.handleTogglePasswordVisibility()
    assertTrue(state.isPasswordVisible.value)

    viewModeler.handleTogglePasswordVisibility()
    assertFalse(state.isPasswordVisible.value)
  }

  @Test
  fun `handleToggleOptions HTTP flips only the http flag`() {
    val state = MutableInfoViewState()
    val viewModeler = InfoViewModeler(state = state)

    viewModeler.handleToggleOptions(InfoViewOptionsType.HTTP)

    assertTrue(state.showHttpOptions.value)
    assertFalse(state.showSocksOptions.value)
  }

  @Test
  fun `handleToggleOptions SOCKS flips only the socks flag`() {
    val state = MutableInfoViewState()
    val viewModeler = InfoViewModeler(state = state)

    viewModeler.handleToggleOptions(InfoViewOptionsType.SOCKS)

    assertTrue(state.showSocksOptions.value)
    assertFalse(state.showHttpOptions.value)
  }

  @Test
  fun `both options potentially visible`() {
    val state = MutableInfoViewState()
    val viewModeler = InfoViewModeler(state = state)

    viewModeler.handleToggleOptions(InfoViewOptionsType.HTTP)
    viewModeler.handleToggleOptions(InfoViewOptionsType.SOCKS)

    assertTrue(state.showSocksOptions.value)
    assertTrue(state.showHttpOptions.value)
  }
}
