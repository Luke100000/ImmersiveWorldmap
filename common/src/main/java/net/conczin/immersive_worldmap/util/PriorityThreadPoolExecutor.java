package net.conczin.immersive_worldmap.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class PriorityThreadPoolExecutor extends ThreadPoolExecutor {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public PriorityThreadPoolExecutor(int threads, ThreadFactory threadFactory) {
        super(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), threadFactory);
    }

    public <T> CompletableFuture<T> submit(int priority, Supplier<T> supplier) {
        return submit(priority, null, supplier);
    }

    public <T> CompletableFuture<T> submit(int priority, Object tag, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        super.execute(new PrioritizedTask(priority, tag, future, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }));
        return future;
    }

    public void discardQueuedTasksOutside(Predicate<Object> keep) {
        getQueue().removeIf(task -> task instanceof PrioritizedTask prioritized
                && prioritized.tag != null
                && !keep.test(prioritized.tag)
                && prioritized.cancel());
    }

    @Override
    public void execute(Runnable command) {
        super.execute(command instanceof PrioritizedTask ? command : new PrioritizedTask(Integer.MAX_VALUE, null, null, command));
    }

    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        private final int priority;
        private final Object tag;
        private final long sequence = SEQUENCE.getAndIncrement();
        private final CompletableFuture<?> future;
        private final Runnable task;

        private PrioritizedTask(int priority, Object tag, CompletableFuture<?> future, Runnable task) {
            this.priority = priority;
            this.tag = tag;
            this.future = future;
            this.task = task;
        }

        private boolean cancel() {
            return future != null && future.cancel(false);
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
