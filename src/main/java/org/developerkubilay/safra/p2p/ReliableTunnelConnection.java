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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

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
    private final boolean diagnosticsLoggingEnabled;
    private final long diagnosticsSummaryMs;
    private final long diagnosticsTickDriftWarnMs;
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger nextSendSequence = new AtomicInteger(1);
    private final AtomicInteger nextExpectedSequence = new AtomicInteger(1);
    private final Object sendWindowMonitor = new Object();
    private final ConcurrentNavigableMap<Integer, PendingSegment> pendingSegments = new ConcurrentSkipListMap<>();
    private final Map<Integer, byte[]> receiveBuffer = new ConcurrentHashMap<>();
    private final BlockingQueue<byte[]> inboundQueue = new LinkedBlockingQueue<>();
    private final AtomicLong outOfOrderPackets = new AtomicLong();
    private final AtomicLong duplicatePackets = new AtomicLong();
    private final AtomicLong selectiveAcknowledgements = new AtomicLong();
    private final AtomicLong duplicateAcknowledgements = new AtomicLong();
    private final AtomicLong timeoutRetransmissions = new AtomicLong();
    private final AtomicLong duplicateAckRetransmissions = new AtomicLong();
    private final AtomicLong negativeAcknowledgementRetransmissions = new AtomicLong();
    private final AtomicLong negativeAcknowledgementsSent = new AtomicLong();
    private final AtomicLong negativeAcknowledgementsReceived = new AtomicLong();
    private final AtomicLong acknowledgementPacketsSent = new AtomicLong();
    private final AtomicLong acknowledgementReinforcementsSent = new AtomicLong();
    private final AtomicLong delayedAcknowledgementsSent = new AtomicLong();
    private final AtomicLong sendWindowBlocks = new AtomicLong();
    private final AtomicLong sendWindowGrowthEvents = new AtomicLong();
    private final AtomicLong sendWindowLossEvents = new AtomicLong();
    private final AtomicLong maintenanceTickDrifts = new AtomicLong();
    private final AtomicLong maintenanceTickLateMs = new AtomicLong();
    private final AtomicLong idleRestartEvents = new AtomicLong();
    private final AtomicLong pacingWaitEvents = new AtomicLong();
    private final AtomicLong pacingWaitNanos = new AtomicLong();
    private final AtomicLong dataPacketsSent = new AtomicLong();
    private final AtomicLong dataBytesSent = new AtomicLong();
    private final AtomicLong dataPacketsReceived = new AtomicLong();
    private final AtomicLong dataBytesReceived = new AtomicLong();
    private final AtomicLong tcpReadBytes = new AtomicLong();
    private final AtomicLong tcpWriteBytes = new AtomicLong();
    private final AtomicLong tcpFlushes = new AtomicLong();
    private final AtomicLong tcpFlushBytes = new AtomicLong();
    private final AtomicLong inboundQueueBytes = new AtomicLong();
    private final AtomicLong peakPendingSegments = new AtomicLong();
    private final AtomicLong peakReceiveBuffer = new AtomicLong();
    private final AtomicLong peakInboundQueueDepth = new AtomicLong();
    private final AtomicLong peakInboundQueueBytes = new AtomicLong();
    private final AtomicLong peakPacingWaitMs = new AtomicLong();
    private final AtomicLong peakHeadOfLineBlockMs = new AtomicLong();
    private final AtomicLong peakWindowBlockMs = new AtomicLong();
    private final AtomicLong peakMaintenanceTickGapMs = new AtomicLong();
    private final AtomicLong microBatchHits = new AtomicLong();
    private final AtomicLong microBatchStartBytes = new AtomicLong();
    private final AtomicLong microBatchEndBytes = new AtomicLong();
    private final AtomicLong microBatchDeadlineExits = new AtomicLong();
    private final AtomicLong microBatchThresholdExits = new AtomicLong();

    private volatile boolean opened;
    private volatile long lastPacketReceivedAt = System.currentTimeMillis();
    private volatile long lastPacketSentAt = System.currentTimeMillis();
    private volatile long lastOpenPacketAt = 0L;
    private volatile long openStartedAt = System.currentTimeMillis();
    private volatile int openPacketsSent;
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
    private volatile long lastInboundQueueWarningAt;
    private volatile long lastDiagnosticsLogAt;
    private volatile long lastDiagnosticsCounterTotal;
    private volatile long lastSummaryAt;
    private volatile long lastSummaryDataPacketsSent;
    private volatile long lastSummaryDataBytesSent;
    private volatile long lastSummaryDataPacketsReceived;
    private volatile long lastSummaryDataBytesReceived;
    private volatile long lastSummaryTcpFlushes;
    private volatile long lastSummaryTcpFlushBytes;
    private volatile long lastSummaryTimeoutRetransmissions;
    private volatile long lastSummaryDuplicateAckRetransmissions;
    private volatile long lastSummaryNegativeAcknowledgementRetransmissions;
    private volatile long lastSummaryAcknowledgementPacketsSent;
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
    private volatile int adaptiveMicroBatchThresholdBytes = P2pConstants.MICRO_BATCH_THRESHOLD_BYTES;
    private volatile int microBatchWarmupSamples;
    private volatile long microBatchWarmupBytes;

    private enum BbrState {
        STARTUP, DRAIN, PROBE_BW, PROBE_RTT
    }

    private static final double[] PACING_GAIN_CYCLE = {1.25D, 0.75D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D};

    private volatile BbrState bbrState = BbrState.STARTUP;
    private volatile int pacingGainCycleIndex = 0;
    private volatile long pacingGainCycleStartTimeNanos = 0L;
    private volatile long probeRttStartTimeMs = 0L;
    private volatile long lastProbeRttTimeMs = 0L;
    private volatile long bbrRoundCount = 0L;
    private volatile int bbrRoundStartSeq = 0;
    private volatile long priorMaxBandwidth = 0L;
    private volatile int bandwidthPlateauCount = 0;
    private volatile long deliveredBytes;
    private volatile long deliveredTimeNanos;
    private volatile long firstSentTimeNanos;
    private volatile boolean appLimited;
    private volatile long deliveryRateBytesPerSecond;
    private volatile long maxBandwidthBytesPerSecond;
    private volatile double minRttMs = -1.0D;
    private volatile long minRttTimestamp;
    private volatile long lastReceiveTimeNanos = 0L;
    private volatile long receiveIntervalNanos = 0L;
    private volatile long nextReceiveWriteAtNanos = 0L;

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
        this.diagnosticsLoggingEnabled = P2pConstants.diagnosticsEnabled();
        this.diagnosticsSummaryMs = diagnosticsLoggingEnabled ? P2pConstants.diagnosticsSummaryMs() : 0L;
        this.diagnosticsTickDriftWarnMs = diagnosticsLoggingEnabled ? P2pConstants.diagnosticsTickDriftWarnMs() : 0L;
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
            case OPEN_ACK:
                markOpened(now);
                break;
            case DATA:
                handleData(packet, now);
                break;
            case ACK:
                processAcknowledgement(packet.acknowledgement(), packet.acknowledgementMask(), now);
                break;
            case NACK:
                handleNegativeAcknowledgement(packet);
                break;
            case CLOSE:
                closeWithoutNotify("remote closed");
                break;
            case OPEN:
                if (!initiator) {
                    sendOpenAck(now);
                }
                break;
        }
    }

    void sendOpenAck() {
        sendPacket(P2pPacket.openAck(token, connectionId));
    }

    void sendOpenAck(long now) {
        sendPacket(P2pPacket.openAck(token, connectionId), now);
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
                if (inputStream.available() <= 0) {
                    Thread.yield();
                    if (inputStream.available() <= 0) {
                        LockSupport.parkNanos(200_000L);
                        continue;
                    }
                }
                int read = inputStream.read(buffer);
                if (read < 0) {
                    closeLocally("tcp eof");
                    return;
                }

                read = coalesceTcpPayload(inputStream, buffer, read);
                long now = System.currentTimeMillis();
                maybeRestartSendWindowAfterIdle(now);
                addDiagnosticCounter(tcpReadBytes, read);
                byte[] payload = Arrays.copyOf(buffer, read);
                int sequence = nextSendSequence.getAndIncrement();
                pendingSegments.put(sequence, new PendingSegment(sequence, payload));
                observePeak(peakPendingSegments, pendingSegments.size());
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

                updateReceiveRate(System.nanoTime());
                waitForReceivePacingSlot();

                outputStream.write(payload);
                addDiagnosticCounter(tcpWriteBytes, payload.length);
                addDiagnosticCounter(inboundQueueBytes, -payload.length);
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

        incrementDiagnosticCounter(dataPacketsReceived);
        addDiagnosticCounter(dataBytesReceived, packet.payload().length);

        int sequence = packet.sequence();
        if (sequence <= 0) {
            sendAcknowledgement(now);
            return;
        }

        int expected = nextExpectedSequence.get();
        if (sequence < expected) {
            incrementDiagnosticCounter(duplicatePackets);
            sendAcknowledgement(now);
            return;
        }

        if (sequence > expected) {
            incrementDiagnosticCounter(outOfOrderPackets);
            noteHeadOfLineBlock(expected, now);
        }

        byte[] previous = receiveBuffer.putIfAbsent(sequence, packet.payload());
        observePeak(peakReceiveBuffer, receiveBuffer.size());
        if (previous != null) {
            incrementDiagnosticCounter(duplicatePackets);
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
            addDiagnosticCounter(inboundQueueBytes, payload.length);
            observePeak(peakInboundQueueDepth, inboundQueue.size());
            observePeak(peakInboundQueueBytes, inboundQueueBytes.get());
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
                    incrementDiagnosticCounter(sendWindowBlocks);
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
        if (blockedStartedAt != 0L) {
            observePeak(peakWindowBlockMs, Math.max(1L, now - blockedStartedAt));
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

        incrementDiagnosticCounter(duplicateAcknowledgements);
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
            if (now - openStartedAt > P2pConstants.OPEN_TIMEOUT_MS) {
                logger.debug("{} connection {} could not open UDP tunnel to {} within {} ms", side, connectionId, remoteAddress, P2pConstants.OPEN_TIMEOUT_MS);
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
                incrementDiagnosticCounter(timeoutRetransmissions);
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
        incrementDiagnosticCounter(dataPacketsSent);
        addDiagnosticCounter(dataBytesSent, payload.length);
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
        packetSender.send(packet, remoteAddress);
    }

    private void sendPacket(P2pPacket packet) {
        sendPacket(packet, System.currentTimeMillis());
    }

    private void markOpened(long now) {
        if (opened) {
            return;
        }
        opened = true;
        lastAcknowledgementProgressAt = now;
        if (initiator && openPacketsSent == 1) {
            updateRetransmitTimeout(now - openStartedAt);
        }
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
        incrementDiagnosticCounter(negativeAcknowledgementsReceived);
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
            incrementDiagnosticCounter(selectiveAcknowledgements);
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

        if (missingSequence == lastNegativeAcknowledgementSequence
            && now - lastNegativeAcknowledgementAt < P2pConstants.NEGATIVE_ACK_REPEAT_MS) {
            return;
        }

        lastNegativeAcknowledgementSequence = missingSequence;
        lastNegativeAcknowledgementAt = now;
        incrementDiagnosticCounter(negativeAcknowledgementsSent);
        sendPacket(P2pPacket.nack(token, connectionId, missingSequence, nextExpectedSequence.get(),
            acknowledgementMask(nextExpectedSequence.get())), now);
    }

    private void fastRetransmit(PendingSegment segment, long now, boolean negativeAcknowledgement) {
        if (now - segment.lastFastRetransmitAt < P2pConstants.FAST_RETRANSMIT_GUARD_MS) {
            return;
        }

        segment.lastFastRetransmitAt = now;
        if (negativeAcknowledgement) {
            incrementDiagnosticCounter(negativeAcknowledgementRetransmissions);
        } else {
            incrementDiagnosticCounter(duplicateAckRetransmissions);
        }
        sendData(segment.sequence, segment.payload, now);
    }

    private void recordRoundTripSample(PendingSegment segment, long now) {
        if (segment.sendCount != 1 || segment.firstSentAt <= 0L) {
            return;
        }

        maybeGrowSendWindow();
        updateRetransmitTimeout(now - segment.firstSentAt);
        updateBandwidthEstimate(segment.sequence, segment.payload.length, System.nanoTime());
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

        if (minRttMs < 0.0D || sampleMs < minRttMs) {
            minRttMs = sampleMs;
            minRttTimestamp = System.currentTimeMillis();
        }
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
        observePeak(peakHeadOfLineBlockMs, now - headOfLineBlockedSince);
        logger.warn("{} connection {} head-of-line block {} ms on seq {} buffered={} pending={} rto={}ms srtt={}ms remote={}",
            side, connectionId, now - headOfLineBlockedSince, headOfLineMissingSequence, bufferedRange(),
            pendingSegments.size(), retransmitTimeoutMs, roundTripMetric(smoothedRoundTripTimeMs), remoteAddress);
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
        observePeak(peakWindowBlockMs, now - lastWindowBlockedSince);
        logger.warn("{} connection {} send window blocked {} ms pending={} window={} ssthresh={} firstPending={} nextExpected={} buffered={} rto={}ms remote={}",
            side, connectionId, now - lastWindowBlockedSince, pendingSegments.size(), sendWindowSize,
            slowStartThreshold, firstPendingSequence(), nextExpectedSequence.get(), bufferedRange(),
            retransmitTimeoutMs, remoteAddress);
        maybeLogDiagnostics("window-block", now, true);
    }

    private void maybeLogDiagnostics(String trigger, long now, boolean forced) {
        if (!diagnosticsLoggingEnabled) {
            return;
        }

        logInboundQueueBacklogIfNeeded(now, forced);
        long counterTotal = diagnosticsCounterTotal();
        long summaryIntervalMs = diagnosticsSummaryMs;
        if (!forced) {
            if (now - lastDiagnosticsLogAt < summaryIntervalMs) {
                return;
            }
            if (counterTotal == lastDiagnosticsCounterTotal && pendingSegments.isEmpty() && receiveBuffer.isEmpty()) {
                return;
            }
        }

        long intervalMs = lastSummaryAt == 0L ? summaryIntervalMs : Math.max(1L, now - lastSummaryAt);
        long totalDataPacketsSent = dataPacketsSent.get();
        long totalDataBytesSent = dataBytesSent.get();
        long totalDataPacketsReceived = dataPacketsReceived.get();
        long totalDataBytesReceived = dataBytesReceived.get();
        long totalTcpFlushes = tcpFlushes.get();
        long totalTcpFlushBytes = tcpFlushBytes.get();
        long totalTimeoutRetransmissions = timeoutRetransmissions.get();
        long totalDuplicateAckRetransmissions = duplicateAckRetransmissions.get();
        long totalNegativeAcknowledgementRetransmissions = negativeAcknowledgementRetransmissions.get();
        long totalAcknowledgementPacketsSent = acknowledgementPacketsSent.get();
        long txPacketsDelta = totalDataPacketsSent - lastSummaryDataPacketsSent;
        long txBytesDelta = totalDataBytesSent - lastSummaryDataBytesSent;
        long rxPacketsDelta = totalDataPacketsReceived - lastSummaryDataPacketsReceived;
        long rxBytesDelta = totalDataBytesReceived - lastSummaryDataBytesReceived;
        long flushesDelta = totalTcpFlushes - lastSummaryTcpFlushes;
        long flushBytesDelta = totalTcpFlushBytes - lastSummaryTcpFlushBytes;
        long timeoutRetransmissionsDelta = totalTimeoutRetransmissions - lastSummaryTimeoutRetransmissions;
        long duplicateAckRetransmissionsDelta = totalDuplicateAckRetransmissions - lastSummaryDuplicateAckRetransmissions;
        long negativeAcknowledgementRetransmissionsDelta =
            totalNegativeAcknowledgementRetransmissions - lastSummaryNegativeAcknowledgementRetransmissions;
        long acknowledgementPacketsSentDelta = totalAcknowledgementPacketsSent - lastSummaryAcknowledgementPacketsSent;
        lastDiagnosticsLogAt = now;
        lastDiagnosticsCounterTotal = counterTotal;
        lastSummaryAt = now;
        lastSummaryDataPacketsSent = totalDataPacketsSent;
        lastSummaryDataBytesSent = totalDataBytesSent;
        lastSummaryDataPacketsReceived = totalDataPacketsReceived;
        lastSummaryDataBytesReceived = totalDataBytesReceived;
        lastSummaryTcpFlushes = totalTcpFlushes;
        lastSummaryTcpFlushBytes = totalTcpFlushBytes;
        lastSummaryTimeoutRetransmissions = totalTimeoutRetransmissions;
        lastSummaryDuplicateAckRetransmissions = totalDuplicateAckRetransmissions;
        lastSummaryNegativeAcknowledgementRetransmissions = totalNegativeAcknowledgementRetransmissions;
        lastSummaryAcknowledgementPacketsSent = totalAcknowledgementPacketsSent;
        StringBuilder diag = new StringBuilder(640);
        diag.append(side).append(" connection ").append(connectionId)
            .append(" diag=").append(trigger)
            .append(" remote=").append(remoteAddress)
            .append(" pending=").append(pendingSegments.size())
            .append(" pendingPeak=").append(peakPendingSegments.get())
            .append(" window=").append(sendWindowSize)
            .append(" ssthresh=").append(slowStartThreshold)
            .append(" expected=").append(nextExpectedSequence.get())
            .append(" buffered=").append(bufferedRange())
            .append(" bufferedPeak=").append(peakReceiveBuffer.get())
            .append(" inboundQueue=").append(inboundQueue.size())
            .append(" inboundQueuePeak=").append(peakInboundQueueDepth.get())
            .append(" inboundBytes=").append(inboundQueueBytes.get())
            .append(" inboundBytesPeak=").append(peakInboundQueueBytes.get())
            .append(" rto=").append(retransmitTimeoutMs).append("ms")
            .append(" srtt=").append(roundTripMetric(smoothedRoundTripTimeMs)).append("ms")
            .append(" rttvar=").append(roundTripMetric(roundTripVariationMs)).append("ms")
            .append(" txKbps=").append(kiloBitsPerSecond(txBytesDelta, intervalMs))
            .append(" rxKbps=").append(kiloBitsPerSecond(rxBytesDelta, intervalMs))
            .append(" txPps=").append(eventsPerSecond(txPacketsDelta, intervalMs))
            .append(" rxPps=").append(eventsPerSecond(rxPacketsDelta, intervalMs))
            .append(" flushes=").append(flushesDelta)
            .append(" flushKb=").append(kiloBytes(flushBytesDelta))
            .append(" ackPps=").append(eventsPerSecond(acknowledgementPacketsSentDelta, intervalMs))
            .append(" retTimeout=").append(timeoutRetransmissionsDelta)
            .append(" retDupAck=").append(duplicateAckRetransmissionsDelta)
            .append(" retNack=").append(negativeAcknowledgementRetransmissionsDelta)
            .append(" outOfOrder=").append(outOfOrderPackets.get())
            .append(" dupData=").append(duplicatePackets.get())
            .append(" sack=").append(selectiveAcknowledgements.get())
            .append(" dupAck=").append(duplicateAcknowledgements.get())
            .append(" nackSent=").append(negativeAcknowledgementsSent.get())
            .append(" nackRecv=").append(negativeAcknowledgementsReceived.get())
            .append(" windowBlocks=").append(sendWindowBlocks.get())
            .append(" winGrow=").append(sendWindowGrowthEvents.get())
            .append(" winLoss=").append(sendWindowLossEvents.get())
            .append(" idleRestart=").append(idleRestartEvents.get())
            .append(" paceWaits=").append(pacingWaitEvents.get())
            .append(" paceMs=").append(pacingWaitNanos.get() / 1_000_000L)
            .append(" paceMaxMs=").append(peakPacingWaitMs.get())
            .append(" holMaxMs=").append(peakHeadOfLineBlockMs.get())
            .append(" winBlockMaxMs=").append(peakWindowBlockMs.get())
            .append(" tickDrifts=").append(maintenanceTickDrifts.get())
            .append(" tickLateMs=").append(maintenanceTickLateMs.get())
            .append(" tickGapMaxMs=").append(peakMaintenanceTickGapMs.get())
            .append(" tcpReadKb=").append(kiloBytes(tcpReadBytes.get()))
            .append(" tcpWriteKb=").append(kiloBytes(tcpWriteBytes.get()))
            .append(" mbHits=").append(microBatchHits.get())
            .append(" mbAvgStart=").append(microBatchAverageStartBytes())
            .append(" mbAvgEnd=").append(microBatchAverageEndBytes())
            .append(" mbDeadline=").append(microBatchDeadlineExits.get())
            .append(" mbThreshold=").append(microBatchThresholdExits.get());
        logger.info(diag.toString());
    }

    private int flushOutput(OutputStream outputStream, int pendingBytes) throws IOException {
        if (pendingBytes <= 0) {
            return 0;
        }

        outputStream.flush();
        incrementDiagnosticCounter(tcpFlushes);
        addDiagnosticCounter(tcpFlushBytes, pendingBytes);
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
        observePeak(peakMaintenanceTickGapMs, gapMs);
        long lateMs = Math.max(0L, gapMs - P2pConstants.MAINTENANCE_TICK_MS);
        if (lateMs < Math.max(2L, P2pConstants.MAINTENANCE_TICK_MS / 2L)) {
            return;
        }

        incrementDiagnosticCounter(maintenanceTickDrifts);
        addDiagnosticCounter(maintenanceTickLateMs, lateMs);
        long warnIntervalMs = diagnosticsSummaryMs;
        if (gapMs < diagnosticsTickDriftWarnMs
            || now - lastMaintenanceDriftWarningAt < warnIntervalMs) {
            return;
        }

        lastMaintenanceDriftWarningAt = now;
        logger.warn("{} connection {} maintenance tick drift {} ms pending={} buffered={} inboundQueue={} remote={}",
            side, connectionId, gapMs, pendingSegments.size(), bufferedRange(), inboundQueue.size(), remoteAddress);
        maybeLogDiagnostics("tick-drift", now, true);
    }

    private void logInboundQueueBacklogIfNeeded(long now, boolean forced) {
        long backlogBytes = inboundQueueBytes.get();
        long thresholdBytes = Math.max(P2pConstants.RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES * 4L,
            P2pConstants.TCP_BUFFER_SIZE / 2L);
        if (!forced && backlogBytes < thresholdBytes) {
            return;
        }

        long summaryIntervalMs = diagnosticsSummaryMs;
        if (!forced && now - lastInboundQueueWarningAt < summaryIntervalMs) {
            return;
        }

        lastInboundQueueWarningAt = now;
        if (backlogBytes > 0L) {
            logger.warn("{} connection {} inbound TCP backlog {} bytes queue={} pending={} buffered={} remote={}",
                side, connectionId, backlogBytes, inboundQueue.size(), pendingSegments.size(), bufferedRange(), remoteAddress);
        }
    }

    private long diagnosticsCounterTotal() {
        return outOfOrderPackets.get()
            + duplicatePackets.get()
            + selectiveAcknowledgements.get()
            + duplicateAcknowledgements.get()
            + negativeAcknowledgementsSent.get()
            + negativeAcknowledgementsReceived.get()
            + timeoutRetransmissions.get()
            + duplicateAckRetransmissions.get()
            + negativeAcknowledgementRetransmissions.get()
            + sendWindowBlocks.get()
            + sendWindowGrowthEvents.get()
            + sendWindowLossEvents.get()
            + maintenanceTickDrifts.get()
            + idleRestartEvents.get()
            + pacingWaitEvents.get()
            + acknowledgementPacketsSent.get()
            + delayedAcknowledgementsSent.get()
            + dataPacketsReceived.get()
            + dataPacketsSent.get();
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
        if (maxBandwidthBytesPerSecond > 0L && smoothedRoundTripTimeMs > 0.0D) {
            int targetCwnd;
            if (bbrState == BbrState.PROBE_RTT) {
                targetCwnd = 8;
            } else {
                double cwndGain = 2.0D;
                long bdpBytes = Math.round((maxBandwidthBytesPerSecond * smoothedRoundTripTimeMs) / 1000.0D);
                targetCwnd = (int) Math.round((bdpBytes * cwndGain) / P2pConstants.MAX_PAYLOAD_SIZE);
            }

            targetCwnd = Math.max(P2pConstants.INITIAL_SEND_WINDOW_SIZE,
                Math.min(P2pConstants.MAX_SEND_WINDOW_SIZE, targetCwnd));

            if (targetCwnd > sendWindowSize) {
                sendWindowSize = Math.min(sendWindowSize + 1, targetCwnd);
                incrementDiagnosticCounter(sendWindowGrowthEvents);
            } else if (targetCwnd < sendWindowSize) {
                sendWindowSize = Math.max(sendWindowSize - 1, targetCwnd);
            }
            return;
        }

        if (sendWindowSize >= P2pConstants.MAX_SEND_WINDOW_SIZE) {
            acknowledgementsSinceWindowIncrease = 0;
            return;
        }

        if (sendWindowSize < slowStartThreshold) {
            sendWindowSize++;
            incrementDiagnosticCounter(sendWindowGrowthEvents);
            return;
        }

        acknowledgementsSinceWindowIncrease++;
        if (acknowledgementsSinceWindowIncrease < Math.max(1, sendWindowSize)) {
            return;
        }

        acknowledgementsSinceWindowIncrease = 0;
        sendWindowSize++;
        incrementDiagnosticCounter(sendWindowGrowthEvents);
    }

    private void noteCongestionEvent(long now) {
        long guardMs = Math.max(P2pConstants.MIN_RESEND_MS, retransmitTimeoutMs);
        if (now - lastCongestionEventAt < guardMs) {
            return;
        }

        lastCongestionEventAt = now;
        slowStartThreshold = Math.max(P2pConstants.SEND_WINDOW_SIZE, sendWindowSize / 2);
        sendWindowSize = Math.max(P2pConstants.SEND_WINDOW_SIZE, slowStartThreshold);
        acknowledgementsSinceWindowIncrease = 0;
        resetPacingBudget();
        incrementDiagnosticCounter(sendWindowLossEvents);
    }

    private void maybeRestartSendWindowAfterIdle(long now) {
        long idleThreshold = Math.max(P2pConstants.IDLE_RESTART_MIN_MS, retransmitTimeoutMs);
        if (now - lastPayloadSentAt <= idleThreshold) {
            return;
        }

        int restartWindow = Math.max(P2pConstants.SEND_WINDOW_SIZE,
            Math.min(P2pConstants.INITIAL_SEND_WINDOW_SIZE, sendWindowSize));
        if (restartWindow == sendWindowSize) {
            return;
        }

        sendWindowSize = restartWindow;
        acknowledgementsSinceWindowIncrease = 0;
        resetPacingBudget();
        incrementDiagnosticCounter(idleRestartEvents);
    }

    private void waitForPacingSlot() {
        long intervalNanos = pacingIntervalNanos();
        if (intervalNanos <= 0L) {
            return;
        }

        long now = System.nanoTime();
        if (nextPayloadSendAtNanos == 0L || now > nextPayloadSendAtNanos + (intervalNanos * 16L)) {
            nextPayloadSendAtNanos = now;
            pacingBurstBudget = P2pConstants.PACING_BURST_PACKETS;
        }

        if (pacingBurstBudget > 0) {
            pacingBurstBudget--;
            nextPayloadSendAtNanos = Math.max(nextPayloadSendAtNanos, now) + intervalNanos;
            return;
        }

        long waitNanos = nextPayloadSendAtNanos - now;
        if (waitNanos > 0L) {
            incrementDiagnosticCounter(pacingWaitEvents);
            addDiagnosticCounter(pacingWaitNanos, waitNanos);
            observePeak(peakPacingWaitMs, TimeUnit.NANOSECONDS.toMillis(waitNanos));
            LockSupport.parkNanos(waitNanos);
            now = System.nanoTime();
        }

        nextPayloadSendAtNanos = Math.max(nextPayloadSendAtNanos, now) + intervalNanos;
    }

    private long pacingIntervalNanos() {
        if (!shouldPacePayloads()) {
            return 0L;
        }

        if (maxBandwidthBytesPerSecond > 0L) {
            double pacingGain;
            switch (bbrState) {
                case STARTUP: pacingGain = 1.5D; break;
                case DRAIN: pacingGain = 0.75D; break;
                case PROBE_BW: pacingGain = PACING_GAIN_CYCLE[pacingGainCycleIndex]; break;
                case PROBE_RTT: pacingGain = 1.0D; break;
                default: pacingGain = 1.25D;
            }

            long pacingRateBytesPerSecond = Math.round(maxBandwidthBytesPerSecond * pacingGain);
            if (pacingRateBytesPerSecond > 0L) {
                long intervalNanos = (P2pConstants.MAX_PAYLOAD_SIZE * 1_000_000_000L) / pacingRateBytesPerSecond;
                return Math.max(P2pConstants.MIN_PACING_INTERVAL_NANOS,
                    Math.min(P2pConstants.MAX_PACING_INTERVAL_NANOS, intervalNanos));
            }
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
        pacingBurstBudget = P2pConstants.PACING_BURST_PACKETS;
    }

    private boolean shouldPacePayloads() {
        if (!opened) {
            return false;
        }

        int pending = pendingSegments.size();
        int backlogThreshold = Math.max(P2pConstants.PACING_BURST_PACKETS * 8, sendWindowSize / 2);
        if (pending >= backlogThreshold) {
            return true;
        }

        if (lastCongestionEventAt == 0L) {
            return false;
        }

        long stableForMs = Math.max(P2pConstants.MIN_RESEND_MS, retransmitTimeoutMs);
        return System.currentTimeMillis() - lastCongestionEventAt <= stableForMs;
    }

    private void updateReceiveRate(long nowNanos) {
        if (lastReceiveTimeNanos > 0L) {
            long intervalNanos = nowNanos - lastReceiveTimeNanos;
            if (receiveIntervalNanos == 0L) {
                receiveIntervalNanos = intervalNanos;
            } else {
                receiveIntervalNanos = (receiveIntervalNanos * 3L + intervalNanos) / 4L;
            }
        }
        lastReceiveTimeNanos = nowNanos;
    }

    private void waitForReceivePacingSlot() {
        if (receiveIntervalNanos <= 0L) {
            return;
        }

        long nowNanos = System.nanoTime();
        if (nextReceiveWriteAtNanos == 0L) {
            nextReceiveWriteAtNanos = nowNanos;
            return;
        }

        long waitNanos = nextReceiveWriteAtNanos - nowNanos;
        if (waitNanos > 0L && waitNanos < 5_000_000L) {
            LockSupport.parkNanos(waitNanos);
        }

        nextReceiveWriteAtNanos = Math.max(nextReceiveWriteAtNanos, System.nanoTime()) + receiveIntervalNanos;
    }

    private int coalesceTcpPayload(InputStream inputStream, byte[] buffer, int read) throws IOException {
        int totalRead = read;
        observeMicroBatchWarmup(read);
        int thresholdBytes = initiator
            ? Math.min(adaptiveMicroBatchThresholdBytes, P2pConstants.CLIENT_MICRO_BATCH_THRESHOLD_BYTES)
            : adaptiveMicroBatchThresholdBytes;
        if (totalRead > 0 && totalRead < thresholdBytes) {
            int availableBeforeWait = inputStream.available();
            if (availableBeforeWait > 0) {
                incrementDiagnosticCounter(microBatchHits);
                addDiagnosticCounter(microBatchStartBytes, totalRead);
            }
            addDiagnosticCounter(microBatchEndBytes, totalRead);
            if (availableBeforeWait > 0) {
                incrementDiagnosticCounter(microBatchDeadlineExits);
            }
        }

        while (totalRead < buffer.length) {
            int available = inputStream.available();
            if (available <= 0) {
                break;
            }

            int chunk = inputStream.read(buffer, totalRead, Math.min(buffer.length - totalRead, available));
            if (chunk <= 0) {
                break;
            }

            totalRead += chunk;
        }
        return totalRead;
    }

    private void observeMicroBatchWarmup(int read) {
        if (read <= 0) {
            return;
        }

        int samples = microBatchWarmupSamples;
        if (samples >= P2pConstants.MICRO_BATCH_WARMUP_SAMPLES) {
            return;
        }

        microBatchWarmupBytes += read;
        samples++;
        microBatchWarmupSamples = samples;

        long averageBytes = Math.max(1L, microBatchWarmupBytes / samples);
        if (averageBytes <= 20L) {
            adaptiveMicroBatchThresholdBytes = P2pConstants.MICRO_BATCH_MAX_THRESHOLD_BYTES;
            return;
        }

        if (averageBytes <= 40L) {
            adaptiveMicroBatchThresholdBytes = P2pConstants.MICRO_BATCH_THRESHOLD_BYTES;
            return;
        }

        adaptiveMicroBatchThresholdBytes = P2pConstants.MICRO_BATCH_MIN_THRESHOLD_BYTES;
    }

    private void sendAcknowledgementPacket(int acknowledgement, int acknowledgementMask, long now) {
        incrementDiagnosticCounter(acknowledgementPacketsSent);
        receivedPacketsSinceLastAcknowledgement = 0;
        lastAcknowledgementPacketAt = now;
        lastAcknowledgementSent = acknowledgement;
        lastAcknowledgementMaskSent = acknowledgementMask;
        sendPacket(P2pPacket.ack(token, connectionId, acknowledgement, acknowledgementMask), now);
    }

    private void sendAcknowledgementPacket(int acknowledgement, int acknowledgementMask) {
        sendAcknowledgementPacket(acknowledgement, acknowledgementMask, System.currentTimeMillis());
    }

    private void scheduleDelayedAcknowledgement(long now) {
        if (closed.get()) {
            return;
        }

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

            incrementDiagnosticCounter(delayedAcknowledgementsSent);
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

            incrementDiagnosticCounter(acknowledgementReinforcementsSent);
            sendAcknowledgementPacket(acknowledgement, acknowledgementMask);
        }, P2pConstants.ACK_REINFORCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void incrementDiagnosticCounter(AtomicLong counter) {
        if (diagnosticsLoggingEnabled) {
            counter.incrementAndGet();
        }
    }

    private void addDiagnosticCounter(AtomicLong counter, long delta) {
        if (diagnosticsLoggingEnabled) {
            counter.addAndGet(delta);
        }
    }

    private void observePeak(AtomicLong peak, long value) {
        if (!diagnosticsLoggingEnabled || value <= 0L) {
            return;
        }

        long current = peak.get();
        while (value > current && !peak.compareAndSet(current, value)) {
            current = peak.get();
        }
    }

    private long kiloBitsPerSecond(long byteDelta, long intervalMs) {
        if (byteDelta <= 0L || intervalMs <= 0L) {
            return 0L;
        }

        return Math.round((byteDelta * 8_000D) / intervalMs) / 1_000L;
    }

    private long eventsPerSecond(long countDelta, long intervalMs) {
        if (countDelta <= 0L || intervalMs <= 0L) {
            return 0L;
        }

        return Math.round((countDelta * 1_000D) / intervalMs);
    }

    private long kiloBytes(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }

        return bytes / 1024L;
    }

    private long averagePayloadSize() {
        long packets = dataPacketsSent.get();
        if (packets <= 0L) {
            return 0L;
        }

        return dataBytesSent.get() / packets;
    }

    private long microBatchAverageStartBytes() {
        long hits = microBatchHits.get();
        if (hits <= 0L) {
            return 0L;
        }

        return microBatchStartBytes.get() / hits;
    }

    private long microBatchAverageEndBytes() {
        long hits = microBatchHits.get();
        if (hits <= 0L) {
            return 0L;
        }

        return microBatchEndBytes.get() / hits;
    }

    private void updateBandwidthEstimate(int ackedSeq, long ackedBytes, long nowNanos) {
        if (ackedBytes <= 0) {
            return;
        }

        if (firstSentTimeNanos == 0L) {
            firstSentTimeNanos = nowNanos;
            deliveredTimeNanos = nowNanos;
            bbrRoundStartSeq = ackedSeq;
            return;
        }

        deliveredBytes += ackedBytes;
        long elapsedNanos = nowNanos - deliveredTimeNanos;
        if (elapsedNanos <= 0L) {
            return;
        }

        deliveryRateBytesPerSecond = (ackedBytes * 1_000_000_000L) / elapsedNanos;
        if (deliveryRateBytesPerSecond > maxBandwidthBytesPerSecond) {
            maxBandwidthBytesPerSecond = deliveryRateBytesPerSecond;
        }

        deliveredTimeNanos = nowNanos;

        if (ackedSeq >= bbrRoundStartSeq) {
            bbrRoundStartSeq = nextSendSequence.get();
            bbrRoundCount++;

            if (bbrState == BbrState.STARTUP) {
                if (maxBandwidthBytesPerSecond > (priorMaxBandwidth * 1.25D)) {
                    priorMaxBandwidth = maxBandwidthBytesPerSecond;
                    bandwidthPlateauCount = 0;
                } else {
                    bandwidthPlateauCount++;
                }
            }
        }

        updateBbrState();
    }

    private void updateBbrState() {
        long nowMs = System.currentTimeMillis();

        switch (bbrState) {
            case STARTUP:
                if (bandwidthPlateauCount >= 3) {
                    bbrState = BbrState.DRAIN;
                }
                break;
            case DRAIN:
                if (pendingSegments.size() <= (maxBandwidthBytesPerSecond * minRttMs / 1000.0D / P2pConstants.MAX_PAYLOAD_SIZE)) {
                    bbrState = BbrState.PROBE_BW;
                    pacingGainCycleStartTimeNanos = System.nanoTime();
                }
                break;
            case PROBE_BW:
                long elapsedNanos = System.nanoTime() - pacingGainCycleStartTimeNanos;
                if (elapsedNanos > (minRttMs * 1_000_000L)) {
                    pacingGainCycleIndex = (pacingGainCycleIndex + 1) % PACING_GAIN_CYCLE.length;
                    pacingGainCycleStartTimeNanos = System.nanoTime();
                }

                if (nowMs - lastProbeRttTimeMs > 20000L) {
                    bbrState = BbrState.PROBE_RTT;
                    probeRttStartTimeMs = nowMs;
                }
                break;
            case PROBE_RTT:
                if (nowMs - probeRttStartTimeMs > 200L) {
                    bbrState = BbrState.PROBE_BW;
                    lastProbeRttTimeMs = nowMs;
                    pacingGainCycleStartTimeNanos = System.nanoTime();
                }
                break;
        }
    }

    private String roundTripMetric(double metric) {
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
