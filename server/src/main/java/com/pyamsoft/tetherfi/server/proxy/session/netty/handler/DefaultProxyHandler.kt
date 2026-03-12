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

package com.pyamsoft.tetherfi.server.proxy.session.netty.handler

import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.ServerSocketTimeout
import io.netty.channel.ChannelHandlerContext

internal abstract class DefaultProxyHandler
internal constructor(
    serverSocketTimeout: ServerSocketTimeout,
) :
    ProxyHandler(
        serverSocketTimeout = serverSocketTimeout,
    ) {

  final override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    try {
      ctx.handleIdleState(evt) {
        Timber.d { "(${channelId}): Close channel after idle timeout" }
        closeChannels(ctx)
      }
    } finally {
      super.userEventTriggered(ctx, evt)
    }
  }
}
