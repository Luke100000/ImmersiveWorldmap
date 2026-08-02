package net.conczin.immersive_worldmap.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class PriorityThreadPoolExecutor extends ThreadPoolExecutor {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final AtomicLong totalTasks = new AtomicLong();
    private final AtomicLong processedTasks = new AtomicLong();

    public PriorityThreadPoolExecutor(int threads, ThreadFactory threadFactory) {
        super(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), threadFactory);
    }

    public <T> CompletableFuture<T> submit(int priority, Supplier<T> supplier) {
        return submit(priority, null, supplier);
    }

    public <T> CompletableFuture<T> submit(int priority, Object tag, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        totalTasks.incrementAndGet();
        super.execute(new PrioritizedTask(priority, tag, future, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }, processedTasks::incrementAndGet));
        return future;
    }

    public long getTotalTasks() {
        return totalTasks.get();
    }

    public long getProcessedTasks() {
        return processedTasks.get();
    }

    public void discardQueuedTasksOutside(Predicate<Object> keep) {
        getQueue().removeIf(task -> task instanceof PrioritizedTask prioritized
                && prioritized.tag != null
                && !keep.test(prioritized.tag)
                && prioritized.cancel());
    }

    @Override
    public void execute(Runnable command) {
        super.execute(command instanceof PrioritizedTask ? command : new PrioritizedTask(Integer.MAX_VALUE, null, null, command, null));
    }

    private static final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
        private final int priority;
        private final Object tag;
        private final long sequence = SEQUENCE.getAndIncrement();
        private final CompletableFuture<?> future;
        private final Runnable task;
        private final Runnable onFinished;
        private final AtomicBoolean finished = new AtomicBoolean();

        private PrioritizedTask(int priority, Object tag, CompletableFuture<?> future, Runnable task, Runnable onFinished) {
            this.priority = priority;
            this.tag = tag;
            this.future = future;
            this.task = task;
            this.onFinished = onFinished;
        }

        private boolean cancel() {
            boolean cancelled = future != null && future.cancel(false);
            if (cancelled) finish();
            return cancelled;
        }

        private void finish() {
            if (onFinished != null && finished.compareAndSet(false, true)) {
                onFinished.run();
            }
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityComparison = Integer.compare(priority, other.priority);
            return priorityComparison != 0 ? priorityComparison : Long.compare(sequence, other.sequence);
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                finish();
            }
        }
    }
}
