package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class P2pQuicBrokerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pQuicBrokerMain.class);
    private static final String READY_FILE_PROPERTY = "safra.p2p.quicReadyFile";
    private static final String READY_MARKER = "SAFRA_QUIC_READY";

    private P2pQuicBrokerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.exit(2);
            return;
        }

        try {
            switch (args[0]) {
                case "probe" -> runProbe();
                case "host" -> runHost(args);
                case "client" -> runClient(args);
                default -> System.exit(2);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void runProbe() throws IOException {
        NettyQuicSupport.probeRuntimeAvailability();
        signalReady("probe");
    }

    private static void runHost(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IOException("Host arguments are missing");
        }

        InetAddress targetAddress = InetAddress.getByName(args[1]);
        int tcpPort = Integer.parseInt(args[2]);
        int bindPort = Integer.parseInt(args[3]);
        int tunnelToken = Integer.parseInt(args[4]);

        try (P2pQuicHostSession session = NettyQuicSupport.startHost(LOGGER, targetAddress, tcpPort, bindPort, tunnelToken);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            signalReady(session.port() + " " + session.certificate());

            String line;
            while ((line = reader.readLine()) != null) {
                if ("STOP".equals(line)) {
                    return;
                }
                if (!line.startsWith("PUNCH ")) {
                    continue;
                }

                String[] parts = line.split(" ");
                if (parts.length != 3) {
                    continue;
                }

                InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName(parts[1]), Integer.parseInt(parts[2]));
                session.punch(remoteAddress);
            }
        }
    }

    private static void runClient(String[] args) throws Exception {
        if (args.length < 7) {
            throw new IOException("Client arguments are missing");
        }

        InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName(args[1]), Integer.parseInt(args[2]));
        int localPort = Integer.parseInt(args[3]);
        int tunnelToken = Integer.parseInt(args[4]);
        String certificate = args[5];
        int bridgePort = Integer.parseInt(args[6]);

        try (Socket bridgeSocket = new Socket(P2pSockets.loopbackAddress(), bridgePort)) {
            NettyQuicSupport.bridgeClient(
                LOGGER,
                bridgeSocket,
                remoteAddress,
                remoteAddress.getPort(),
                localPort,
                tunnelToken,
                certificate,
                () -> {
                    signalReady("client");
                }
            );
        }
    }

    private static void signalReady(String payload) {
        String readyPayload = payload == null ? "" : payload.trim();
        String readyFilePath = System.getProperty(READY_FILE_PROPERTY, "");
        if (!readyFilePath.isBlank()) {
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(readyFilePath),
                    readyPayload,
                    StandardCharsets.UTF_8
                );
            } catch (IOException ignored) {
            }
        }
        System.out.println(READY_MARKER + " " + readyPayload);
        System.out.flush();
    }
}
