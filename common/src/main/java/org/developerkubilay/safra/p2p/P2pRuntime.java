package org.developerkubilay.safra.p2p;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class P2pRuntime {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final ThreadFactory BACKGROUND_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "safra-p2p-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    private P2pRuntime() {
    }

    static ScheduledExecutorService singleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(BACKGROUND_THREAD_FACTORY);
    }

    static ScheduledExecutorService schedulerPool(int size) {
        return Executors.newScheduledThreadPool(size, BACKGROUND_THREAD_FACTORY);
    }

    static Thread start(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
