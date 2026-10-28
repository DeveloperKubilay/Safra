package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.impl.QuicClientConnectionImpl;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("network")
@Timeout(70)
class P2pNetworkQualityTest {
    private static final int MIN = P2pConstants.MIN_STREAM_WINDOW_BYTES;
    private static final int MAX = P2pConstants.MAX_STREAM_WINDOW_BYTES;
    private static final int BLOCK = 4096;
    private static final int BLOCKS = 256;
    private static final InetSocketAddress CLIENT = new InetSocketAddress(P2pSockets.loopbackAddress(), 4444);

    static Stream<Object[]> conditions() {
        String selected = System.getProperty("safra.networkProfile", "all");
        return Stream.of(new Profile("good", 20, 20, 2, 0, 0),
            new Profile("high-latency", 20, 120, 10, 0, 0),
            new Profile("loss-jitter", 5, 80, 25, 29, 0),
            new Profile("rate-change", 20, 60, 5, 0, 0),
            new Profile("slow-reader", 20, 120, 5, 0, 0),
            new Profile("reorder-duplicate", 8, 60, 50, 37, 17))
            .filter(profile -> selected.equals("all") || selected.equals(profile.name))
            .flatMap(profile -> Stream.of(false, true).flatMap(upload -> Stream.of(false, true)
                .map(adaptive -> new Object[]{profile, upload, adaptive})));
    }

    @ParameterizedTest(name = "{0}, upload={1}, adaptive={2}")
    @MethodSource("conditions")
    void transfersWithoutCorruptionOrOversizedPackets(Profile profile, boolean upload, boolean adaptive) throws Exception {
        int initial = adaptive ? MIN : (int) Math.max(MIN, Math.min(MAX, 1_500_000L * profile.rtt / 1000));
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        var clientRef = new AtomicReference<P2pKwikDatagramSocket>();
        var serverRef = new AtomicReference<P2pKwikDatagramSocket>();
        Link up = new Link(profile, executor, bytes -> serverRef.get().deliver(bytes, CLIENT));
        Link down = new Link(profile, executor, bytes -> clientRef.get().deliver(bytes));
        var peer = new CompletableFuture<Endpoint>();
        CountDownLatch release = new CountDownLatch(1);
        var certificate = P2pKwikCertificate.create();
        try (var serverSocket = new P2pKwikDatagramSocket((bytes, address) -> down.send(bytes));
             var clientSocket = new P2pKwikDatagramSocket((bytes, address) -> up.send(bytes))) {
            serverRef.set(serverSocket);
            clientRef.set(clientSocket);
            var config = ServerConnectionConfig.builder().maxOpenPeerInitiatedBidirectionalStreams(1)
                .maxOpenPeerInitiatedUnidirectionalStreams(0).maxConnectionBufferSize(MAX)
                .maxBidirectionalStreamBufferSize(initial).maxIdleTimeoutInSeconds(15)
                .useStrictSmallestAllowedMaximumDatagramSize(true).build();
            try (var server = ServerConnector.builder().withSocket(serverSocket)
                .withKeyStore(certificate.keyStore(), "safra-p2p", P2pKwikCertificate.keyPassword())
                .withConfiguration(config).withLogger(new NullLogger()).build()) {
                server.registerApplicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL, new ApplicationProtocolConnectionFactory() {
                    public ApplicationProtocolConnection createConnection(String protocol, QuicConnection connection) {
                        return new ApplicationProtocolConnection() {
                            public void acceptPeerInitiatedStream(QuicStream stream) {
                                peer.complete(new Endpoint(connection, stream));
                            }
                        };
                    }
                    public int maxConcurrentPeerInitiatedBidirectionalStreams() { return 1; }
                    public int maxConcurrentPeerInitiatedUnidirectionalStreams() { return 0; }
                });
                server.start();
                var builder = new QuicClientConnectionImpl.ExtendedBuilder();
                builder.maxUdpPayloadSize(P2pConstants.MAX_PAYLOAD_SIZE);
                builder.enforceMaxUdpPayloadSize(true);
                builder.useStrictSmallestAllowedMaximumDatagramSize();
                QuicClientConnection client = builder.host(P2pConstants.LOCAL_PROXY_HOST)
                    .port(P2pConstants.KWIK_VIRTUAL_PORT).applicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL)
                    .connectTimeout(Duration.ofSeconds(8)).maxIdleTimeout(Duration.ofSeconds(15))
                    .defaultStreamReceiveBufferSize((long) initial).noServerCertificateCheck()
                    .customTrustStore(P2pKwikCertificate.trustStore(certificate.encoded()))
                    .socketFactory(address -> clientSocket).build();
                try {
                    var deadline = executor.schedule(() -> {
                        client.close();
                        remoteClose(peer);
                    }, 60, TimeUnit.SECONDS);
                    client.connect();
                    QuicStream local = client.createStream(true);
                    local.getOutputStream().write(1);
                    Endpoint remote = peer.get(8, TimeUnit.SECONDS);
                    assertEquals(1, remote.stream.getInputStream().read());
                    QuicStream sender = upload ? local : remote.stream;
                    QuicStream receiver = upload ? remote.stream : local;
                    if (adaptive) receiver.enableReceiveWindowAutoTuning(MIN, MAX);
                    FutureTask<Void> writer = new FutureTask<>(() -> {
                        var out = new DataOutputStream(sender.getOutputStream());
                        byte[] block = new byte[BLOCK];
                        for (int index = 0; index < BLOCKS; index++) {
                            Arrays.fill(block, (byte) index);
                            out.writeLong(System.nanoTime());
                            out.writeInt(index);
                            out.write(block);
                        }
                        out.close();
                        release.await(30, TimeUnit.SECONDS);
                        return null;
                    });
                    long start = System.nanoTime(), peak = receiver.getReceiveWindowSize();
                    P2pRuntime.start("safra-network-writer", writer);
                    var in = new DataInputStream(receiver.getInputStream());
                    List<Long> delays = new ArrayList<>();
                    byte[] expected = new byte[BLOCK];
                    for (int index = 0; index < BLOCKS; index++) {
                        long sent = in.readLong();
                        assertEquals(index, in.readInt());
                        Arrays.fill(expected, (byte) index);
                        assertArrayEquals(expected, in.readNBytes(BLOCK));
                        delays.add(System.nanoTime() - sent);
                        long window = receiver.getReceiveWindowSize();
                        assertTrue(window >= MIN && window <= MAX, "Window outside memory budget");
                        peak = Math.max(peak, window);
                        if (profile.name.equals("slow-reader") && index >= BLOCKS / 2) Thread.sleep(12);
                    }
                    assertEquals(-1, in.read());
                    long duration = System.nanoTime() - start;
                    release.countDown();
                    writer.get(2, TimeUnit.SECONDS);
                    assertTrue(client.isConnected());
                    assertTrue(up.maximum.get() <= 1200 && down.maximum.get() <= 1200);
                    if (profile.dropEvery > 0) assertTrue(up.dropped.get() + down.dropped.get() > 0);
                    if (profile.duplicateEvery > 0) assertTrue(up.duplicated.get() + down.duplicated.get() > 0);
                    if (profile.name.equals("rate-change")) assertTrue((upload ? up : down).bytes > 768 * 1024);
                    if (adaptive && profile.name.equals("high-latency")) assertTrue(peak > MIN, "Window never grew");
                    delays.sort(Long::compare);
                    long p95 = delays.get((delays.size() - 1) * 95 / 100);
                    QuicConnection sendingConnection = upload ? client : remote.connection;
                    report(String.format(Locale.ROOT, "%s,%s,%s,%.3f,%.3f,%.2f,%d,%d,%d,%d,%d,%d",
                        profile.name, upload ? "upload" : "download", adaptive ? "adaptive" : "fixed-rtt",
                        duration / 1e9, BLOCKS * BLOCK * 8.0 / duration * 1000, p95 / 1e6,
                        peak, receiver.getReceiveWindowSize(), sendingConnection.getStats().lostPackets(),
                        up.dropped.get() + down.dropped.get(), Math.max(up.maximum.get(), down.maximum.get()),
                        Math.max(up.peakQueue.get(), down.peakQueue.get())));
                    deadline.cancel(false);
                } finally {
                    client.close();
                }
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static synchronized void report(String row) throws Exception {
        Path file = Path.of(System.getProperty("safra.networkReport"));
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file,
            "profile,direction,mode,seconds,mbps,p95_ms,peak_window,final_window,lost,dropped,max_quic,peak_queue\n");
        Files.writeString(file, row + "\n", StandardOpenOption.APPEND);
        System.out.println(row);
    }

    private static void remoteClose(CompletableFuture<Endpoint> peer) {
        Endpoint endpoint = peer.getNow(null);
        if (endpoint != null) endpoint.connection.close();
    }

    private record Endpoint(QuicConnection connection, QuicStream stream) { }
    private record Profile(String name, int mbps, int rtt, int jitter, int dropEvery, int duplicateEvery) {
        public String toString() { return name; }
    }

    private static final class Link {
        final Profile profile;
        final ScheduledThreadPoolExecutor executor;
        final Consumer<byte[]> deliver;
        final Random random = new Random(629_317);
        final ArrayDeque<Transmission> pending = new ArrayDeque<>();
        final AtomicInteger maximum = new AtomicInteger(), queued = new AtomicInteger(), peakQueue = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger(), duplicated = new AtomicInteger();
        long nextSend, nextArrival, bytes;
        int packets;

        Link(Profile profile, ScheduledThreadPoolExecutor executor, Consumer<byte[]> deliver) {
            this.profile = profile;
            this.executor = executor;
            this.deliver = deliver;
        }

        synchronized void send(byte[] payload) {
            maximum.accumulateAndGet(payload.length, Math::max);
            int wire = P2pPacket.quicData(1, 1, payload).encode().length + 48;
            if (wire > 1280 || queued.get() + wire > 256 * 1024 ||
                (++packets > 10 && profile.dropEvery > 0 && packets % profile.dropEvery == 0)) {
                dropped.incrementAndGet();
                return;
            }
            bytes += wire;
            boolean slow = profile.name.equals("rate-change") && bytes >= 256 * 1024 && bytes < 768 * 1024;
            long now = System.nanoTime();
            nextSend = Math.max(now, nextSend) + wire * 8000L / (slow ? 1 : profile.mbps);
            long propagation = Math.max(0, (slow ? 90 : profile.rtt / 2) + random.nextInt(2 * profile.jitter + 1) - profile.jitter);
            long arrival = nextSend + TimeUnit.MILLISECONDS.toNanos(propagation);
            if (!profile.name.equals("reorder-duplicate")) arrival = Math.max(arrival, nextArrival + 1);
            nextArrival = arrival;
            schedule(payload, wire, arrival);
            if (profile.duplicateEvery > 0 && packets % profile.duplicateEvery == 0) {
                duplicated.incrementAndGet();
                schedule(payload.clone(), wire, arrival + 2_000_000L);
            }
        }

        private void schedule(byte[] payload, int wire, long arrival) {
            peakQueue.accumulateAndGet(queued.addAndGet(wire), Math::max);
            if (!profile.name.equals("reorder-duplicate")) {
                pending.addLast(new Transmission(payload, wire, arrival));
                if (pending.size() == 1) executor.schedule(this::drain, Math.max(0, arrival - System.nanoTime()), TimeUnit.NANOSECONDS);
                return;
            }
            executor.schedule(() -> {
                queued.addAndGet(-wire);
                deliver.accept(payload);
            }, Math.max(0, arrival - System.nanoTime()), TimeUnit.NANOSECONDS);
        }

        private synchronized void drain() {
            while (!pending.isEmpty()) {
                Transmission next = pending.peekFirst();
                long delay = next.arrival - System.nanoTime();
                if (delay > 0) {
                    executor.schedule(this::drain, delay, TimeUnit.NANOSECONDS);
                    return;
                }
                pending.removeFirst();
                queued.addAndGet(-next.wire);
                deliver.accept(next.payload);
            }
        }

        private record Transmission(byte[] payload, int wire, long arrival) { }
    }
}
