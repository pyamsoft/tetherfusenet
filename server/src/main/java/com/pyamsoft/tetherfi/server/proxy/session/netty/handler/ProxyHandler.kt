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
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

internal abstract class ProxyHandler internal constructor() : ChannelInboundHandlerAdapter() {

  protected var channelId = "CHANNEL-UNKNOWN"
    private set

  protected fun setChannelId(id: String) {
    channelId = id
  }

  protected fun closeChannels(ctx: ChannelHandlerContext) {
    onCloseChannels(ctx)

    val channel = ctx.channel()
    if (channel.isOpen) {
      Timber.d { "close owner channel $channel" }
      flushAndClose(channel)
    }
  }

  protected open fun onCloseChannels(ctx: ChannelHandlerContext) {}

  protected abstract fun sendErrorAndClose(ctx: ChannelHandlerContext, msg: Any)
}
