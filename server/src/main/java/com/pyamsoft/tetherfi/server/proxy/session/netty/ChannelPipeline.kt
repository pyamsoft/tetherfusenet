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

package com.pyamsoft.tetherfi.server.proxy.session.netty

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelPipeline
import kotlin.reflect.KClass

internal fun <T : ChannelHandler> ChannelPipeline.dropHandler(c: KClass<T>) {
  val self = this
  val javaClass = c.java
  if (self.get(javaClass) != null) {
    self.remove(javaClass)
  }
}
