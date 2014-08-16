/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.netty.protocol.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

import net.kuujo.copycat.protocol.AppendEntriesRequest;
import net.kuujo.copycat.protocol.AppendEntriesResponse;
import net.kuujo.copycat.protocol.ProtocolHandler;
import net.kuujo.copycat.protocol.ProtocolServer;
import net.kuujo.copycat.protocol.Request;
import net.kuujo.copycat.protocol.RequestVoteRequest;
import net.kuujo.copycat.protocol.RequestVoteResponse;
import net.kuujo.copycat.protocol.SubmitCommandRequest;
import net.kuujo.copycat.protocol.SubmitCommandResponse;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * Netty TCP protocol server.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class TcpProtocolServer implements ProtocolServer {
  private final TcpProtocol protocol;
  private ProtocolHandler handler;
  private Channel channel;

  public TcpProtocolServer(TcpProtocol protocol) {
    this.protocol = protocol;
  }

  @Override
  public void protocolHandler(ProtocolHandler handler) {
    this.handler = handler;
  }

  @Override
  public ListenableFuture<Void> start() {
    // TODO: Configure proper SSL trust store.
    final SslContext sslContext;
    if (protocol.isSsl()) {
      try {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        sslContext = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
      } catch (SSLException | CertificateException e) {
        return Futures.immediateFailedFuture(e);
      }
    } else {
      sslContext = null;
    }

    final EventLoopGroup serverGroup = new NioEventLoopGroup();
    final EventLoopGroup workerGroup = new NioEventLoopGroup(protocol.getThreads());

    final ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(serverGroup, workerGroup)
    .channel(NioServerSocketChannel.class)
    .childHandler(new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(SocketChannel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        if (sslContext != null) {
          pipeline.addLast(sslContext.newHandler(channel.alloc()));
        }
        pipeline.addLast(
            new ObjectEncoder(),
            new ObjectDecoder(ClassResolvers.softCachingConcurrentResolver(getClass().getClassLoader())),
            new TcpProtocolServerHandler(TcpProtocolServer.this)
        );
      }
    })
    .option(ChannelOption.SO_BACKLOG, 128);

    if (protocol.getSendBufferSize() > -1) {
      bootstrap.option(ChannelOption.SO_SNDBUF, protocol.getSendBufferSize());
    }

    if (protocol.getReceiveBufferSize() > -1) {
      bootstrap.option(ChannelOption.SO_RCVBUF, protocol.getReceiveBufferSize());
    }

    bootstrap.option(ChannelOption.TCP_NODELAY, protocol.isNoDelay());
    bootstrap.option(ChannelOption.SO_REUSEADDR, protocol.isReuseAddress());
    bootstrap.option(ChannelOption.SO_KEEPALIVE, protocol.isKeepAlive());
    bootstrap.option(ChannelOption.SO_BACKLOG, protocol.getAcceptBacklog());

    if (protocol.getTrafficClass() > -1) {
      bootstrap.option(ChannelOption.IP_TOS, protocol.getTrafficClass());
    }

    bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

    // Bind and start to accept incoming connections.
    final SettableFuture<Void> future = SettableFuture.create();
    bootstrap.bind(protocol.getHost(), protocol.getPort()).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture channelFuture) throws Exception {
        channelFuture.channel().closeFuture().addListener(new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            workerGroup.shutdownGracefully();
          }
        });

        if (channelFuture.isSuccess()) {
          channel = channelFuture.channel();
          future.set(null);
        } else if (channelFuture.cause() != null) {
          future.setException(channelFuture.cause());
        }
      }
    });
    return future;
  }

  @Override
  public ListenableFuture<Void> stop() {
    if (channel != null) {
      final SettableFuture<Void> future = SettableFuture.create();
      channel.close().addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture channelFuture) throws Exception {
          if (channelFuture.isSuccess()) {
            future.set(null);
          } else if (channelFuture.cause() != null) {
            future.setException(channelFuture.cause());
          }
        }
      });
      return future;
    } else {
      return Futures.immediateFuture(null);
    }
  }

  /**
   * Server request handler.
   */
  private static class TcpProtocolServerHandler extends ChannelInboundHandlerAdapter {
    private final TcpProtocolServer server;

    private TcpProtocolServerHandler(TcpProtocolServer server) {
      this.server = server;
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, Object message) {
      final Request request = (Request) message;
      if (request instanceof AppendEntriesRequest) {
        context.channel().eventLoop().submit(new Runnable() {
          @Override
          public void run() {
            Futures.addCallback(server.handler.appendEntries((AppendEntriesRequest) request), new FutureCallback<AppendEntriesResponse>() {
              @Override
              public void onFailure(Throwable t) {
                
              }
              @Override
              public void onSuccess(AppendEntriesResponse response) {
                context.writeAndFlush(response);
              }
            });
          }
        });
      } else if (request instanceof RequestVoteRequest) {
        context.channel().eventLoop().submit(new Runnable() {
          @Override
          public void run() {
            Futures.addCallback(server.handler.requestVote((RequestVoteRequest) request), new FutureCallback<RequestVoteResponse>() {
              @Override
              public void onFailure(Throwable t) {
                
              }
              @Override
              public void onSuccess(RequestVoteResponse response) {
                context.writeAndFlush(response);
              }
            });
          }
        });
      } else if (request instanceof SubmitCommandRequest) {
        context.channel().eventLoop().submit(new Runnable() {
          @Override
          public void run() {
            Futures.addCallback(server.handler.submitCommand((SubmitCommandRequest) request), new FutureCallback<SubmitCommandResponse>() {
              @Override
              public void onFailure(Throwable t) {
                
              }
              @Override
              public void onSuccess(SubmitCommandResponse response) {
                context.writeAndFlush(response);
              }
            });
          }
        });
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      context.close();
    }

  }

}
