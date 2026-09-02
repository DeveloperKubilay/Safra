package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.BufferedOutputStream;
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
import java.util.concurrent.locks.LockSupport;

final class ReliableTunnelConnection implements AutoCloseable {
    interface PacketSender {
        void send(P2pPacket packet, InetSocketAddress remoteAddress);
    }

    interface RemovalCallback {
        void remove(int connectionId);
    }

    interface OpenFallbackHandler {
        void fallback(ReliableTunnelConnection connection);
    }

    private final Logger logger;
    private final String side;
    private final int token;
    private final int connectionId;
    private volatile PacketRoute packetRoute;
    private final Socket tcpSocket;
    private final RemovalCallback removalCallback;
    private final ScheduledExecutorService scheduler;
    private final boolean initiator;
    private final OpenFallbackHandler openFallbackHandler;
    private final boolean diagnosticsLoggingEnabled;
    private final long diagnosticsSummaryMs;
    private final long diagnosticsTickDriftWarnMs;
    private final TunnelDiagnostics diagnostics;

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
    private volatile int openPacketsSent;
    private volatile boolean openFallbackStarted;
    private volatile boolean openFallbackPending;
    private volatile long lastPayloadSentAt = System.currentTimeMillis();
    private volatile long lastAcknowledgementProgressAt = System.currentTimeMillis();
    private volatile long retransmitTimeoutMs = P2pConstants.INITIAL_RESEND_MS;
    private volatile double smoothedRoundTripTimeMs = -1.0D;
    private volatile double roundTripVariationMs = -1.0D;
    private volatile int lastProcessedAcknowledgement;
    private volatile int duplicateAcknowledgementCount;
    private volatile int lastNegativeAcknowledgementSequence;
    private volatile long lastNegativeAcknowledgementAt;
    private volatile int headOfLineMissingSequence;
    private volatile long headOfLineBlockedSince;
    private volatile long lastHeadOfLineWarningAt;
    private volatile long lastWindowBlockedSince;
    private volatile long lastWindowWarningAt;
    private volatile long lastCongestionEventAt;
    private volatile long lastMaintenanceTickAt;
    private volatile long lastMaintenanceDriftWarningAt;
    private volatile int sendWindowSize = P2pConstants.INITIAL_SEND_WINDOW_SIZE;
    private volatile int slowStartThreshold = P2pConstants.MAX_SEND_WINDOW_SIZE;
    private volatile int acknowledgementsSinceWindowIncrease;
    private volatile long nextPayloadSendAtNanos;
    private volatile int pacingBurstBudget = P2pConstants.PACING_BURST_PACKETS;
    private volatile int lastAcknowledgementSent;
    private volatile int lastAcknowledgementMaskSent;
    private volatile int receivedPacketsSinceLastAcknowledgement;
    private volatile long lastAcknowledgementPacketAt;
    private volatile ScheduledFuture<?> maintenanceTask;
    private volatile ScheduledFuture<?> delayedAcknowledgementTask;
    private volatile ScheduledFuture<?> acknowledgementReinforcementTask;

    ReliableTunnelConnection(Logger logger, String side, int token, int connectionId, InetSocketAddress remoteAddress,
                             Socket tcpSocket, PacketSender packetSender, RemovalCallback removalCallback,
                             ScheduledExecutorService scheduler, boolean initiator, OpenFallbackHandler openFallbackHandler) {
        this.logger = logger;
        this.side = side;
        this.token = token;
        this.connectionId = connectionId;
        this.packetRoute = new PacketRoute(remoteAddress, packetSender);
        this.tcpSocket = tcpSocket;
        this.removalCallback = removalCallback;
        this.scheduler = scheduler;
        this.initiator = initiator;
        this.openFallbackHandler = openFallbackHandler;
        this.diagnosticsLoggingEnabled = P2pConstants.diagnosticsEnabled();
        this.diagnosticsSummaryMs = diagnosticsLoggingEnabled ? P2pConstants.diagnosticsSummaryMs() : 0L;
        this.diagnosticsTickDriftWarnMs = diagnosticsLoggingEnabled ? P2pConstants.diagnosticsTickDriftWarnMs() : 0L;
        this.diagnostics = diagnosticsLoggingEnabled ? new TunnelDiagnostics() : null;
    }

    void start() throws IOException {
        P2pSockets.tune(tcpSocket);

        Thread readerThread = P2pRuntime.start(side + "-udp-reader-" + connectionId, this::tcpReaderLoop);
        Thread writerThread = P2pRuntime.start(side + "-udp-writer-" + connectionId, this::tcpWriterLoop);
        maintenanceTask = scheduler.scheduleAtFixedRate(this::maintenanceTick, P2pConstants.MAINTENANCE_TICK_MS,
            P2pConstants.MAINTENANCE_TICK_MS, TimeUnit.MILLISECONDS);

        long now = System.currentTimeMillis();
        if (!initiator) {
            markOpened(now);
        } else {
            sendOpen(now);
        }

        readerThread.setUncaughtExceptionHandler((thread, throwable) -> closeFromError("reader", throwable));
        writerThread.setUncaughtExceptionHandler((thread, throwable) -> closeFromError("writer", throwable));
    }

    void handlePacket(P2pPacket packet) {
        if (closed.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        lastPacketReceivedAt = now;
        switch (packet.type()) {
            case OPEN_ACK -> markOpened(now);
            case DATA -> handleData(packet, now);
            case ACK -> processAcknowledgement(packet.acknowledgement(), packet.acknowledgementMask(), now);
            case NACK -> handleNegativeAcknowledgement(packet);
            case CLOSE -> closeWithoutNotify("remote closed");
            case OPEN -> {
                if (!initiator) {
                    sendOpenAck(now);
                }
            }
        }
    }

    void sendOpenAck() {
        sendPacket(P2pPacket.openAck(token, connectionId));
    }

    void sendOpenAck(long now) {
        sendPacket(P2pPacket.openAck(token, connectionId), now);
    }

    void retryOpen(InetSocketAddress fallbackRemoteAddress) {
        retryOpen(fallbackRemoteAddress, false);
    }

    void retryDirectOpen(InetSocketAddress fallbackRemoteAddress) {
        retryOpen(fallbackRemoteAddress, true);
    }

    private void retryOpen(InetSocketAddress fallbackRemoteAddress, boolean allowAnotherFallback) {
        if (closed.get() || fallbackRemoteAddress == null || fallbackRemoteAddress.isUnresolved()) {
            closeLocally("open fallback failed");
            return;
        }

        long now = System.currentTimeMillis();
        PacketRoute currentRoute = packetRoute;
        packetRoute = new PacketRoute(fallbackRemoteAddress, currentRoute.sender());
        openFallbackStarted = !allowAnotherFallback;
        openFallbackPending = false;
        openStartedAt = now;
        lastOpenPacketAt = 0L;
        openPacketsSent = 0;
        lastPacketReceivedAt = now;
        sendOpen(now);
    }

    boolean updateRoute(InetSocketAddress remoteAddress, PacketSender packetSender) {
        if (closed.get() || remoteAddress == null || remoteAddress.isUnresolved() || packetSender == null) {
            return false;
        }
        PacketRoute currentRoute = packetRoute;
        packetRoute = new PacketRoute(remoteAddress, packetSender);
        return !currentRoute.remoteAddress().equals(remoteAddress);
    }

    void failOpenFallback() {
        openFallbackPending = false;
        closeLocally("open fallback failed");
    }

    boolean isOpened() {
        return opened;
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

                read = coalesceTcpPayload(inputStream, buffer, read);
                long now = System.currentTimeMillis();
                maybeRestartSendWindowAfterIdle(now);
                if (diagnostics != null) {
                    diagnostics.recordTcpRead(read);
                }
                byte[] payload = Arrays.copyOf(buffer, read);
                int sequence = nextSendSequence.getAndIncrement();
                pendingSegments.put(sequence, new PendingSegment(sequence, payload));
                if (diagnostics != null) {
                    diagnostics.observePendingSegments(pendingSegments.size());
                }
                waitForPacingSlot();
                sendData(sequence, payload, now);
            }
        } catch (IOException exception) {
            closeFromError("tcp read", exception);
        }
    }

    private void tcpWriterLoop() {
        try (OutputStream outputStream = new BufferedOutputStream(
            tcpSocket.getOutputStream(),
            P2pConstants.RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES
        )) {
            int pendingBytes = 0;
            while (!closed.get()) {
                byte[] payload = inboundQueue.poll(10L, TimeUnit.MILLISECONDS);
                if (payload == null) {
                    if (pendingBytes > 0) {
                        outputStream.flush();
                        pendingBytes = 0;
                    }
                    continue;
                }

                outputStream.write(payload);
                if (diagnostics != null) {
                    diagnostics.recordTcpWrite(payload.length);
                }
                pendingBytes += payload.length;
                if (pendingBytes >= P2pConstants.RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES || inboundQueue.isEmpty()) {
                    pendingBytes = flushOutput(outputStream, pendingBytes);
                }
            }
            flushOutput(outputStream, pendingBytes);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            closeLocally("writer interrupted");
        } catch (IOException exception) {
            closeFromError("tcp write", exception);
        }
    }

    private void handleData(P2pPacket packet, long now) {
        processAcknowledgement(packet.acknowledgement(), 0, now);
        if (!opened) {
            markOpened(now);
        }

        if (diagnostics != null) {
            diagnostics.recordDataReceived(packet.payload().length);
        }

        int sequence = packet.sequence();
        if (sequence <= 0) {
            sendAcknowledgement(now);
            return;
        }

        int expected = nextExpectedSequence.get();
        if (sequence < expected) {
            if (diagnostics != null) {
                diagnostics.recordDuplicatePacket();
            }
            sendAcknowledgement(now);
            return;
        }

        if ((long) sequence - expected > P2pConstants.MAX_SEND_WINDOW_SIZE) {
            sendAcknowledgement(now);
            return;
        }

        if (sequence > expected) {
            if (diagnostics != null) {
                diagnostics.recordOutOfOrderPacket();
            }
            noteHeadOfLineBlock(expected, now);
        }

        byte[] previous = receiveBuffer.putIfAbsent(sequence, packet.payload());
        if (diagnostics != null) {
            diagnostics.observeReceiveBuffer(receiveBuffer.size());
        }
        if (previous != null) {
            if (diagnostics != null) {
                diagnostics.recordDuplicatePacket();
            }
        }

        flushReceiveBuffer();
        updateHeadOfLineBlockState(now);
        boolean needsImmediateAcknowledgement = sequence > expected || previous != null || !receiveBuffer.isEmpty();
        if (sequence > expected || !receiveBuffer.isEmpty()) {
            sendNegativeAcknowledgement(nextExpectedSequence.get(), now);
        }
        if (needsImmediateAcknowledgement) {
            sendAcknowledgement(now);
            return;
        }

        scheduleDelayedAcknowledgement(now);
    }

    private void flushReceiveBuffer() {
        while (true) {
            int expected = nextExpectedSequence.get();
            byte[] payload = receiveBuffer.remove(expected);
            if (payload == null) {
                return;
            }

            inboundQueue.offer(payload);
            if (diagnostics != null) {
                diagnostics.recordInboundQueue(payload.length, inboundQueue.size());
            }
            nextExpectedSequence.incrementAndGet();
        }
    }

    private void waitForWindow() {
        long blockedStartedAt = 0L;
        long now = System.currentTimeMillis();
        synchronized (sendWindowMonitor) {
            while (!closed.get() && pendingSegments.size() >= sendWindowSize) {
                if (lastWindowBlockedSince == 0L) {
                    lastWindowBlockedSince = now;
                    blockedStartedAt = now;
                    if (diagnostics != null) {
                        diagnostics.recordSendWindowBlock();
                    }
                }
                logWindowBlockIfNeeded(now);
                try {
                    sendWindowMonitor.wait(10L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                now = System.currentTimeMillis();
            }
        }
        if (blockedStartedAt != 0L && diagnostics != null) {
            diagnostics.observeWindowBlock(Math.max(1L, now - blockedStartedAt));
        }
        lastWindowBlockedSince = 0L;
    }

    private void processAcknowledgement(int acknowledgement, int acknowledgementMask, long now) {
        if (acknowledgement <= 0) {
            return;
        }

        boolean removed = removeAcknowledgedSegments(acknowledgement, acknowledgementMask, now);
        PendingSegment missingSegment = acknowledgementMask == 0 ? null : pendingSegments.get(acknowledgement);

        if (removed) {
            lastAcknowledgementProgressAt = now;
            synchronized (sendWindowMonitor) {
                sendWindowMonitor.notifyAll();
            }
        }

        if (acknowledgement > lastProcessedAcknowledgement) {
            lastProcessedAcknowledgement = acknowledgement;
            duplicateAcknowledgementCount = 0;
            if (missingSegment != null) {
                fastRetransmit(missingSegment, now, false);
            }
            return;
        }

        if (acknowledgement < lastProcessedAcknowledgement || acknowledgementMask == 0) {
            return;
        }

        if (missingSegment == null) {
            return;
        }

        if (diagnostics != null) {
            diagnostics.recordDuplicateAck();
        }
        duplicateAcknowledgementCount++;
        if (duplicateAcknowledgementCount < P2pConstants.FAST_RETRANSMIT_DUP_ACKS) {
            return;
        }

        duplicateAcknowledgementCount = 0;
        fastRetransmit(missingSegment, now, false);
    }

    private void maintenanceTick() {
        if (closed.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        observeMaintenanceTickGap(now);
        if (initiator && !opened) {
            if (openFallbackPending) {
                return;
            }

            if (!openFallbackStarted
                && openFallbackHandler != null
                && now - openStartedAt >= P2pConstants.DIRECT_OPEN_FALLBACK_MS) {
                openFallbackStarted = true;
                openFallbackPending = true;
                P2pRuntime.start(side + "-turn-fallback-" + connectionId, () -> openFallbackHandler.fallback(this));
                return;
            }

            if (now - openStartedAt > P2pConstants.OPEN_TIMEOUT_MS) {
                logger.debug("{} connection {} could not open UDP tunnel to {} within {} ms", side, connectionId, packetRoute.remoteAddress(), P2pConstants.OPEN_TIMEOUT_MS);
                closeLocally("open timeout");
                return;
            }

            if (now - lastOpenPacketAt >= P2pConstants.OPEN_RESEND_MS) {
                sendOpen(now);
            }
            return;
        }

        if (now - lastPacketReceivedAt > P2pConstants.CONNECTION_TIMEOUT_MS) {
            closeLocally("remote timeout");
            return;
        }

        Map.Entry<Integer, PendingSegment> firstPending = pendingSegments.firstEntry();
        if (firstPending != null) {
            PendingSegment segment = firstPending.getValue();
            if (segment != null
                && now - segment.lastSentAt >= retransmitTimeoutMs
                && now - lastAcknowledgementProgressAt >= Math.max(P2pConstants.ACK_REINFORCE_DELAY_MS,
                retransmitTimeoutMs / 2L)) {
                noteCongestionEvent(now);
                if (diagnostics != null) {
                    diagnostics.recordTimeoutRetransmission();
                }
                sendData(segment.sequence, segment.payload, now);
            }
        }

        if (!receiveBuffer.isEmpty()) {
            logHeadOfLineBlockIfNeeded(now);
            sendNegativeAcknowledgement(nextExpectedSequence.get(), now);
        }

        if (now - lastPacketSentAt >= P2pConstants.KEEP_ALIVE_MS) {
            sendAcknowledgement(now);
        }

        maybeLogDiagnostics("tick", now, false);
    }

    private void sendOpen(long now) {
        if (openPacketsSent == 0) {
            openStartedAt = now;
        }
        openPacketsSent++;
        lastOpenPacketAt = now;
        sendPacket(P2pPacket.open(token, connectionId), now);
    }

    private void sendData(int sequence, byte[] payload, long now) {
        cancelDelayedAcknowledgement();
        PendingSegment segment = pendingSegments.get(sequence);
        if (segment != null) {
            if (segment.firstSentAt == 0L) {
                segment.firstSentAt = now;
            }
            segment.lastSentAt = now;
            segment.sendCount++;
            lastPayloadSentAt = now;
        }
        if (diagnostics != null) {
            diagnostics.recordDataSent(payload.length);
        }
        sendPacket(P2pPacket.data(token, connectionId, sequence, nextExpectedSequence.get(), payload), now);
    }

    private void sendAcknowledgement(long now) {
        cancelDelayedAcknowledgement();
        int acknowledgement = nextExpectedSequence.get();
        int acknowledgementMask = acknowledgementMask(acknowledgement);
        sendAcknowledgementPacket(acknowledgement, acknowledgementMask, now);
        scheduleAcknowledgementReinforcement(acknowledgement, acknowledgementMask);
    }

    private void sendAcknowledgement() {
        sendAcknowledgement(System.currentTimeMillis());
    }

    private void sendPacket(P2pPacket packet, long now) {
        lastPacketSentAt = now;
        PacketRoute currentRoute = packetRoute;
        currentRoute.sender().send(packet, currentRoute.remoteAddress());
    }

    private void sendPacket(P2pPacket packet) {
        sendPacket(packet, System.currentTimeMillis());
    }

    private void markOpened(long now) {
        if (opened) {
            return;
        }
        opened = true;
        openFallbackPending = false;
        lastAcknowledgementProgressAt = now;
        if (initiator && openPacketsSent == 1) {
            updateRetransmitTimeout(now - openStartedAt);
        }
        openLatch.countDown();
        logger.debug("{} connection {} UDP tunnel opened with {}", side, connectionId, packetRoute.remoteAddress());
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
        cancelTask(maintenanceTask);
        cancelTask(delayedAcknowledgementTask);
        cancelTask(acknowledgementReinforcementTask);
        openLatch.countDown();

        try {
            tcpSocket.close();
        } catch (IOException ignored) {
        }

        synchronized (sendWindowMonitor) {
            sendWindowMonitor.notifyAll();
        }

        maybeLogDiagnostics(reason, System.currentTimeMillis(), true);
        inboundQueue.clear();
        pendingSegments.clear();
        receiveBuffer.clear();
        removalCallback.remove(connectionId);
        logger.debug("{} connection {} closed: {}", side, connectionId, reason);
    }

    private void handleNegativeAcknowledgement(P2pPacket packet) {
        long now = System.currentTimeMillis();
        if (diagnostics != null) {
            diagnostics.recordNegativeAckReceived();
        }
        processAcknowledgement(packet.acknowledgement(), packet.acknowledgementMask(), now);
        int missingSequence = packet.sequence();
        if (missingSequence <= 0) {
            return;
        }

        PendingSegment segment = pendingSegments.get(missingSequence);
        if (segment == null) {
            return;
        }

        fastRetransmit(segment, now, true);
    }

    private boolean removeAcknowledgedSegments(int acknowledgement, int acknowledgementMask, long now) {
        boolean removed = false;
        while (!pendingSegments.isEmpty()) {
            Map.Entry<Integer, PendingSegment> entry = pendingSegments.firstEntry();
            if (entry == null || entry.getKey() >= acknowledgement) {
                break;
            }

            PendingSegment segment = pendingSegments.remove(entry.getKey());
            if (segment == null) {
                continue;
            }

            recordRoundTripSample(segment, now);
            removed = true;
        }

        if (acknowledgementMask == 0) {
            return removed;
        }

        for (int offset = 0; offset < P2pConstants.SELECTIVE_ACK_BITS; offset++) {
            if (((acknowledgementMask >>> offset) & 1) == 0) {
                continue;
            }

            int sequence = acknowledgement + offset + 1;
            PendingSegment segment = pendingSegments.remove(sequence);
            if (segment == null) {
                continue;
            }

            recordRoundTripSample(segment, now);
            if (diagnostics != null) {
                diagnostics.recordSelectiveAck();
            }
            removed = true;
        }

        return removed;
    }

    private int acknowledgementMask(int acknowledgement) {
        int mask = 0;
        for (int offset = 0; offset < P2pConstants.SELECTIVE_ACK_BITS; offset++) {
            int sequence = acknowledgement + offset + 1;
            if (receiveBuffer.containsKey(sequence)) {
                mask |= 1 << offset;
            }
        }
        return mask;
    }

    private void sendNegativeAcknowledgement(int missingSequence, long now) {
        if (missingSequence <= 0 || receiveBuffer.isEmpty()) {
            return;
        }

        long repeatMs = Math.max(P2pConstants.NEGATIVE_ACK_REPEAT_MS, fastRetransmitGuardMs() / 2L);
        if (missingSequence == lastNegativeAcknowledgementSequence
            && now - lastNegativeAcknowledgementAt < repeatMs) {
            return;
        }

        lastNegativeAcknowledgementSequence = missingSequence;
        lastNegativeAcknowledgementAt = now;
        if (diagnostics != null) {
            diagnostics.recordNegativeAckSent();
        }
        sendPacket(P2pPacket.nack(token, connectionId, missingSequence, nextExpectedSequence.get(),
            acknowledgementMask(nextExpectedSequence.get())), now);
    }

    private void fastRetransmit(PendingSegment segment, long now, boolean negativeAcknowledgement) {
        if (now - segment.lastFastRetransmitAt < fastRetransmitGuardMs()) {
            return;
        }

        segment.lastFastRetransmitAt = now;
        if (diagnostics != null) {
            diagnostics.recordFastRetransmission(negativeAcknowledgement);
        }
        sendData(segment.sequence, segment.payload, now);
    }

    private long fastRetransmitGuardMs() {
        if (smoothedRoundTripTimeMs < 0.0D) {
            return P2pConstants.DEFAULT_FAST_RETRANSMIT_GUARD_MS;
        }

        long guardMs = Math.round(smoothedRoundTripTimeMs + (roundTripVariationMs * 2.0D));
        return Math.max(P2pConstants.MIN_FAST_RETRANSMIT_GUARD_MS,
            Math.min(P2pConstants.MAX_FAST_RETRANSMIT_GUARD_MS, guardMs));
    }

    private void recordRoundTripSample(PendingSegment segment, long now) {
        if (segment.sendCount != 1 || segment.firstSentAt <= 0L) {
            return;
        }

        maybeGrowSendWindow();
        updateRetransmitTimeout(now - segment.firstSentAt);
    }

    private void updateRetransmitTimeout(long sampleMs) {
        if (sampleMs <= 0L) {
            return;
        }

        if (smoothedRoundTripTimeMs < 0.0D) {
            smoothedRoundTripTimeMs = sampleMs;
            roundTripVariationMs = sampleMs / 2.0D;
        } else {
            roundTripVariationMs = (0.75D * roundTripVariationMs)
                + (0.25D * Math.abs(smoothedRoundTripTimeMs - sampleMs));
            smoothedRoundTripTimeMs = (0.875D * smoothedRoundTripTimeMs)
                + (0.125D * sampleMs);
        }

        long computed = Math.round(smoothedRoundTripTimeMs
            + Math.max(P2pConstants.MAINTENANCE_TICK_MS * 2.0D,
            (roundTripVariationMs * 4.0D) + P2pConstants.ACK_REINFORCE_DELAY_MS));
        retransmitTimeoutMs = Math.max(P2pConstants.MIN_RESEND_MS,
            Math.min(P2pConstants.MAX_RESEND_MS, computed));
    }

    private void noteHeadOfLineBlock(int missingSequence, long now) {
        if (missingSequence <= 0) {
            return;
        }

        if (headOfLineMissingSequence != missingSequence) {
            headOfLineMissingSequence = missingSequence;
            headOfLineBlockedSince = now;
            lastHeadOfLineWarningAt = 0L;
            return;
        }

        if (headOfLineBlockedSince == 0L) {
            headOfLineBlockedSince = now;
        }
    }

    private void updateHeadOfLineBlockState(long now) {
        if (receiveBuffer.isEmpty()) {
            headOfLineMissingSequence = 0;
            headOfLineBlockedSince = 0L;
            lastHeadOfLineWarningAt = 0L;
            return;
        }

        noteHeadOfLineBlock(nextExpectedSequence.get(), now);
    }

    private void logHeadOfLineBlockIfNeeded(long now) {
        if (!diagnosticsLoggingEnabled) {
            return;
        }

        if (headOfLineBlockedSince == 0L || now - headOfLineBlockedSince < P2pConstants.HEAD_OF_LINE_WARN_MS) {
            return;
        }

        if (now - lastHeadOfLineWarningAt < diagnosticsSummaryMs) {
            return;
        }

        lastHeadOfLineWarningAt = now;
        if (diagnostics != null) {
            diagnostics.observeHeadOfLineBlock(now - headOfLineBlockedSince);
        }
        logger.warn("{} connection {} head-of-line block {} ms on seq {} buffered={} pending={} rto={}ms srtt={}ms remote={}",
            side, connectionId, now - headOfLineBlockedSince, headOfLineMissingSequence, bufferedRange(),
            pendingSegments.size(), retransmitTimeoutMs, roundTripMetric(smoothedRoundTripTimeMs), packetRoute.remoteAddress());
        maybeLogDiagnostics("head-of-line", now, true);
    }

    private void logWindowBlockIfNeeded(long now) {
        if (!diagnosticsLoggingEnabled) {
            return;
        }

        if (lastWindowBlockedSince == 0L || now - lastWindowBlockedSince < P2pConstants.WINDOW_STALL_WARN_MS) {
            return;
        }

        if (now - lastWindowWarningAt < diagnosticsSummaryMs) {
            return;
        }

        lastWindowWarningAt = now;
        if (diagnostics != null) {
            diagnostics.observeWindowBlock(now - lastWindowBlockedSince);
        }
        logger.warn("{} connection {} send window blocked {} ms pending={} window={} ssthresh={} firstPending={} nextExpected={} buffered={} rto={}ms remote={}",
            side, connectionId, now - lastWindowBlockedSince, pendingSegments.size(), sendWindowSize,
            slowStartThreshold, firstPendingSequence(), nextExpectedSequence.get(), bufferedRange(),
            retransmitTimeoutMs, packetRoute.remoteAddress());
        maybeLogDiagnostics("window-block", now, true);
    }

    private void maybeLogDiagnostics(String trigger, long now, boolean forced) {
        if (diagnostics == null) {
            return;
        }

        diagnostics.maybeLogDiagnostics(
            logger, side, connectionId, trigger, now, forced, diagnosticsSummaryMs,
            packetRoute.remoteAddress(), pendingSegments.size(), sendWindowSize,
            slowStartThreshold, nextExpectedSequence.get(), bufferedRange(),
            inboundQueue.size(), retransmitTimeoutMs, smoothedRoundTripTimeMs,
            roundTripVariationMs, pendingSegments.isEmpty() && receiveBuffer.isEmpty()
        );
    }

    private int flushOutput(OutputStream outputStream, int pendingBytes) throws IOException {
        if (pendingBytes <= 0) {
            return 0;
        }

        outputStream.flush();
        if (diagnostics != null) {
            diagnostics.recordTcpFlush(pendingBytes);
        }
        return 0;
    }

    private void observeMaintenanceTickGap(long now) {
        if (!diagnosticsLoggingEnabled) {
            lastMaintenanceTickAt = now;
            return;
        }

        long previous = lastMaintenanceTickAt;
        lastMaintenanceTickAt = now;
        if (previous == 0L) {
            return;
        }

        long gapMs = Math.max(0L, now - previous);
        if (diagnostics != null) {
            diagnostics.observeMaintenanceTickGap(gapMs);
        }
        long lateMs = Math.max(0L, gapMs - P2pConstants.MAINTENANCE_TICK_MS);
        if (lateMs < Math.max(2L, P2pConstants.MAINTENANCE_TICK_MS / 2L)) {
            return;
        }

        if (diagnostics != null) {
            diagnostics.recordMaintenanceTickDrift(gapMs, lateMs);
        }
        long warnIntervalMs = diagnosticsSummaryMs;
        if (gapMs < diagnosticsTickDriftWarnMs
            || now - lastMaintenanceDriftWarningAt < warnIntervalMs) {
            return;
        }

        lastMaintenanceDriftWarningAt = now;
        logger.warn("{} connection {} maintenance tick drift {} ms pending={} buffered={} inboundQueue={} remote={}",
            side, connectionId, gapMs, pendingSegments.size(), bufferedRange(), inboundQueue.size(), packetRoute.remoteAddress());
        maybeLogDiagnostics("tick-drift", now, true);
    }

    private record PacketRoute(InetSocketAddress remoteAddress, PacketSender sender) {
    }

    private String bufferedRange() {
        if (receiveBuffer.isEmpty()) {
            return "-";
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Integer sequence : receiveBuffer.keySet()) {
            if (sequence == null) {
                continue;
            }
            if (sequence < min) {
                min = sequence;
            }
            if (sequence > max) {
                max = sequence;
            }
        }

        if (min == Integer.MAX_VALUE) {
            return "-";
        }

        return min == max ? Integer.toString(min) : min + "-" + max;
    }

    private int firstPendingSequence() {
        Map.Entry<Integer, PendingSegment> firstEntry = pendingSegments.firstEntry();
        return firstEntry == null ? 0 : firstEntry.getKey();
    }

    private void maybeGrowSendWindow() {
        if (sendWindowSize >= P2pConstants.MAX_SEND_WINDOW_SIZE) {
            acknowledgementsSinceWindowIncrease = 0;
            return;
        }

        if (sendWindowSize < slowStartThreshold) {
            sendWindowSize++;
            if (diagnostics != null) {
                diagnostics.recordSendWindowGrowth();
            }
            return;
        }

        acknowledgementsSinceWindowIncrease++;
        if (acknowledgementsSinceWindowIncrease < Math.max(1, sendWindowSize)) {
            return;
        }

        acknowledgementsSinceWindowIncrease = 0;
        sendWindowSize++;
        if (diagnostics != null) {
            diagnostics.recordSendWindowGrowth();
        }
    }

    private void noteCongestionEvent(long now) {
        long guardMs = Math.max(P2pConstants.MIN_RESEND_MS, retransmitTimeoutMs);
        if (now - lastCongestionEventAt < guardMs) {
            return;
        }

        lastCongestionEventAt = now;
        slowStartThreshold = Math.max(P2pConstants.MIN_SEND_WINDOW_SIZE, sendWindowSize / 2);
        sendWindowSize = Math.max(P2pConstants.MIN_SEND_WINDOW_SIZE, slowStartThreshold);
        acknowledgementsSinceWindowIncrease = 0;
        resetPacingBudget();
        if (diagnostics != null) {
            diagnostics.recordSendWindowLoss();
        }
    }

    private void maybeRestartSendWindowAfterIdle(long now) {
        long idleThreshold = Math.max(P2pConstants.IDLE_RESTART_MIN_MS, retransmitTimeoutMs);
        if (now - lastPayloadSentAt <= idleThreshold) {
            return;
        }

        int restartWindow = Math.max(P2pConstants.MIN_SEND_WINDOW_SIZE,
            Math.min(P2pConstants.INITIAL_SEND_WINDOW_SIZE, sendWindowSize));
        if (restartWindow == sendWindowSize) {
            return;
        }

        sendWindowSize = restartWindow;
        acknowledgementsSinceWindowIncrease = 0;
        resetPacingBudget();
        if (diagnostics != null) {
            diagnostics.recordIdleRestart();
        }
    }

    private void waitForPacingSlot() {
        long intervalNanos = pacingIntervalNanos();
        if (intervalNanos <= 0L) {
            return;
        }

        long now = System.nanoTime();
        if (nextPayloadSendAtNanos == 0L || now > nextPayloadSendAtNanos + (intervalNanos * 16L)) {
            nextPayloadSendAtNanos = now;
            pacingBurstBudget = Math.min(P2pConstants.PACING_BURST_PACKETS, Math.max(1, sendWindowSize / 2));
        }

        if (pacingBurstBudget > 0) {
            pacingBurstBudget--;
            return;
        }

        long waitNanos = nextPayloadSendAtNanos - now;
        if (waitNanos > 0L) {
            if (diagnostics != null) {
                diagnostics.recordPacingWait(waitNanos);
            }
            LockSupport.parkNanos(waitNanos);
            now = System.nanoTime();
        }

        nextPayloadSendAtNanos = Math.max(nextPayloadSendAtNanos, now) + intervalNanos;
    }

    private long pacingIntervalNanos() {
        if (!opened) {
            return 0L;
        }

        double baseRttMs = smoothedRoundTripTimeMs > 0.0D
            ? smoothedRoundTripTimeMs
            : Math.max(4.0D, retransmitTimeoutMs / 4.0D);
        long intervalNanos = Math.round((baseRttMs * 1_000_000D) / Math.max(1, sendWindowSize));
        return Math.max(P2pConstants.MIN_PACING_INTERVAL_NANOS,
            Math.min(P2pConstants.MAX_PACING_INTERVAL_NANOS, intervalNanos));
    }

    private void resetPacingBudget() {
        nextPayloadSendAtNanos = 0L;
        pacingBurstBudget = Math.min(P2pConstants.PACING_BURST_PACKETS, Math.max(1, sendWindowSize / 2));
    }

    private int coalesceTcpPayload(InputStream inputStream, byte[] buffer, int initialRead) throws IOException {
        int read = initialRead;
        while (read < buffer.length && inputStream.available() > 0) {
            int additional = inputStream.read(buffer, read, buffer.length - read);
            if (additional <= 0) {
                break;
            }
            read += additional;
        }
        return read;
    }

    private void sendAcknowledgementPacket(int acknowledgement, int acknowledgementMask) {
        sendAcknowledgementPacket(acknowledgement, acknowledgementMask, System.currentTimeMillis());
    }

    private void sendAcknowledgementPacket(int acknowledgement, int acknowledgementMask, long now) {
        if (diagnostics != null) {
            diagnostics.recordAckSent();
        }
        receivedPacketsSinceLastAcknowledgement = 0;
        lastAcknowledgementPacketAt = now;
        lastAcknowledgementSent = acknowledgement;
        lastAcknowledgementMaskSent = acknowledgementMask;
        sendPacket(P2pPacket.ack(token, connectionId, acknowledgement, acknowledgementMask), now);
    }

    private void scheduleDelayedAcknowledgement(long now) {
        receivedPacketsSinceLastAcknowledgement++;
        if (receivedPacketsSinceLastAcknowledgement >= P2pConstants.DELAYED_ACK_PACKET_THRESHOLD
            || now - lastAcknowledgementPacketAt >= P2pConstants.DELAYED_ACK_MS) {
            sendAcknowledgement(now);
            return;
        }

        ScheduledFuture<?> existing = delayedAcknowledgementTask;
        if (existing != null && !existing.isDone()) {
            return;
        }

        delayedAcknowledgementTask = scheduler.schedule(() -> {
            delayedAcknowledgementTask = null;
            if (closed.get()) {
                return;
            }

            if (diagnostics != null) {
                diagnostics.recordDelayedAckSent();
            }
            sendAcknowledgement();
        }, P2pConstants.DELAYED_ACK_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelDelayedAcknowledgement() {
        ScheduledFuture<?> task = delayedAcknowledgementTask;
        delayedAcknowledgementTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void scheduleAcknowledgementReinforcement(int acknowledgement, int acknowledgementMask) {
        if (!opened || closed.get() || !pendingSegments.isEmpty()) {
            return;
        }

        ScheduledFuture<?> existing = acknowledgementReinforcementTask;
        if (existing != null && !existing.isDone()) {
            return;
        }

        acknowledgementReinforcementTask = scheduler.schedule(() -> {
            if (closed.get()) {
                return;
            }

            if (lastAcknowledgementSent != acknowledgement || lastAcknowledgementMaskSent != acknowledgementMask) {
                return;
            }

            if (diagnostics != null) {
                diagnostics.recordAckReinforcementSent();
            }
            sendAcknowledgementPacket(acknowledgement, acknowledgementMask);
        }, P2pConstants.ACK_REINFORCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static String roundTripMetric(double metric) {
        return metric < 0.0D ? "-" : Long.toString(Math.round(metric));
    }

    private static final class PendingSegment {
        private final int sequence;
        private final byte[] payload;
        private volatile long firstSentAt;
        private volatile long lastSentAt;
        private volatile long lastFastRetransmitAt;
        private volatile int sendCount;

        private PendingSegment(int sequence, byte[] payload) {
            this.sequence = sequence;
            this.payload = payload;
            this.lastSentAt = 0L;
        }
    }
}
