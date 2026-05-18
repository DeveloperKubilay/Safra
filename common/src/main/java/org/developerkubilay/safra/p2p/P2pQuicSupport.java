package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class P2pQuicSupport {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CHILD_START_TIMEOUT = Duration.ofSeconds(15);
    private static final long CHILD_OUTPUT_POLL_SLICE_MS = 250L;
    private static final String BROKER_CHILD_PROPERTY = "safra.p2p.quicBrokerChild";
    private static final String READY_FILE_PROPERTY = "safra.p2p.quicReadyFile";
    private static final String READY_MARKER = "SAFRA_QUIC_READY";

    private static volatile ProbeResult cachedProbeResult;
    private static volatile Thread nativeDownloadThread;

    private P2pQuicSupport() {
    }

    static boolean enabled() {
        if (!P2pConstants.quicEnabled()) {
            return false;
        }

        ProbeResult probeResult = cachedProbeResult;
        if (probeResult != null) {
            return probeResult.available;
        }

        synchronized (P2pQuicSupport.class) {
            probeResult = cachedProbeResult;
            if (probeResult == null) {
                probeResult = probeRuntimeAvailability();
                cachedProbeResult = probeResult;
            }
        }

        return probeResult.available;
    }

    static String unavailableReason() {
        ProbeResult probeResult = cachedProbeResult;
        if (probeResult == null || probeResult.available) {
            return "bilinmiyor";
        }
        return probeResult.reason();
    }

    static void downloadNativeAsync() {
        if (!P2pConstants.quicEnabled()) {
            return;
        }

        Thread runningThread = nativeDownloadThread;
        if (runningThread != null && runningThread.isAlive()) {
            return;
        }

        synchronized (P2pQuicSupport.class) {
            runningThread = nativeDownloadThread;
            if (runningThread != null && runningThread.isAlive()) {
                return;
            }

            Thread thread = new Thread(() -> {
                try {
                    P2pQuicNativeManager.ensureExternalNativeJarAvailable();
                    P2pQuicNativeManager.ensureExternalNativeAvailable();
                } catch (IOException ignored) {
                } finally {
                    nativeDownloadThread = null;
                }
            }, "safra-quic-download");
            thread.setDaemon(true);
            nativeDownloadThread = thread;
            thread.start();
        }
    }

    static P2pQuicHostSession startHost(Logger logger, InetAddress targetAddress, int tcpPort, int bindPort, int tunnelToken) throws IOException {
        if (!enabled()) {
            throw new IOException("Safra QUIC is disabled");
        }

        if (isBrokerChildProcess()) {
            return startHostInProcess(logger, targetAddress, tcpPort, bindPort, tunnelToken);
        }

        BrokerProcess broker = startBrokerProcess(List.of(
            "host",
            targetAddress.getHostAddress(),
            Integer.toString(tcpPort),
            Integer.toString(bindPort),
            Integer.toString(tunnelToken)
        ));

        try {
            String ready = broker.awaitReady(CHILD_START_TIMEOUT);
            int firstSpace = ready.indexOf(' ');
            if (firstSpace < 0) {
                throw new IOException("Safra QUIC host helper hazirlik cevabi bozuk");
            }

            int port = Integer.parseInt(ready.substring(0, firstSpace).trim());
            String certificate = ready.substring(firstSpace + 1).trim();
            if (certificate.isBlank()) {
                throw new IOException("Safra QUIC host helper sertifika dondurmedi");
            }
            return new OutOfProcessQuicHostSession(logger, broker, port, certificate);
        } catch (Throwable throwable) {
            broker.close();
            if (throwable instanceof IOException exception) {
                throw exception;
            }
            throw new IOException("Safra QUIC host helper baslatilamadi: " + summarize(throwable), throwable);
        }
    }

    static void bridgeClient(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                             int quicPort, int localPort, int tunnelToken, String encodedCertificate) throws IOException {
        if (!enabled()) {
            throw new IOException("Safra QUIC is disabled");
        }
        if (remoteAddress == null || quicPort < 1) {
            throw new IOException("Safra QUIC target is missing");
        }
        if (encodedCertificate == null || encodedCertificate.isBlank()) {
            throw new IOException("Safra QUIC session certificate is missing");
        }

        if (isBrokerChildProcess()) {
            bridgeClientInProcess(logger, localSocket, remoteAddress, quicPort, localPort, tunnelToken, encodedCertificate, null);
            return;
        }

        ServerSocket bridgeServer = null;
        Socket helperSocket = null;
        BrokerProcess broker = null;
        try {
            bridgeServer = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
            bridgeServer.setSoTimeout(P2pConstants.QUIC_CONNECT_TIMEOUT_MS);

            broker = startBrokerProcess(List.of(
                "client",
                remoteAddress.getAddress().getHostAddress(),
                Integer.toString(quicPort),
                Integer.toString(localPort),
                Integer.toString(tunnelToken),
                encodedCertificate,
                Integer.toString(bridgeServer.getLocalPort())
            ));

            helperSocket = bridgeServer.accept();
            String ready = broker.awaitReady(CHILD_START_TIMEOUT);
            if (!"client".equals(ready)) {
                throw new IOException("Safra QUIC client helper hazirlik cevabi bozuk: " + ready);
            }

            new DirectTcpBridge(logger, localSocket, helperSocket).run();
        } catch (SocketTimeoutException exception) {
            throw new IOException("Safra QUIC client helper loopback baglantisi zaman asimina ugradi", exception);
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("Safra QUIC client bridge failed: " + summarize(throwable), throwable);
        } finally {
            closeSocketQuietly(helperSocket);
            closeServerSocketQuietly(bridgeServer);
            if (broker != null) {
                broker.close();
            }
        }
    }

    private static P2pQuicHostSession startHostInProcess(Logger logger, InetAddress targetAddress, int tcpPort, int bindPort, int tunnelToken) throws IOException {
        try {
            return NettyQuicSupport.startHost(logger, targetAddress, tcpPort, bindPort, tunnelToken);
        } catch (Throwable throwable) {
            throw new IOException("Safra QUIC host kullanilamiyor: " + summarize(throwable), throwable);
        }
    }

    private static void bridgeClientInProcess(Logger logger, Socket localSocket, InetSocketAddress remoteAddress,
                                              int quicPort, int localPort, int tunnelToken, String encodedCertificate,
                                              Runnable onConnected) throws IOException {
        try {
            NettyQuicSupport.bridgeClient(logger, localSocket, remoteAddress, quicPort, localPort, tunnelToken, encodedCertificate, onConnected);
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IOException("Safra QUIC client bridge failed: " + summarize(throwable), throwable);
        }
    }

    private static boolean isBrokerChildProcess() {
        return Boolean.getBoolean(BROKER_CHILD_PROPERTY);
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

    private static ProbeResult probeRuntimeAvailability() {
        BrokerProcess broker = null;
        try {
            broker = startBrokerProcess(List.of("probe"));
            broker.awaitReady(PROBE_TIMEOUT);
            return ProbeResult.availableResult();
        } catch (Throwable throwable) {
            return ProbeResult.unavailableResult(summarize(throwable));
        } finally {
            if (broker != null) {
                broker.close();
            }
        }
    }

    private static BrokerProcess startBrokerProcess(List<String> arguments) throws IOException {
        Path javaExecutable = resolveJavaExecutable();
        Path nativeJar = null;
        try {
            nativeJar = P2pQuicNativeManager.ensureExternalNativeJarAvailable();
        } catch (IOException ignored) {
        }
        String classPath = buildRuntimeClassPath(nativeJar);
        if (javaExecutable == null || classPath.isBlank()) {
            throw new IOException("Safra QUIC helper process classpath'i hazir degil");
        }

        Path argFile = Files.createTempFile("safra-quic-broker-", ".args");
        Path workDir = Files.createTempDirectory("safra-quic-broker-work-");
        Path readyFile = workDir.resolve("ready.txt");
        Path nativeHome = P2pQuicNativeManager.resolvePreferredNativeHome();
        String nativeBaseUrl = P2pQuicNativeManager.configuredBaseUrl();
        try {
            List<String> argLines = new ArrayList<>();
            argLines.add("--enable-native-access=ALL-UNNAMED");
            argLines.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
            argLines.add("-Dio.netty.tryReflectionSetAccessible=true");
            argLines.add("-D" + BROKER_CHILD_PROPERTY + "=true");
            argLines.add("-D" + READY_FILE_PROPERTY + "=" + quoteArgFileValue(readyFile.toString()));
            argLines.add("-D" + P2pQuicNativeManager.NATIVE_HOME_PROPERTY + "=" + quoteArgFileValue(nativeHome.toString()));
            argLines.add("-D" + P2pQuicNativeManager.NATIVE_BASE_URL_PROPERTY + "=" + quoteArgFileValue(nativeBaseUrl));
            argLines.add("-Duser.dir=" + quoteArgFileValue(workDir.toString()));
            argLines.add("-cp");
            argLines.add(quoteArgFileValue(classPath));
            argLines.add(P2pQuicBrokerMain.class.getName());
            for (String argument : arguments) {
                argLines.add(quoteArgFileValue(argument));
            }
            Files.writeString(argFile, String.join(System.lineSeparator(), argLines), StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(javaExecutable.toString(), "@" + argFile.toAbsolutePath())
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
            return new BrokerProcess(process, argFile, workDir, readyFile);
        } catch (Throwable throwable) {
            Files.deleteIfExists(argFile);
            deleteDirectoryQuietly(workDir);
            if (throwable instanceof IOException exception) {
                throw exception;
            }
            throw new IOException("Safra QUIC helper process baslatilamadi: " + summarize(throwable), throwable);
        }
    }

    private static Path resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        if (javaHome.isBlank()) {
            return null;
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        return Files.isRegularFile(executable) ? executable : null;
    }

    private static String buildRuntimeClassPath(Path nativeJar) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();

        String runtimeClassPath = System.getProperty("java.class.path", "");
        if (!runtimeClassPath.isBlank()) {
            for (String entry : runtimeClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }

        try {
            var codeSource = P2pQuicSupport.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                entries.add(Path.of(codeSource.getLocation().toURI()).toString());
            }
        } catch (Throwable ignored) {
        }

        try {
            URL resourceUrl = P2pQuicSupport.class.getResource("P2pQuicSupport.class");
            if (resourceUrl != null) {
                String externalForm = resourceUrl.toExternalForm();
                int separatorIndex = externalForm.indexOf("!/");
                if (separatorIndex > 0 && externalForm.startsWith("jar:")) {
                    String jarUrl = externalForm.substring("jar:".length(), separatorIndex);
                    entries.add(Path.of(URI.create(jarUrl)).toString());
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            ClassLoader classLoader = P2pQuicSupport.class.getClassLoader();
            if (classLoader instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    if (!"file".equalsIgnoreCase(url.getProtocol())) {
                        continue;
                    }
                    entries.add(Path.of(url.toURI()).toString());
                }
            }
        } catch (Throwable ignored) {
        }

        if (nativeJar != null && Files.isRegularFile(nativeJar)) {
            entries.add(nativeJar.toString());
        }

        return String.join(File.pathSeparator, entries);
    }

    private static String quoteArgFileValue(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String tryExtractReadyPayload(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return null;
        }

        String line = rawLine.replaceAll("\\u001B\\[[;\\d]*m", "");
        int markerIndex = line.indexOf(READY_MARKER);
        if (markerIndex < 0) {
            return null;
        }
        return line.substring(markerIndex + READY_MARKER.length()).trim();
    }

    private static void closeSocketQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeServerSocketQuietly(ServerSocket serverSocket) {
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            if (!Files.exists(directory)) {
                return;
            }
            Files.walk(directory)
                .sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }

    private static final class OutOfProcessQuicHostSession implements P2pQuicHostSession {
        private final Logger logger;
        private final BrokerProcess broker;
        private final int port;
        private final String certificate;
        private volatile boolean closed;

        private OutOfProcessQuicHostSession(Logger logger, BrokerProcess broker, int port, String certificate) {
            this.logger = logger;
            this.broker = broker;
            this.port = port;
            this.certificate = certificate;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public String mode() {
            return "direct";
        }

        @Override
        public String certificate() {
            return certificate;
        }

        @Override
        public void punch(InetSocketAddress remoteAddress) {
            if (closed || remoteAddress == null || remoteAddress.isUnresolved()) {
                return;
            }
            try {
                broker.sendCommand("PUNCH " + remoteAddress.getAddress().getHostAddress() + " " + remoteAddress.getPort());
            } catch (IOException exception) {
                logger.debug("Safra QUIC helper punch gonderilemedi: {}", exception.toString());
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            broker.close();
        }
    }

    private static final class BrokerProcess implements AutoCloseable {
        private final Process process;
        private final Path argFile;
        private final Path workDir;
        private final Path readyFile;
        private final BufferedWriter stdin;
        private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
        private final List<String> history = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;

        private BrokerProcess(Process process, Path argFile, Path workDir, Path readyFile) {
            this.process = process;
            this.argFile = argFile;
            this.workDir = workDir;
            this.readyFile = readyFile;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            Thread outputThread = new Thread(this::pumpOutput, "safra-quic-broker-output");
            outputThread.setDaemon(true);
            outputThread.start();
        }

        private void pumpOutput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputQueue.offer(line);
                    synchronized (history) {
                        history.add(line);
                        if (history.size() > 16) {
                            history.remove(0);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private String awaitReady(Duration timeout) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String readyFromFile = readReadyFile();
                if (readyFromFile != null) {
                    return readyFromFile;
                }

                long remainingNanos = deadline - System.nanoTime();
                String line;
                try {
                    long waitMillis = Math.min(
                        CHILD_OUTPUT_POLL_SLICE_MS,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
                    );
                    line = outputQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Safra QUIC helper beklenirken is parcacigi bolundu", exception);
                }

                String readyPayload = tryExtractReadyPayload(line);
                if (readyPayload != null) {
                    return readyPayload;
                }

                if (line == null && !process.isAlive()) {
                    break;
                }
            }

            String readyFromFile = readReadyFile();
            if (readyFromFile != null) {
                return readyFromFile;
            }

            throw new IOException("Safra QUIC helper hazir olamadi: " + outputSummary());
        }

        private String readReadyFile() {
            if (readyFile == null || !Files.isRegularFile(readyFile)) {
                return null;
            }
            try {
                String payload = Files.readString(readyFile, StandardCharsets.UTF_8).trim();
                return payload.isBlank() ? null : payload;
            } catch (IOException ignored) {
                return null;
            }
        }

        private void sendCommand(String command) throws IOException {
            stdin.write(command);
            stdin.newLine();
            stdin.flush();
        }

        private String outputSummary() {
            String summary;
            synchronized (history) {
                summary = String.join(" | ", history);
            }
            if (summary.isBlank()) {
                if (!process.isAlive()) {
                    return "helper cikis kodu " + process.exitValue();
                }
                return "helper cevap vermedi";
            }
            return summary.length() > 400 ? summary.substring(0, 400) + "..." : summary;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            try {
                sendCommand("STOP");
            } catch (IOException ignored) {
            }

            try {
                stdin.close();
            } catch (IOException ignored) {
            }

            if (process.isAlive()) {
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroy();
                    }
                    if (process.isAlive() && !process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }

            try {
                Files.deleteIfExists(argFile);
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(readyFile);
            } catch (IOException ignored) {
            }
            deleteDirectoryQuietly(workDir);
        }
    }

    private record ProbeResult(boolean available, String reason) {
        private static ProbeResult availableResult() {
            return new ProbeResult(true, "");
        }

        private static ProbeResult unavailableResult(String reason) {
            return new ProbeResult(false, reason == null || reason.isBlank() ? "bilinmeyen hata" : reason);
        }
    }
}
