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

package com.pyamsoft.tetherfi.server.proxy.session.tcp.http

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.core.Timber
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.net.URI
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

class NettyHttpProxy(private val host: String, private val port: Int) {

  private val bossGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
  private val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

  suspend fun start() {
    val bootstrap =
      ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel::class.java)
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .childHandler(
          object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
              val pipeline = ch.pipeline()

              // We want our pipeline to deal with HTTP
              pipeline.addLast(HttpServerCodec())

              // And bind our proxy relay handler
              pipeline.addLast(Connect())
            }
          }
        )

    val serverChannel = bootstrap.bind(host, port).channel()
    try {
      awaitCancellation()
    } finally {
      withContext(context = NonCancellable) {
        serverChannel.close()
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
      }
    }
  }
}

class Connect : ChannelInboundHandlerAdapter() {

  private var outboundChannel: Channel? = null

  private data class HostAndPort(val host: String, val port: Int)

  @CheckResult
  private fun parseHostAndPort(uri: String, defaultPort: Int = 80): HostAndPort {
    val hostPort = uri.split(":")
    val hostname = hostPort[0]
    val port = hostPort.getOrNull(1)?.toIntOrNull() ?: defaultPort
    return HostAndPort(hostname, port)
  }

  private fun assignOutboundChannel(channel: Channel) {
    outboundChannel?.let { old ->
      Timber.d { "Re-assigning outbound channel $old -> $channel" }
      if (old.isActive) {
        old.close()
      }
    }

    outboundChannel = channel
  }

  private fun sendErrorAndClose(ctx: ChannelHandlerContext) {
    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, Unpooled.EMPTY_BUFFER)
    ctx.writeAndFlush(response).addListener { closeChannels(ctx) }
  }

  private fun closeChannels(ctx: ChannelHandlerContext) {
    outboundChannel?.close()
    ctx.close()
  }

  override fun channelRegistered(ctx: ChannelHandlerContext) {
    ctx.pipeline().addFirst("idle", IdleStateHandler(60, 60, 60))
  }

  override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
    if (evt is IdleStateEvent) {
      Timber.d { "Closing idle connection: $ctx $evt" }
      closeChannels(ctx)
    }
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      outboundChannel?.config()?.isAutoRead = ctx.channel().isWritable
    } finally {
      ctx.fireChannelWritabilityChanged()
    }
  }

  override fun channelInactive(ctx: ChannelHandlerContext) {
    Timber.d { "Close inactive outbound channels: $ctx" }
    closeChannels(ctx)
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      Timber.e(cause) { "ProxyServer exception caught $ctx" }
    } finally {
      closeChannels(ctx)
    }
  }

  private fun handleHttpsConnect(ctx: ChannelHandlerContext, msg: HttpRequest) {

    val proxyServerChannel = ctx.channel()
    val parsed = parseHostAndPort(msg.uri())
    val outbound =
      Bootstrap()
        .group(proxyServerChannel.eventLoop())
        .channelFactory(NetworkBoundChannelFactory())
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .handler(
          object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
              // Once our connection to the internet is made, relay data in tunnel
              ch.pipeline().addLast(RelayHandler(proxyServerChannel))
            }
          }
        )

    outbound.connect(parsed.host, parsed.port).addListener { future ->
      if (!future.isSuccess) {
        sendErrorAndClose(ctx)
        return@addListener
      }

      val outbound = future.cast<ChannelFuture>()?.channel()
      if (outbound == null) {
        sendErrorAndClose(ctx)
        return@addListener
      }

      assignOutboundChannel(outbound)

      // Tell proxy we've established connection
      val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
      ctx.writeAndFlush(response)

      // Drop down to raw TCP
      val pipeline = ctx.pipeline()
      // Remove the http server codec
      pipeline.remove(HttpServerCodec::class.java)

      // Remove our own handler
      pipeline.remove(this)

      // Add a relay for the internet outbound
      pipeline.addLast(RelayHandler(outbound))
    }
  }

  private fun handleHttpForward(ctx: ChannelHandlerContext, msg: HttpRequest) {
    val uri = URI(msg.uri())
    val port = if (uri.port <= 0) 80 else uri.port

    val proxyChannel = ctx.channel()
    val bootstrap =
      Bootstrap()
        .group(proxyChannel.eventLoop())
        .channelFactory(NetworkBoundChannelFactory())
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .handler(
          object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
              val pipeline = ch.pipeline()
              pipeline.addLast(HttpClientCodec())
              pipeline.addLast(RelayHandler(proxyChannel))
            }
          }
        )

    bootstrap.connect(uri.host, port).addListener { future ->
      if (!future.isSuccess) {
        sendErrorAndClose(ctx)
        return@addListener
      }

      val outbound = future.cast<ChannelFuture>()?.channel()
      if (outbound == null) {
        sendErrorAndClose(ctx)
        return@addListener
      }

      assignOutboundChannel(outbound)

      // Adjust the URL to be relative to the new host
      var newUri = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
      newUri = newUri.ifBlank { "/" }
      msg.uri = newUri
      outbound.writeAndFlush(msg)
    }
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (msg is HttpRequest) {
      if (msg.method() == HttpMethod.CONNECT) {
        handleHttpsConnect(ctx, msg)
      } else {
        handleHttpForward(ctx, msg)
      }
    } else {
      Timber.w { "MSG was not http content: $msg" }
      sendErrorAndClose(ctx)
    }
  }
}

class NetworkBoundChannelFactory : ChannelFactory<NioSocketChannel> {

  override fun newChannel(): NioSocketChannel {
    val outboundSocketChannel = java.nio.channels.SocketChannel.open().apply { configureBlocking(false) }

    // TODO Bind socket to network

    return NioSocketChannel(outboundSocketChannel)
  }
}

class RelayHandler(private val relayChannel: Channel) : ChannelInboundHandlerAdapter() {

  private fun closeChannels(ctx: ChannelHandlerContext) {
    if (relayChannel.isActive) {
      relayChannel.close()
    }

    ctx.close()
  }

  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    if (!relayChannel.isActive) {
      return
    }

    if (msg is ByteBuf) {
      val byteCount = msg.readableBytes().toLong()
      // TODO Record amount consumed
      Timber.d { "Read $byteCount bytes" }
    }

    relayChannel.writeAndFlush(msg)
  }

  override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
    try {
      relayChannel.config().isAutoRead = ctx.channel().isWritable
    } finally {
      ctx.fireChannelWritabilityChanged()
    }
  }

  override fun channelInactive(ctx: ChannelHandlerContext) {
    Timber.d { "Close inactive relay channel: $ctx" }
    closeChannels(ctx)
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    Timber.e(cause) { "RelayChannel exception caught $ctx" }
    closeChannels(ctx)
  }
}