package org.developerkubilay.safra.client.p2p;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class P2pLoginTimeoutTest {
    @Test
    void extendsOnlyLoginThenRestoresOriginalTimeoutWithANewHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ReadTimeoutHandler original = new ReadTimeoutHandler(30);
            channel.pipeline().addLast("timeout", original);
            P2pLoginTimeout timeout = new P2pLoginTimeout();
            timeout.extend(channel);
            ReadTimeoutHandler extended = handler(channel);
            assertNotSame(original, extended);
            assertEquals(180_000L, extended.getReaderIdleTimeInMillis());
            timeout.extend(channel);
            assertSame(extended, handler(channel));
            timeout.finish();
            assertEquals(30_000L, handler(channel).getReaderIdleTimeInMillis());
            assertNotSame(original, handler(channel));
            assertNotSame(extended, handler(channel));
            timeout.extend(channel);
            assertEquals(30_000L, handler(channel).getReaderIdleTimeInMillis());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void leavesOrdinaryChannelsUnchanged() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ReadTimeoutHandler original = new ReadTimeoutHandler(30);
            channel.pipeline().addLast("timeout", original);
            new P2pLoginTimeout().finish();
            assertSame(original, handler(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void preservesMissingCustomAndDisabledTimeoutHandlers() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            new P2pLoginTimeout().extend(channel);
            assertNull(channel.pipeline().get("timeout"));
            ChannelInboundHandlerAdapter custom = new ChannelInboundHandlerAdapter();
            channel.pipeline().addLast("timeout", custom);
            new P2pLoginTimeout().extend(channel);
            assertSame(custom, channel.pipeline().get("timeout"));
            channel.pipeline().replace(custom, "timeout", new ReadTimeoutHandler(90));
            new P2pLoginTimeout().extend(channel);
            assertEquals(90_000L, handler(channel).getReaderIdleTimeInMillis());
            ReadTimeoutHandler subclass = new ReadTimeoutHandler(30) { };
            channel.pipeline().replace("timeout", "timeout", subclass);
            new P2pLoginTimeout().extend(channel);
            assertSame(subclass, handler(channel));
            channel.pipeline().replace("timeout", "timeout", new ReadTimeoutHandler(0));
            new P2pLoginTimeout().extend(channel);
            assertEquals(0L, handler(channel).getReaderIdleTimeInMillis());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void doesNotOverwriteAnotherModsReplacementOnFinish() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
            P2pLoginTimeout timeout = new P2pLoginTimeout();
            timeout.extend(channel);
            ReadTimeoutHandler replacement = new ReadTimeoutHandler(60);
            channel.pipeline().replace("timeout", "timeout", replacement);
            timeout.finish();
            assertSame(replacement, handler(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void skipsClosedChannelsAndLateExtensionAfterPlay() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        P2pLoginTimeout finished = new P2pLoginTimeout();
        finished.finish();
        finished.extend(channel);
        assertEquals(30_000L, handler(channel).getReaderIdleTimeInMillis());
        channel.finishAndReleaseAll();
        P2pLoginTimeout closed = new P2pLoginTimeout();
        closed.extend(channel);
        closed.finish();
        channel.checkException();
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void queuedExtensionCannotRunAfterLoginFinishes() throws Exception {
        var group = new io.netty.channel.DefaultEventLoopGroup(2);
        io.netty.channel.Channel server = null;
        io.netty.channel.Channel client = null;
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            server = new io.netty.bootstrap.ServerBootstrap().group(group)
                .channel(io.netty.channel.local.LocalServerChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter())
                .bind(new io.netty.channel.local.LocalAddress("safra-timeout-" + java.util.UUID.randomUUID()))
                .sync().channel();
            client = new io.netty.bootstrap.Bootstrap().group(group)
                .channel(io.netty.channel.local.LocalChannel.class)
                .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
                    @Override
                    protected void initChannel(io.netty.channel.Channel channel) {
                        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
                    }
                }).connect(server.localAddress()).sync().channel();
            var entered = new java.util.concurrent.CountDownLatch(1);
            client.eventLoop().execute(() -> {
                entered.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            P2pLoginTimeout timeout = new P2pLoginTimeout();
            timeout.extend(client);
            timeout.finish();
            release.countDown();
            client.eventLoop().submit(() -> { }).sync();
            assertEquals(30_000L, ((ReadTimeoutHandler) client.pipeline().get("timeout")).getReaderIdleTimeInMillis());
        } finally {
            release.countDown();
            if (client != null) client.close().sync();
            if (server != null) server.close().sync();
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
        }
    }

    private static ReadTimeoutHandler handler(EmbeddedChannel channel) {
        return (ReadTimeoutHandler) channel.pipeline().get("timeout");
    }
}
