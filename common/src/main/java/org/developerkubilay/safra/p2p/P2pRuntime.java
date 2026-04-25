package org.developerkubilay.safra.p2p;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

final class P2pRuntime {
    private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().factory();

    private P2pRuntime() {
    }

    static ScheduledExecutorService singleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(VIRTUAL_THREAD_FACTORY);
    }

    static ScheduledExecutorService schedulerPool(int size) {
        return Executors.newScheduledThreadPool(size, VIRTUAL_THREAD_FACTORY);
    }

    static Thread start(String name, Runnable runnable) {
        return Thread.ofVirtual().name(name).start(runnable);
    }
}
