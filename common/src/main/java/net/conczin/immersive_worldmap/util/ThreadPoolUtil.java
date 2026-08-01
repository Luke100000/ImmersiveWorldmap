package net.conczin.immersive_worldmap.util;

import java.util.concurrent.ThreadFactory;

public final class ThreadPoolUtil {
    private ThreadPoolUtil() {
    }

    public static PriorityThreadPoolExecutor createLowPriorityFixedThreadPool(String namePrefix) {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, cores - 2);
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r);
            thread.setName(namePrefix + "-" + thread.threadId());
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            return thread;
        };
        return new PriorityThreadPoolExecutor(threads, threadFactory);
    }
}
