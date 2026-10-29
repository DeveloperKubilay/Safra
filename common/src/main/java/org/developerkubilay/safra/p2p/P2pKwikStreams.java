package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class P2pKwikStreams {
    private static final int MAX_DIAGNOSTIC_EVENTS = 4;

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
                copy(logger, side, "QUIC -> Minecraft", stream.getInputStream(), minecraftSocket.getOutputStream(), finished);
            } catch (IOException exception) {
                if (!finished.get()) {
                    logger.warn("Safra Kwik {} QUIC -> Minecraft stream closed unexpectedly: {}", side, exception.toString());
                } else {
                    logger.debug("Safra Kwik {} QUIC -> Minecraft stream closed: {}", side, exception.toString());
                }
            } finally {
                closeBoth.run();
            }
        });
        P2pRuntime.start("safra-kwik-" + side + "-from-minecraft", () -> {
            try {
                copy(logger, side, "Minecraft -> QUIC", minecraftSocket.getInputStream(), stream.getOutputStream(), finished);
                stream.getOutputStream().close();
            } catch (IOException exception) {
                if (!finished.get()) {
                    logger.warn("Safra Kwik {} Minecraft -> QUIC stream closed unexpectedly: {}", side, exception.toString());
                } else {
                    logger.debug("Safra Kwik {} Minecraft -> QUIC stream closed: {}", side, exception.toString());
                }
            } finally {
                closeBoth.run();
            }
        });
    }

    private static void copy(Logger logger, String side, String direction, InputStream in, OutputStream out, AtomicBoolean finished) throws IOException {
        if (!SafraBuildInfo.diagnostics()) {
            in.transferTo(out);
            return;
        }
        long startNanos = System.nanoTime();
        logger.info("Safra Kwik {} {}: pipe started", side, direction);
        long totalBytes = 0;
        long firstProgressNanos = 0;
        long lastProgressNanos = 0;
        int copyEvents = 0;
        String reason = "eof";
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                lastProgressNanos = System.nanoTime();
                if (firstProgressNanos == 0) {
                    firstProgressNanos = lastProgressNanos;
                }
                totalBytes += read;
                if (copyEvents < MAX_DIAGNOSTIC_EVENTS) {
                    copyEvents++;
                    logger.info("Safra Kwik {} {}: copied {} bytes at {}ms (event {}, total {} bytes)",
                        side, direction, read, TimeUnit.NANOSECONDS.toMillis(lastProgressNanos - startNanos),
                        copyEvents, totalBytes);
                }
            }
        } catch (IOException exception) {
            reason = finished.get() ? "closed" : exception.getClass().getSimpleName();
            throw exception;
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.info("Safra Kwik {} {}: completed {} bytes in {}ms (first={}ms, last={}ms, reason={})",
                side, direction, totalBytes, elapsedMs,
                firstProgressNanos == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(firstProgressNanos - startNanos),
                lastProgressNanos == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(lastProgressNanos - startNanos), reason);
        }
    }
}
