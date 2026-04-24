package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ReliableTunnelConnection implements AutoCloseable {
    interface PacketSender {
        void send(P2pPacket packet, InetSocketAddress remoteAddress);
    }

    interface RemovalCallback {
        void remove(int connectionId);
    }

    private final Logger logger;
    private final String side;
    private final int token;
    private final int connectionId;
    private final InetSocketAddress remoteAddress;
    private final Socket tcpSocket;
    private final PacketSender packetSender;
    private final RemovalCallback removalCallback;
    private final ScheduledExecutorService scheduler;
    private final boolean initiator;
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger nextSendSequence = new AtomicInteger(1);
    private final AtomicInteger nextExpectedSequence = new AtomicInteger(1);
    private final Object sendWindowMonitor = new Object();
    private final ConcurrentNavigableMap<Integer, PendingSegment> pendingSegments = new ConcurrentSkipListMap<>();
    private final Map<Integer, byte[]> receiveBuffer = new ConcurrentHashMap<>();
    private final BlockingQueue<byte[]> inboundQueue = new LinkedBlockingQueue<>();

    private volatile boolean opened;
    private volatile long lastPacketReceivedAt = System.currentTimeMillis();
    private volatile long lastPacketSentAt = System.currentTimeMillis();
    private volatile long lastOpenPacketAt = 0L;
    private volatile long openStartedAt = System.currentTimeMillis();
    private volatile ScheduledFuture<?> maintenanceTask;

    ReliableTunnelConnection(Logger logger, String side, int token, int connectionId, InetSocketAddress remoteAddress,
                             Socket tcpSocket, PacketSender packetSender, RemovalCallback removalCallback,
                             ScheduledExecutorService scheduler, boolean initiator) {
        this.logger = logger;
        this.side = side;
        this.token = token;
        this.connectionId = connectionId;
        this.remoteAddress = remoteAddress;
        this.tcpSocket = tcpSocket;
        this.packetSender = packetSender;
        this.removalCallback = removalCallback;
        this.scheduler = scheduler;
        this.initiator = initiator;
    }

    void start() throws IOException {
        P2pSockets.tune(tcpSocket);

        Thread readerThread = Thread.ofVirtual().name(side + "-udp-reader-" + connectionId).start(this::tcpReaderLoop);
        Thread writerThread = Thread.ofVirtual().name(side + "-udp-writer-" + connectionId).start(this::tcpWriterLoop);
        maintenanceTask = scheduler.scheduleAtFixedRate(this::maintenanceTick, P2pConstants.MAINTENANCE_TICK_MS,
            P2pConstants.MAINTENANCE_TICK_MS, TimeUnit.MILLISECONDS);

        if (!initiator) {
            markOpened();
        } else {
            sendOpen();
        }

        readerThread.setUncaughtExceptionHandler((thread, throwable) -> closeFromError("reader", throwable));
        writerThread.setUncaughtExceptionHandler((thread, throwable) -> closeFromError("writer", throwable));
    }

    void handlePacket(P2pPacket packet) {
        if (closed.get()) {
            return;
        }

        lastPacketReceivedAt = System.currentTimeMillis();
        switch (packet.type()) {
            case OPEN_ACK -> markOpened();
            case DATA -> handleData(packet);
            case ACK -> processAcknowledgement(packet.acknowledgement());
            case CLOSE -> closeWithoutNotify("remote closed");
            case OPEN -> {
                if (!initiator) {
                    sendOpenAck();
                }
            }
        }
    }

    void sendOpenAck() {
        sendPacket(P2pPacket.openAck(token, connectionId));
    }

    @Override
    public void close() {
        closeLocally("closed");
    }

    private void tcpReaderLoop() {
        awaitOpen();
        if (closed.get()) {
            return;
        }

        try (InputStream inputStream = tcpSocket.getInputStream()) {
            byte[] buffer = new byte[P2pConstants.MAX_PAYLOAD_SIZE];
            while (!closed.get()) {
                waitForWindow();
                int read = inputStream.read(buffer);
                if (read < 0) {
                    closeLocally("tcp eof");
                    return;
                }

                byte[] payload = Arrays.copyOf(buffer, read);
                int sequence = nextSendSequence.getAndIncrement();
                pendingSegments.put(sequence, new PendingSegment(sequence, payload));
                sendData(sequence, payload);
            }
        } catch (IOException exception) {
            closeFromError("tcp read", exception);
        }
    }

    private void tcpWriterLoop() {
        try (OutputStream outputStream = tcpSocket.getOutputStream()) {
            while (!closed.get()) {
                byte[] payload = inboundQueue.poll(500L, TimeUnit.MILLISECONDS);
                if (payload == null) {
                    continue;
                }

                outputStream.write(payload);
                outputStream.flush();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            closeLocally("writer interrupted");
        } catch (IOException exception) {
            closeFromError("tcp write", exception);
        }
    }

    private void handleData(P2pPacket packet) {
        processAcknowledgement(packet.acknowledgement());
        if (!opened) {
            markOpened();
        }

        int sequence = packet.sequence();
        if (sequence <= 0) {
            sendAcknowledgement();
            return;
        }

        if (sequence < nextExpectedSequence.get()) {
            sendAcknowledgement();
            return;
        }

        receiveBuffer.putIfAbsent(sequence, packet.payload());
        flushReceiveBuffer();
        sendAcknowledgement();
    }

    private void flushReceiveBuffer() {
        while (true) {
            int expected = nextExpectedSequence.get();
            byte[] payload = receiveBuffer.remove(expected);
            if (payload == null) {
                return;
            }

            inboundQueue.offer(payload);
            nextExpectedSequence.incrementAndGet();
        }
    }

    private void waitForWindow() {
        synchronized (sendWindowMonitor) {
            while (!closed.get() && pendingSegments.size() >= P2pConstants.SEND_WINDOW_SIZE) {
                try {
                    sendWindowMonitor.wait(50L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void processAcknowledgement(int acknowledgement) {
        if (acknowledgement <= 0) {
            return;
        }

        boolean removed = false;
        while (!pendingSegments.isEmpty()) {
            Map.Entry<Integer, PendingSegment> entry = pendingSegments.firstEntry();
            if (entry == null || entry.getKey() >= acknowledgement) {
                break;
            }
            pendingSegments.remove(entry.getKey());
            removed = true;
        }

        if (removed) {
            synchronized (sendWindowMonitor) {
                sendWindowMonitor.notifyAll();
            }
        }
    }

    private void maintenanceTick() {
        if (closed.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (initiator && !opened) {
            if (now - openStartedAt > P2pConstants.OPEN_TIMEOUT_MS) {
                logger.debug("{} connection {} could not open UDP tunnel to {} within {} ms", side, connectionId, remoteAddress, P2pConstants.OPEN_TIMEOUT_MS);
                closeLocally("open timeout");
                return;
            }

            if (now - lastOpenPacketAt >= P2pConstants.OPEN_RESEND_MS) {
                sendOpen();
            }
            return;
        }

        if (now - lastPacketReceivedAt > P2pConstants.CONNECTION_TIMEOUT_MS) {
            closeLocally("remote timeout");
            return;
        }

        for (PendingSegment segment : pendingSegments.values()) {
            if (now - segment.lastSentAt >= P2pConstants.RESEND_MS) {
                sendData(segment.sequence, segment.payload);
            }
        }

        if (now - lastPacketSentAt >= P2pConstants.KEEP_ALIVE_MS) {
            sendAcknowledgement();
        }
    }

    private void sendOpen() {
        lastOpenPacketAt = System.currentTimeMillis();
        sendPacket(P2pPacket.open(token, connectionId));
    }

    private void sendData(int sequence, byte[] payload) {
        PendingSegment segment = pendingSegments.get(sequence);
        if (segment != null) {
            segment.lastSentAt = System.currentTimeMillis();
        }
        sendPacket(P2pPacket.data(token, connectionId, sequence, nextExpectedSequence.get(), payload));
    }

    private void sendAcknowledgement() {
        sendPacket(P2pPacket.ack(token, connectionId, nextExpectedSequence.get()));
    }

    private void sendPacket(P2pPacket packet) {
        lastPacketSentAt = System.currentTimeMillis();
        packetSender.send(packet, remoteAddress);
    }

    private void markOpened() {
        if (opened) {
            return;
        }
        opened = true;
        openLatch.countDown();
        logger.debug("{} connection {} UDP tunnel opened with {}", side, connectionId, remoteAddress);
    }

    private void awaitOpen() {
        try {
            openLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeLocally(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            sendPacket(P2pPacket.close(token, connectionId));
        }

        cleanup(reason);
    }

    private void closeWithoutNotify(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        cleanup(reason);
    }

    private void closeFromError(String context, Throwable throwable) {
        logger.debug("{} connection {} {} failed: {}", side, connectionId, context, throwable.toString());
        closeLocally(context);
    }

    private void cleanup(String reason) {
        ScheduledFuture<?> task = maintenanceTask;
        if (task != null) {
            task.cancel(false);
        }
        openLatch.countDown();

        try {
            tcpSocket.close();
        } catch (IOException ignored) {
        }

        synchronized (sendWindowMonitor) {
            sendWindowMonitor.notifyAll();
        }

        inboundQueue.clear();
        pendingSegments.clear();
        receiveBuffer.clear();
        removalCallback.remove(connectionId);
        logger.debug("{} connection {} closed: {}", side, connectionId, reason);
    }

    private static final class PendingSegment {
        private final int sequence;
        private final byte[] payload;
        private volatile long lastSentAt;

        private PendingSegment(int sequence, byte[] payload) {
            this.sequence = sequence;
            this.payload = payload;
            this.lastSentAt = 0L;
        }
    }
}
