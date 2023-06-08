/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.net.impl.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoop;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.ClientOptionsBase;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.impl.SocketAddressImpl;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

import java.net.SocketAddress;
import java.util.WeakHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadFactory;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class EpollTransport extends Transport {

  private static volatile int pendingFastOpenRequestsThreshold = 256;

  // Keeps the EpollResource alive until the EpollEventLoopGroup is GC'ed
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private final WeakHashMap<EpollEventLoopGroup, EpollResource> resources = new WeakHashMap<>();

  /**
   * Return the number of of pending TFO connections in SYN-RCVD state for TCP_FASTOPEN.
   *
   * {@see #setPendingFastOpenRequestsThreshold}
   */
  public static int getPendingFastOpenRequestsThreshold() {
    return pendingFastOpenRequestsThreshold;
  }

  /**
   * Set the number of of pending TFO connections in SYN-RCVD state for TCP_FASTOPEN
   * <p/>
   * If this value goes over a certain limit the server disables all TFO connections.
   */
  public static void setPendingFastOpenRequestsThreshold(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("Invalid " + value);
    }
    pendingFastOpenRequestsThreshold = value;
  }

  EpollTransport() {
  }

  @Override
  public SocketAddress convert(io.vertx.core.net.SocketAddress address) {
    if (address.isDomainSocket()) {
      return new DomainSocketAddress(address.path());
    } else {
      return super.convert(address);
    }
  }

  @Override
  public io.vertx.core.net.SocketAddress convert(SocketAddress address) {
    if (address instanceof DomainSocketAddress) {
      return new SocketAddressImpl(((DomainSocketAddress) address).path());
    }
    return super.convert(address);
  }

  @Override
  public boolean isAvailable() {
    return Epoll.isAvailable();
  }

  @Override
  public Throwable unavailabilityCause() {
    return Epoll.unavailabilityCause();
  }

  @Override
  public EventLoopGroup eventLoopGroup(int type, int nThreads, ThreadFactory threadFactory, int ioRatio) {
    EpollEventLoopGroup eventLoopGroup = new EpollEventLoopGroup(nThreads, threadFactory);
    eventLoopGroup.setIoRatio(ioRatio);
    synchronized (resources) {
      resources.put(eventLoopGroup, new EpollResource(eventLoopGroup));
    }
    return eventLoopGroup;
  }

  @Override
  public DatagramChannel datagramChannel() {
    return new EpollDatagramChannel();
  }

  @Override
  public DatagramChannel datagramChannel(InternetProtocolFamily family) {
    return new EpollDatagramChannel();
  }

  @Override
  public ChannelFactory<? extends Channel> channelFactory(boolean domainSocket) {
    if (domainSocket) {
      return EpollDomainSocketChannel::new;
    } else {
      return EpollSocketChannel::new;
    }
  }

  public ChannelFactory<? extends ServerChannel> serverChannelFactory(boolean domainSocket) {
    if (domainSocket) {
      return EpollServerDomainSocketChannel::new;
    }
    return EpollServerSocketChannel::new;
  }

  @Override
  public void configure(DatagramChannel channel, DatagramSocketOptions options) {
    channel.config().setOption(EpollChannelOption.SO_REUSEPORT, options.isReusePort());
    super.configure(channel, options);
  }

  @Override
  public void configure(NetServerOptions options, boolean domainSocket, ServerBootstrap bootstrap) {
    if (!domainSocket) {
      bootstrap.option(EpollChannelOption.SO_REUSEPORT, options.isReusePort());
      if (options.isTcpFastOpen()) {
        bootstrap.option(EpollChannelOption.TCP_FASTOPEN, options.isTcpFastOpen() ? pendingFastOpenRequestsThreshold : 0);
      }
      bootstrap.childOption(EpollChannelOption.TCP_QUICKACK, options.isTcpQuickAck());
      bootstrap.childOption(EpollChannelOption.TCP_CORK, options.isTcpCork());
    }
    super.configure(options, domainSocket, bootstrap);
  }

  @Override
  public void configure(ClientOptionsBase options, boolean domainSocket, Bootstrap bootstrap) {
    if (!domainSocket) {
      if (options.isTcpFastOpen()) {
        bootstrap.option(EpollChannelOption.TCP_FASTOPEN_CONNECT, options.isTcpFastOpen());
      }
      bootstrap.option(EpollChannelOption.TCP_USER_TIMEOUT, options.getTcpUserTimeout());
      bootstrap.option(EpollChannelOption.TCP_QUICKACK, options.isTcpQuickAck());
      bootstrap.option(EpollChannelOption.TCP_CORK, options.isTcpCork());
    }
    super.configure(options, domainSocket, bootstrap);
  }

  private static class EpollResource implements Resource {
    private final EpollEventLoopGroup eventLoopGroup;
    private final Phaser phaser;

    public EpollResource(EpollEventLoopGroup eventLoopGroup) {
      this.eventLoopGroup = eventLoopGroup;
      // contrary to Barrier the Phaser supports uninterruptible waiting
      this.phaser = new Phaser(eventLoopGroup.executorCount() + 1);
      Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
      for (EventExecutor executor : eventLoopGroup) {
        EpollEventLoop eventLoop = (EpollEventLoop) executor;
        executor.execute(() -> {
          eventLoop.closeFileDescriptors();
          phaser.arriveAndAwaitAdvance();
          phaser.arriveAndAwaitAdvance();
          eventLoop.openFileDescriptors();
        });
      }
      phaser.arriveAndAwaitAdvance();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
      phaser.arriveAndAwaitAdvance();
    }
  }
}
