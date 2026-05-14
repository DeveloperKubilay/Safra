package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

final class P2pQuicSupport {
    private P2pQuicSupport() {
    }

    static boolean enabled() {
        return P2pConstants.quicEnabled();
    }

    static P2pQuicHostSession startHost(Logger logger, InetAddress targetAddress, int tcpPort, int bindPort, int tunnelToken) throws IOException {
        if (!enabled()) {
            throw new IOException("Safra experimental QUIC is disabled");
        }

        try {
            return NettyQuicSupport.startHost(logger, targetAddress, tcpPort, bindPort, tunnelToken);
        } catch (Throwable throwable) {
            throw new IOException("Safra experimental QUIC host is unavailable: " + summarize(throwable), throwable);
        }
    }

    static void bridgeClient(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                             int quicPort, int localPort, int tunnelToken, String encodedCertificate) throws IOException {
        if (!enabled()) {
            throw new IOException("Safra experimental QUIC is disabled");
        }
        if (remoteAddress == null || quicPort < 1) {
            throw new IOException("Safra experimental QUIC target is missing");
        }
        if (encodedCertificate == null || encodedCertificate.isBlank()) {
            throw new IOException("Safra experimental QUIC session certificate is missing");
        }

        try {
            NettyQuicSupport.bridgeClient(logger, localSocket, remoteAddress, quicPort, localPort, tunnelToken, encodedCertificate);
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("Safra experimental QUIC client bridge failed: " + summarize(throwable), throwable);
        }
    }

    private static String summarize(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        String message = cause.getMessage();
        return message == null || message.isBlank()
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + message;
    }
}
