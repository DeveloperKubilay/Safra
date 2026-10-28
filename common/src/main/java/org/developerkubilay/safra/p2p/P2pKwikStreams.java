package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

final class P2pKwikStreams {
    private P2pKwikStreams() {
    }

    static void logLinkQuality(Logger logger, String side, int id, QuicConnection connection, QuicStream stream) {
        if (!SafraBuildInfo.diagnostics() || connection == null || stream == null) return;
        Statistics stats = connection.getStats();
        logger.info("Safra {} tunnel {}: {} packets, {} lost, rtt {}ms (variation {}ms), receive window {} bytes, sent {} bytes (stream {} bytes), latest RTT {}ms",
            side, id, stats.packetsSent(), stats.lostPackets(), stats.smoothedRtt(), stats.rttVar(),
            stream.getReceiveWindowSize(), stats.bytesSent(), stats.dataBytesSent(), stats.latestRtt());
    }

    static void pipe(Logger logger, String side, QuicStream stream, Socket minecraftSocket, Runnable onClosed) {
        stream.enableReceiveWindowAutoTuning(P2pConstants.MIN_STREAM_WINDOW_BYTES, P2pConstants.MAX_STREAM_WINDOW_BYTES);
        AtomicBoolean finished = new AtomicBoolean();
        Runnable closeBoth = () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                minecraftSocket.close();
            } catch (IOException ignored) {
            }
            onClosed.run();
        };

        P2pRuntime.start("safra-kwik-" + side + "-to-minecraft", () -> {
            try {
                stream.getInputStream().transferTo(minecraftSocket.getOutputStream());
            } catch (IOException exception) {
                if (!finished.get()) {
                    logger.warn("Safra Kwik {} QUIC -> Minecraft stream closed unexpectedly: {}", side, exception.toString());
                } else if (SafraBuildInfo.diagnostics()) {
                    logger.info("Safra Kwik {} QUIC -> Minecraft stream closed: {}", side, exception.toString());
                } else {
                    logger.debug("Safra Kwik {} QUIC -> Minecraft stream closed: {}", side, exception.toString());
                }
            } finally {
                closeBoth.run();
            }
        });
        P2pRuntime.start("safra-kwik-" + side + "-from-minecraft", () -> {
            try {
                minecraftSocket.getInputStream().transferTo(stream.getOutputStream());
                stream.getOutputStream().close();
            } catch (IOException exception) {
                if (!finished.get()) {
                    logger.warn("Safra Kwik {} Minecraft -> QUIC stream closed unexpectedly: {}", side, exception.toString());
                } else if (SafraBuildInfo.diagnostics()) {
                    logger.info("Safra Kwik {} Minecraft -> QUIC stream closed: {}", side, exception.toString());
                } else {
                    logger.debug("Safra Kwik {} Minecraft -> QUIC stream closed: {}", side, exception.toString());
                }
            } finally {
                closeBoth.run();
            }
        });
    }
}


