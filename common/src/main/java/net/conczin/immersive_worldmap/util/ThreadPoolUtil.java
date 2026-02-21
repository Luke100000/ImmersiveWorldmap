package net.conczin.immersive_worldmap.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

public final class ThreadPoolUtil {
    private ThreadPoolUtil() {
    }

    public static ThreadPoolExecutor createLowPriorityFixedThreadPool(String namePrefix) {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, cores - 2);
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r);
            thread.setName(namePrefix + "-" + thread.threadId());
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            return thread;
        };
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(threads, threadFactory);
    }
}

