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

package com.pyamsoft.tetherfi.server.netty

import com.pyamsoft.tetherfi.server.runBlockingWithDelays
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.junit.Test

class ServerTest {

  @Test
  fun `test Netty server starts`(): Unit = runBlockingWithDelays {
    withNetty {
      // Delay for a bit to "keep it running"
      delay(1.seconds)
    }
  }
}
