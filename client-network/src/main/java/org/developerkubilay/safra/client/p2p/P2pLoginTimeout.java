package org.developerkubilay.safra.client.p2p;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class P2pLoginTimeout {
    private volatile boolean finished;
    private volatile Channel channel;
    private ReadTimeoutHandler extendedHandler;
    private long originalTimeoutMillis;

    public void extend(Channel connection) {
        if (finished || connection == null) {
            return;
        }
        channel = connection;
        runOnEventLoop(connection, () -> {
            if (finished || !connection.isActive() || extendedHandler != null) {
                return;
            }
            ChannelHandler handler = connection.pipeline().get("timeout");
            if (handler == null || handler.getClass() != ReadTimeoutHandler.class) {
                return;
            }
            ReadTimeoutHandler timeout = (ReadTimeoutHandler) handler;
            if (timeout.getReaderIdleTimeInMillis() != TimeUnit.SECONDS.toMillis(30L)) {
                return;
            }
            originalTimeoutMillis = timeout.getReaderIdleTimeInMillis();
            extendedHandler = new ReadTimeoutHandler(P2pConstants.PROXY_LOGIN_TIMEOUT_SECONDS);
            connection.pipeline().replace(timeout, "timeout", extendedHandler);
            if (SafraBuildInfo.diagnostics()) {
                LoggerFactory.getLogger(P2pLoginTimeout.class).info(
                    "Safra LOGIN read timeout extended to {}s on {} -> {}",
                    P2pConstants.PROXY_LOGIN_TIMEOUT_SECONDS, connection.localAddress(), connection.remoteAddress());
            }
        });
    }

    public void finish() {
        finished = true;
        Channel connection = channel;
        if (connection == null) {
            return;
        }
        runOnEventLoop(connection, () -> {
            if (connection.isActive() && extendedHandler != null
                && connection.pipeline().get("timeout") == extendedHandler) {
                connection.pipeline().replace(extendedHandler, "timeout",
                    new ReadTimeoutHandler(originalTimeoutMillis, TimeUnit.MILLISECONDS));
                if (SafraBuildInfo.diagnostics()) {
                    LoggerFactory.getLogger(P2pLoginTimeout.class).info(
                        "Safra LOGIN read timeout restored to {}ms on {} -> {}",
                        originalTimeoutMillis, connection.localAddress(), connection.remoteAddress());
                }
            }
            extendedHandler = null;
        });
    }

    private static void runOnEventLoop(Channel channel, Runnable action) {
        if (channel.eventLoop().inEventLoop()) {
            action.run();
        } else if (!channel.eventLoop().isShuttingDown()) {
            try {
                channel.eventLoop().execute(action);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    }
}
