package net.conczin.immersive_worldmap.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class PriorityThreadPoolExecutor extends ThreadPoolExecutor {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public PriorityThreadPoolExecutor(int threads, ThreadFactory threadFactory) {
        super(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), threadFactory);
    }

    public <T> CompletableFuture<T> submit(int priority, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        super.execute(new PrioritizedTask(priority, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }));
        return future;
    }

    @Override
    public void execute(Runnable command) {
        super.execute(command instanceof PrioritizedTask ? command : new PrioritizedTask(Integer.MAX_VALUE, command));
    }

    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        private final int priority;
        private final long sequence = SEQUENCE.getAndIncrement();
        private final Runnable task;

        private PrioritizedTask(int priority, Runnable task) {
            this.priority = priority;
            this.task = task;
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityComparison = Integer.compare(priority, other.priority);
            return priorityComparison != 0 ? priorityComparison : Long.compare(sequence, other.sequence);
        }

        @Override
        public void run() {
            task.run();
        }
    }
}
