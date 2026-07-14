package org.developerkubilay.safra.p2p;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class P2pRuntime {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final ThreadFactory THREAD_FACTORY = createThreadFactory();

    private P2pRuntime() {
    }

    public static ScheduledExecutorService singleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(THREAD_FACTORY);
    }

    public static ScheduledExecutorService schedulerPool(int size) {
        return Executors.newScheduledThreadPool(size, THREAD_FACTORY);
    }

    public static Thread start(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        return thread;
    }

    private static ThreadFactory createThreadFactory() {
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Object factory = builder.getClass().getMethod("factory").invoke(builder);
            if (factory instanceof ThreadFactory) {
                return (ThreadFactory) factory;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }

        return runnable -> {
            Thread thread = new Thread(runnable, "safra-p2p-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            return thread;
        };
    }
}
