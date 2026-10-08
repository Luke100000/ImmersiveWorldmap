package net.conczin.immersive_worldmap.util;

import java.util.ArrayList;
import java.util.List;
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
        PrioritizedTask task = new PrioritizedTask(priority, tag, future, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
                if (!ThreadPoolUtil.isCancellation(throwable)) {
                    ThreadPoolUtil.crash(Thread.currentThread(), throwable);
                }
            }
        });
        try {
            super.execute(task);
        } catch (RejectedExecutionException e) {
            if (isShutdown()) {
                future.cancel(false);
            } else {
                future.completeExceptionally(e);
                ThreadPoolUtil.crash(Thread.currentThread(), e);
            }
        }
        return future;
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<>(() -> {
            try {
                return callable.call();
            } catch (Exception | Error error) {
                if (!ThreadPoolUtil.isCancellation(error)) {
                    ThreadPoolUtil.crash(Thread.currentThread(), error);
                }
                throw error;
            }
        });
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T result) {
        return newTaskFor(Executors.callable(runnable, result));
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> queued = super.shutdownNow();
        for (Runnable task : queued) {
            if (task instanceof PrioritizedTask prioritized) prioritized.cancel();
        }
        return queued;
    }

    public void discardQueuedTasksOutside(Predicate<Object> keep) {
        List<PrioritizedTask> discarded = new ArrayList<>();
        getQueue().removeIf(task -> {
            if (task instanceof PrioritizedTask prioritized
                && prioritized.tag != null
                && !keep.test(prioritized.tag)) {
                discarded.add(prioritized);
                return true;
            }
            return false;
        });
        discarded.forEach(PrioritizedTask::cancel);
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

        private void cancel() {
            if (future != null) {
                future.cancel(false);
            } else if (task instanceof Future<?> result) {
                result.cancel(false);
            }
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityComparison = Integer.compare(priority, other.priority);
            return priorityComparison != 0 ? priorityComparison : Long.compare(sequence, other.sequence);
        }

        @Override
        public void run() {
            if (future == null || !future.isDone()) task.run();
        }
    }
}
