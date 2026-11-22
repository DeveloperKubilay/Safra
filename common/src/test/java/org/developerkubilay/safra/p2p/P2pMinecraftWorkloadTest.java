package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicClientConnection;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
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
@Timeout(90)
class P2pMinecraftWorkloadTest {
    private static final InetSocketAddress CLIENT = new InetSocketAddress(P2pSockets.loopbackAddress(), 4444);
    private static final int LOGIN_BYTES = 2_500_000;
    private static final int REGISTRY_PAUSE_AFTER_BYTES = 512 * 1024;
    private static final int REGISTRY_PAUSE_MS = 2_500;
    private static final int CHUNK_BLOCK_BYTES = 4096;
    private static final int CHUNK_BURST_BLOCKS = 64; // 256 KB per burst
    private static final int CHUNK_BURST_COUNT = 4;   // 1 MB total chunk bursts

    static Stream<Profile> workloadProfiles() {
        return Stream.of(
            new Profile("local-hairpin", 100, 6, 1, 0),
            new Profile("good", 20, 20, 2, 0),
            new Profile("high-latency", 20, 120, 10, 0),
            new Profile("loss-jitter", 5, 80, 25, 29),
            new Profile("rate-change", 20, 60, 5, 0)
        );
    }

    @ParameterizedTest(name = "login-{0}")
    @MethodSource("workloadProfiles")
    void forgeLoginBurstWithRegistryPauseCompletesWithoutTimeout(Profile profile) throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        var executor = newExecutor();
        var clientRef = new AtomicReference<P2pKwikClientTunnel>();
        var hostRef = new AtomicReference<P2pKwikHostServer>();
        Link up = new Link(profile, executor, packet -> hostRef.get().handlePacket(packet, CLIENT, null));
        Link down = new Link(profile, executor, packet -> clientRef.get().handlePacket(packet));
        CountDownLatch established = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             ServerSocket proxy = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             Socket game = new Socket(P2pSockets.loopbackAddress(), proxy.getLocalPort());
             var host = new P2pKwikHostServer(logger, target.getLocalPort(), P2pSockets.loopbackAddress(),
                 P2pKwikCertificate.create(), (transport, packet, address) -> down.send(packet));
             var tunnel = new P2pKwikClientTunnel(logger, 11, 21, proxy.accept(), 15_000L,
                 up::send, () -> {}, failed::countDown, established::countDown)) {
            hostRef.set(host);
            clientRef.set(tunnel);
            game.setSoTimeout(25_000);
            game.setTcpNoDelay(true);

            FutureTask<Void> hostTask = new FutureTask<>(() -> {
                try (Socket hostGame = target.accept()) {
                    hostGame.setSoTimeout(25_000);
                    hostGame.setTcpNoDelay(true);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(hostGame.getOutputStream(), 16 * 1024));
                    DataInputStream in = new DataInputStream(hostGame.getInputStream());
                    assertEquals(1, in.read()); // login start byte from client
                    byte[] block = new byte[16 * 1024];
                    int sent = 0;
                    while (sent < LOGIN_BYTES) {
                        int n = Math.min(block.length, LOGIN_BYTES - sent);
                        Arrays.fill(block, 0, n, (byte) ((sent / block.length) + 1));
                        out.write(block, 0, n);
                        sent += n;
                    }
                    out.flush();
                    byte[] ack = new byte[64];
                    in.readFully(ack);
                    for (int i = 0; i < ack.length; i++) {
                        assertEquals((byte) (i ^ 0x5A), ack[i]);
                    }
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            P2pRuntime.start("safra-test-login-host", hostTask);

            long startNanos = System.nanoTime();
            tunnel.start();
            assertTrue(established.await(10, TimeUnit.SECONDS), "Handshake failed for " + profile.name);

            game.getOutputStream().write(1);
            game.getOutputStream().flush();

            DataInputStream gameIn = new DataInputStream(new BufferedInputStream(game.getInputStream(), 16 * 1024));
            byte[] buf = new byte[16 * 1024];
            int totalRead = 0;
            boolean paused = false;
            while (totalRead < LOGIN_BYTES) {
                int toRead = Math.min(buf.length, LOGIN_BYTES - totalRead);
                gameIn.readFully(buf, 0, toRead);
                byte expectedVal = (byte) ((totalRead / (16 * 1024)) + 1);
                for (int i = 0; i < toRead; i++) {
                    if (buf[i] != expectedVal) {
                        fail("Corrupt login byte at offset " + (totalRead + i));
                    }
                }
                totalRead += toRead;
                if (!paused && totalRead >= REGISTRY_PAUSE_AFTER_BYTES) {
                    paused = true;
                    Thread.sleep(REGISTRY_PAUSE_MS);
                }
            }

            byte[] ack = new byte[64];
            for (int i = 0; i < ack.length; i++) {
                ack[i] = (byte) (i ^ 0x5A);
            }
            game.getOutputStream().write(ack);
            game.getOutputStream().flush();

            release.countDown();
            hostTask.get(15, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertEquals(1, failed.getCount());
            QuicClientConnection conn = readConnection(tunnel);
            assertNotNull(conn);
            assertTrue(conn.isConnected());
            long maxLoginMs = profile.dropEvery > 0 ? 45_000L : 20_000L;
            assertTrue(elapsedMs < maxLoginMs, "Login took too long (" + elapsedMs + " ms) on " + profile.name);

            report(String.format(Locale.ROOT,
                "login,%s,elapsed_ms=%d,rtt_ms=%d,lost=%d,dropped=%d,peak_queue=%d",
                profile.name, elapsedMs, conn.getStats().smoothedRtt(), conn.getStats().lostPackets(),
                up.dropped.get() + down.dropped.get(), Math.max(up.peakQueue.get(), down.peakQueue.get())));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest(name = "gameplay-{0}")
    @MethodSource("workloadProfiles")
    void chunkBurstsPreserveInteractiveMovementLatency(Profile profile) throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        var executor = newExecutor();
        var clientRef = new AtomicReference<P2pKwikClientTunnel>();
        var hostRef = new AtomicReference<P2pKwikHostServer>();
        Link up = new Link(profile, executor, packet -> hostRef.get().handlePacket(packet, CLIENT, null));
        Link down = new Link(profile, executor, packet -> clientRef.get().handlePacket(packet));
        CountDownLatch established = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch chunksDone = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             ServerSocket proxy = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             Socket game = new Socket(P2pSockets.loopbackAddress(), proxy.getLocalPort());
             var host = new P2pKwikHostServer(logger, target.getLocalPort(), P2pSockets.loopbackAddress(),
                 P2pKwikCertificate.create(), (transport, packet, address) -> down.send(packet));
             var tunnel = new P2pKwikClientTunnel(logger, 12, 22, proxy.accept(), 15_000L,
                 up::send, () -> {}, failed::countDown, established::countDown)) {
            hostRef.set(host);
            clientRef.set(tunnel);
            game.setSoTimeout(25_000);
            game.setTcpNoDelay(true);

            FutureTask<Void> hostTask = new FutureTask<>(() -> {
                try (Socket hostGame = target.accept()) {
                    hostGame.setSoTimeout(25_000);
                    hostGame.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(hostGame.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(hostGame.getOutputStream(), 8192));
                    Object writeLock = new Object();

                    FutureTask<Void> burstWriter = new FutureTask<>(() -> {
                        byte[] chunkPayload = new byte[CHUNK_BLOCK_BYTES];
                        Arrays.fill(chunkPayload, (byte) 0x33);
                        for (int burst = 0; burst < CHUNK_BURST_COUNT; burst++) {
                            Thread.sleep(350);
                            for (int block = 0; block < CHUNK_BURST_BLOCKS; block++) {
                                synchronized (writeLock) {
                                    out.writeByte(3); // chunk block
                                    out.writeInt(burst * CHUNK_BURST_BLOCKS + block);
                                    out.writeInt(chunkPayload.length);
                                    out.write(chunkPayload);
                                    out.flush();
                                }
                            }
                        }
                        synchronized (writeLock) {
                            out.writeByte(4); // bursts finished
                            out.writeInt(0);
                            out.writeInt(0);
                            out.flush();
                        }
                        chunksDone.countDown();
                        return null;
                    });
                    P2pRuntime.start("safra-test-chunk-burst", burstWriter);

                    byte[] moveBuf = new byte[64];
                    while (true) {
                        byte type;
                        try {
                            type = in.readByte();
                        } catch (EOFException eof) {
                            break;
                        }
                        int seq = in.readInt();
                        int len = in.readInt();
                        in.readFully(moveBuf, 0, len);
                        if (type == 99) {
                            break;
                        }
                        synchronized (writeLock) {
                            out.writeByte(2); // echo movement/ping
                            out.writeInt(seq);
                            out.writeInt(len);
                            out.write(moveBuf, 0, len);
                            out.flush();
                        }
                    }
                    burstWriter.get(15, TimeUnit.SECONDS);
                }
                return null;
            });
            P2pRuntime.start("safra-test-gameplay-host", hostTask);

            tunnel.start();
            assertTrue(established.await(10, TimeUnit.SECONDS), "Handshake failed for " + profile.name);

            var sentTimes = new ConcurrentHashMap<Integer, Long>();
            var interactiveRttsNanos = new ArrayList<Long>();
            var readerDone = new CountDownLatch(1);
            var receivedChunks = new AtomicInteger();

            FutureTask<Void> clientReader = new FutureTask<>(() -> {
                DataInputStream in = new DataInputStream(new BufferedInputStream(game.getInputStream(), 16 * 1024));
                byte[] scratch = new byte[CHUNK_BLOCK_BYTES];
                while (true) {
                    byte type = in.readByte();
                    int seq = in.readInt();
                    int len = in.readInt();
                    if (len > 0) {
                        in.readFully(scratch, 0, len);
                    }
                    if (type == 2) {
                        Long sentAt = sentTimes.remove(seq);
                        if (sentAt != null) {
                            synchronized (interactiveRttsNanos) {
                                interactiveRttsNanos.add(System.nanoTime() - sentAt);
                            }
                        }
                    } else if (type == 3) {
                        receivedChunks.incrementAndGet();
                    } else if (type == 4) {
                        readerDone.countDown();
                        break;
                    }
                }
                return null;
            });
            P2pRuntime.start("safra-test-gameplay-reader", clientReader);

            DataOutputStream gameOut = new DataOutputStream(game.getOutputStream());
            byte[] movePayload = new byte[64];
            int seq = 0;
            while (chunksDone.getCount() > 0 || seq < 40) {
                seq++;
                Arrays.fill(movePayload, (byte) seq);
                sentTimes.put(seq, System.nanoTime());
                synchronized (gameOut) {
                    gameOut.writeByte(1);
                    gameOut.writeInt(seq);
                    gameOut.writeInt(movePayload.length);
                    gameOut.write(movePayload);
                    gameOut.flush();
                }
                Thread.sleep(50);
                if (seq > 600) {
                    fail("Timed out waiting for chunk bursts on " + profile.name);
                }
            }

            long maxDrainSec = profile.dropEvery > 0 ? 30L : 15L;
            assertTrue(readerDone.await(maxDrainSec, TimeUnit.SECONDS), "Client reader did not finish on " + profile.name);
            // Tell host to stop
            synchronized (gameOut) {
                gameOut.writeByte(99);
                gameOut.writeInt(0);
                gameOut.writeInt(0);
                gameOut.flush();
            }
            clientReader.get(5, TimeUnit.SECONDS);
            hostTask.get(5, TimeUnit.SECONDS);

            assertEquals(CHUNK_BURST_BLOCKS * CHUNK_BURST_COUNT, receivedChunks.get());
            assertEquals(1, failed.getCount());

            List<Long> sorted;
            synchronized (interactiveRttsNanos) {
                sorted = new ArrayList<>(interactiveRttsNanos);
            }
            assertFalse(sorted.isEmpty());
            sorted.sort(Long::compare);
            double p50Ms = sorted.get((sorted.size() - 1) * 50 / 100) / 1e6;
            double p95Ms = sorted.get((sorted.size() - 1) * 95 / 100) / 1e6;
            double p99Ms = sorted.get((sorted.size() - 1) * 99 / 100) / 1e6;
            double maxMs = sorted.get(sorted.size() - 1) / 1e6;

            QuicClientConnection conn = readConnection(tunnel);
            assertNotNull(conn);
            assertTrue(conn.isConnected());

            report(String.format(Locale.ROOT,
                "gameplay,%s,samples=%d,p50_ms=%.2f,p95_ms=%.2f,p99_ms=%.2f,max_ms=%.2f,rtt_ms=%d,lost=%d,dropped=%d,peak_queue=%d",
                profile.name, sorted.size(), p50Ms, p95Ms, p99Ms, maxMs, conn.getStats().smoothedRtt(),
                conn.getStats().lostPackets(), up.dropped.get() + down.dropped.get(),
                Math.max(up.peakQueue.get(), down.peakQueue.get())));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static QuicClientConnection readConnection(P2pKwikClientTunnel tunnel) throws Exception {
        var field = P2pKwikClientTunnel.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (QuicClientConnection) field.get(tunnel);
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static synchronized void report(String row) throws Exception {
        String reportPath = System.getProperty("safra.networkReport");
        if (reportPath != null && !reportPath.isBlank()) {
            Path workloadFile = Path.of(reportPath).resolveSibling("minecraft-workload.csv");
            Files.createDirectories(workloadFile.getParent());
            Files.writeString(workloadFile, row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println("WORKLOAD: " + row);
    }

    private record Profile(String name, int mbps, int rtt, int jitter, int dropEvery) {
        public String toString() {
            return name;
        }
    }

    private static final class Link {
        final Profile profile;
        final ScheduledThreadPoolExecutor executor;
        final Consumer<P2pPacket> deliver;
        final Random random = new Random(629_317);
        final ArrayDeque<Transmission> pending = new ArrayDeque<>();
        final AtomicInteger queued = new AtomicInteger(), peakQueue = new AtomicInteger(), dropped = new AtomicInteger();
        long nextSend, nextArrival, bytes;
        int packets;

        Link(Profile profile, ScheduledThreadPoolExecutor executor, Consumer<P2pPacket> deliver) {
            this.profile = profile;
            this.executor = executor;
            this.deliver = deliver;
        }

        synchronized void send(P2pPacket packet) {
            byte[] encoded = packet.encode();
            int wire = encoded.length + 48;
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
            long arrival = Math.max(nextSend + TimeUnit.MILLISECONDS.toNanos(propagation), nextArrival + 1);
            nextArrival = arrival;
            peakQueue.accumulateAndGet(queued.addAndGet(wire), Math::max);
            pending.addLast(new Transmission(packet, wire, arrival));
            if (pending.size() == 1) {
                executor.schedule(this::drain, Math.max(0, arrival - now), TimeUnit.NANOSECONDS);
            }
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
                deliver.accept(next.packet);
            }
        }

        private record Transmission(P2pPacket packet, int wire, long arrival) {
        }
    }
}
