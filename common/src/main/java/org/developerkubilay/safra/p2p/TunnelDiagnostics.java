package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encapsulated diagnostics and telemetry for ReliableTunnelConnection.
 * Isolates telemetry counters and performance metrics from the core network loop.
 */
final class TunnelDiagnostics {
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

    private long lastDiagnosticsLogAt;
    private long lastDiagnosticsCounterTotal;
    private long lastSummaryAt;
    private long lastSummaryDataPacketsSent;
    private long lastSummaryDataBytesSent;
    private long lastSummaryDataPacketsReceived;
    private long lastSummaryDataBytesReceived;
    private long lastSummaryTcpFlushes;
    private long lastSummaryTcpFlushBytes;
    private long lastSummaryTimeoutRetransmissions;
    private long lastSummaryDuplicateAckRetransmissions;
    private long lastSummaryNegativeAcknowledgementRetransmissions;
    private long lastSummaryAcknowledgementPacketsSent;
    private long lastInboundQueueWarningAt;

    void recordTcpRead(int bytes) {
        tcpReadBytes.addAndGet(bytes);
    }

    void recordTcpWrite(int bytes) {
        tcpWriteBytes.addAndGet(bytes);
        inboundQueueBytes.addAndGet(-bytes);
    }

    void recordTcpFlush(int bytes) {
        tcpFlushes.incrementAndGet();
        tcpFlushBytes.addAndGet(bytes);
    }

    void recordDataReceived(int bytes) {
        dataPacketsReceived.incrementAndGet();
        dataBytesReceived.addAndGet(bytes);
    }

    void recordDataSent(int bytes) {
        dataPacketsSent.incrementAndGet();
        dataBytesSent.addAndGet(bytes);
    }

    void recordDuplicatePacket() {
        duplicatePackets.incrementAndGet();
    }

    void recordOutOfOrderPacket() {
        outOfOrderPackets.incrementAndGet();
    }

    void recordInboundQueue(int bytes, int queueSize) {
        inboundQueueBytes.addAndGet(bytes);
        observePeak(peakInboundQueueDepth, queueSize);
        observePeak(peakInboundQueueBytes, inboundQueueBytes.get());
    }

    void recordDuplicateAck() {
        duplicateAcknowledgements.incrementAndGet();
    }

    void recordTimeoutRetransmission() {
        timeoutRetransmissions.incrementAndGet();
    }

    void recordFastRetransmission(boolean negativeAck) {
        if (negativeAck) {
            negativeAcknowledgementRetransmissions.incrementAndGet();
        } else {
            duplicateAckRetransmissions.incrementAndGet();
        }
    }

    void recordSelectiveAck() {
        selectiveAcknowledgements.incrementAndGet();
    }

    void recordNegativeAckSent() {
        negativeAcknowledgementsSent.incrementAndGet();
    }

    void recordNegativeAckReceived() {
        negativeAcknowledgementsReceived.incrementAndGet();
    }

    void recordMaintenanceTickDrift(long gapMs, long lateMs) {
        observePeak(peakMaintenanceTickGapMs, gapMs);
        maintenanceTickDrifts.incrementAndGet();
        maintenanceTickLateMs.addAndGet(lateMs);
    }

    void observeMaintenanceTickGap(long gapMs) {
        observePeak(peakMaintenanceTickGapMs, gapMs);
    }

    void recordSendWindowGrowth() {
        sendWindowGrowthEvents.incrementAndGet();
    }

    void recordSendWindowLoss() {
        sendWindowLossEvents.incrementAndGet();
    }

    void recordSendWindowBlock() {
        sendWindowBlocks.incrementAndGet();
    }

    void recordIdleRestart() {
        idleRestartEvents.incrementAndGet();
    }

    void recordPacingWait(long waitNanos) {
        pacingWaitEvents.incrementAndGet();
        pacingWaitNanos.addAndGet(waitNanos);
        observePeak(peakPacingWaitMs, TimeUnit.NANOSECONDS.toMillis(waitNanos));
    }

    void recordAckSent() {
        acknowledgementPacketsSent.incrementAndGet();
    }

    void recordDelayedAckSent() {
        delayedAcknowledgementsSent.incrementAndGet();
    }

    void recordAckReinforcementSent() {
        acknowledgementReinforcementsSent.incrementAndGet();
    }

    void observePendingSegments(int count) {
        observePeak(peakPendingSegments, count);
    }

    void observeReceiveBuffer(int count) {
        observePeak(peakReceiveBuffer, count);
    }

    void observeHeadOfLineBlock(long durationMs) {
        observePeak(peakHeadOfLineBlockMs, durationMs);
    }

    void observeWindowBlock(long durationMs) {
        observePeak(peakWindowBlockMs, durationMs);
    }

    private void observePeak(AtomicLong peak, long value) {
        if (value <= 0L) {
            return;
        }

        long current = peak.get();
        while (value > current && !peak.compareAndSet(current, value)) {
            current = peak.get();
        }
    }

    long counterTotal() {
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

    void maybeLogDiagnostics(Logger logger, String side, int connectionId, String trigger, long now, boolean forced,
                             long summaryIntervalMs, InetSocketAddress remoteAddress, int pendingSegmentsCount,
                             int sendWindowSize, int slowStartThreshold, int nextExpectedSequence,
                             String bufferedRange, int inboundQueueSize, long retransmitTimeoutMs,
                             double smoothedRoundTripTimeMs, double roundTripVariationMs, boolean queuesEmpty) {
        logInboundQueueBacklogIfNeeded(logger, side, connectionId, remoteAddress, inboundQueueSize, pendingSegmentsCount, bufferedRange, now, forced, summaryIntervalMs);

        long total = counterTotal();
        if (!forced) {
            if (now - lastDiagnosticsLogAt < summaryIntervalMs) {
                return;
            }
            if (total == lastDiagnosticsCounterTotal && queuesEmpty) {
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
        long negativeAcknowledgementRetransmissionsDelta = totalNegativeAcknowledgementRetransmissions - lastSummaryNegativeAcknowledgementRetransmissions;
        long acknowledgementPacketsSentDelta = totalAcknowledgementPacketsSent - lastSummaryAcknowledgementPacketsSent;

        lastDiagnosticsLogAt = now;
        lastDiagnosticsCounterTotal = total;
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
            .append(" pending=").append(pendingSegmentsCount)
            .append(" pendingPeak=").append(peakPendingSegments.get())
            .append(" window=").append(sendWindowSize)
            .append(" ssthresh=").append(slowStartThreshold)
            .append(" expected=").append(nextExpectedSequence)
            .append(" buffered=").append(bufferedRange)
            .append(" bufferedPeak=").append(peakReceiveBuffer.get())
            .append(" inboundQueue=").append(inboundQueueSize)
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
            .append(" tcpWriteKb=").append(kiloBytes(tcpWriteBytes.get()));

        logger.info(diag.toString());
    }

    private void logInboundQueueBacklogIfNeeded(Logger logger, String side, int connectionId,
                                                InetSocketAddress remoteAddress, int queueSize,
                                                int pendingSize, String bufferedRange,
                                                long now, boolean forced, long summaryIntervalMs) {
        long backlogBytes = inboundQueueBytes.get();
        long thresholdBytes = Math.max(P2pConstants.RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES * 4L,
            P2pConstants.TCP_BUFFER_SIZE / 2L);
        if (!forced && backlogBytes < thresholdBytes) {
            return;
        }

        if (!forced && now - lastInboundQueueWarningAt < summaryIntervalMs) {
            return;
        }

        lastInboundQueueWarningAt = now;
        if (backlogBytes > 0L) {
            logger.warn("{} connection {} inbound TCP backlog {} bytes queue={} pending={} buffered={} remote={}",
                side, connectionId, backlogBytes, queueSize, pendingSize, bufferedRange, remoteAddress);
        }
    }

    private static long kiloBitsPerSecond(long byteDelta, long intervalMs) {
        if (byteDelta <= 0L || intervalMs <= 0L) {
            return 0L;
        }
        return Math.round((byteDelta * 8_000D) / intervalMs) / 1_000L;
    }

    private static long eventsPerSecond(long countDelta, long intervalMs) {
        if (countDelta <= 0L || intervalMs <= 0L) {
            return 0L;
        }
        return Math.round((countDelta * 1_000D) / intervalMs);
    }

    private static long kiloBytes(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return bytes / 1024L;
    }

    private static String roundTripMetric(double metric) {
        return metric < 0.0D ? "-" : Long.toString(Math.round(metric));
    }
}
