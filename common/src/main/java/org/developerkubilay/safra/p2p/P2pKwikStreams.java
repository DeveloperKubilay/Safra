package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicStream;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

final class P2pKwikStreams {
    private P2pKwikStreams() {
    }

    static void pipe(Logger logger, String side, QuicStream stream, Socket minecraftSocket, Runnable onClosed) {
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
                logger.debug("Safra Kwik {} QUIC -> Minecraft akışı kapandı: {}", side, exception.toString());
            } finally {
                closeBoth.run();
            }
        });
        P2pRuntime.start("safra-kwik-" + side + "-from-minecraft", () -> {
            try {
                minecraftSocket.getInputStream().transferTo(stream.getOutputStream());
                stream.getOutputStream().close();
            } catch (IOException exception) {
                logger.debug("Safra Kwik {} Minecraft -> QUIC akışı kapandı: {}", side, exception.toString());
            } finally {
                closeBoth.run();
            }
        });
    }
}


