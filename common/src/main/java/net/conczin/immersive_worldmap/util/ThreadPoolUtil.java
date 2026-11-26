package net.conczin.immersive_worldmap.util;

import net.conczin.immersive_worldmap.config.Config;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;

import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ThreadPoolUtil {
    private static final AtomicBoolean CRASH_SCHEDULED = new AtomicBoolean();

    private ThreadPoolUtil() {
    }

    public static PriorityThreadPoolExecutor createLowPriorityFixedThreadPool(String namePrefix) {
        return createLowPriorityFixedThreadPool(namePrefix, Config.getInstance().getWorkerThreads());
    }

    public static PriorityThreadPoolExecutor createLowPriorityFixedThreadPool(String namePrefix, int threads) {
        ThreadFactory threadFactory = r -> {
            Thread thread = new Thread(r);
            thread.setName(namePrefix + "-" + thread.threadId());
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(ThreadPoolUtil::crash);
            return thread;
        };
        return new PriorityThreadPoolExecutor(threads, threadFactory);
    }

    public static void crash(Thread thread, Throwable error) {
        error = unwrap(error);
        if (error instanceof SQLException) {
            ImmersiveWorldmap.LOGGER.error("Database operation failed on {}", thread.getName(), error);
            return;
        }
        if (!CRASH_SCHEDULED.compareAndSet(false, true)) return;
        CrashReport report = CrashReport.forThrowable(error, "Immersive Worldmap background task");
        report.addCategory("Immersive Worldmap").setDetail("Worker thread", thread.getName());
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.delayCrash(report));
    }

    public static <T> CompletableFuture<T> failOnError(CompletableFuture<T> future) {
        future.whenComplete((result, error) -> {
            if (error == null || isCancellation(error)) return;
            crash(Thread.currentThread(), error);
        });
        return future;
    }

    public static boolean isCancellation(Throwable error) {
        return unwrap(error) instanceof CancellationException;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
