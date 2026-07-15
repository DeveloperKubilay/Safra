package org.developerkubilay.safra.p2p;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class P2pRuntime {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final ThreadFactory SCHEDULER_THREAD_FACTORY = createSchedulerThreadFactory();

    private P2pRuntime() {
    }

    public static ScheduledExecutorService singleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(SCHEDULER_THREAD_FACTORY);
    }

    public static ScheduledExecutorService schedulerPool(int size) {
        return Executors.newScheduledThreadPool(size, SCHEDULER_THREAD_FACTORY);
    }

    public static Thread start(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        setNetworkPriority(thread);
        thread.start();
        return thread;
    }

    private static ThreadFactory createSchedulerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "safra-p2p-scheduler-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            setNetworkPriority(thread);
            return thread;
        };
    }

    private static void setNetworkPriority(Thread thread) {
        try {
            thread.setPriority(Thread.NORM_PRIORITY + 1);
        } catch (RuntimeException ignored) {
        }
    }
}
