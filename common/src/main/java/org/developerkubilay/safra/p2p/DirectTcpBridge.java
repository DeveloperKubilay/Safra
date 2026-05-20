package org.developerkubilay.safra.p2p;

import java.io.BufferedOutputStream;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class DirectTcpBridge implements AutoCloseable {
    private final Logger logger;
    private final Socket localSocket;
    private final Socket remoteSocket;
    private final CountDownLatch done = new CountDownLatch(2);
    private final AtomicBoolean closed = new AtomicBoolean();

    DirectTcpBridge(Logger logger, Socket localSocket, Socket remoteSocket) {
        this.logger = logger;
        this.localSocket = localSocket;
        this.remoteSocket = remoteSocket;
    }

    void run() throws IOException {
        P2pSockets.tune(localSocket);
        P2pSockets.tune(remoteSocket);

        P2pRuntime.start("safra-direct-tcp-up", () -> pump(localSocket, remoteSocket));
        P2pRuntime.start("safra-direct-tcp-down", () -> pump(remoteSocket, localSocket));

        try {
            done.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Direct TCP bridge interrupted", exception);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            localSocket.close();
        } catch (IOException ignored) {
        }
        try {
            remoteSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void pump(Socket sourceSocket, Socket targetSocket) {
        byte[] buffer = new byte[P2pConstants.DIRECT_TCP_COPY_BUFFER_SIZE];
        try (InputStream input = sourceSocket.getInputStream();
             OutputStream output = new BufferedOutputStream(
                 targetSocket.getOutputStream(),
                 P2pConstants.DIRECT_TCP_COPY_BUFFER_SIZE
             )) {
            int pendingBytes = 0;
            while (!closed.get()) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                pendingBytes += read;
                if (pendingBytes >= P2pConstants.DIRECT_TCP_FLUSH_THRESHOLD_BYTES || input.available() == 0) {
                    output.flush();
                    pendingBytes = 0;
                }
            }
            output.flush();
        } catch (IOException exception) {
            if (!closed.get()) {
                logger.debug("Direct TCP bridge pump stopped: {}", exception.toString());
            }
        } finally {
            close();
            done.countDown();
        }
    }
}
