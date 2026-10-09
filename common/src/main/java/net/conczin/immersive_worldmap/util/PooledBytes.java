package net.conczin.immersive_worldmap.util;

import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PooledBytes {
    private static final Unsafe UNSAFE = unsafe();
    private static final long MAX_POOLED_BYTES = 64L * 1024 * 1024;
    private static final Map<Integer, ArrayDeque<ByteBuffer>> POOL = new HashMap<>();
    private static final PooledBytes EMPTY = new PooledBytes(ByteBuffer.allocate(0), 0);
    private static long pooledBytes;
    private static final Cleaner CLEANER = Cleaner.create(task -> {
        Thread thread = new Thread(task, "ImmersiveWorldmap-BufferRecycler");
        thread.setUncaughtExceptionHandler(ThreadPoolUtil::crash);
        return thread;
    });

    private final ByteBuffer bytes;
    private final byte[] array;
    private final long address;
    private final int length;

    private PooledBytes(ByteBuffer bytes, int length) {
        this.bytes = bytes;
        this.array = bytes.hasArray() ? bytes.array() : null;
        this.address = bytes.isDirect() ? MemoryUtil.memAddress(bytes) : 0;
        this.length = length;
    }

    // Borrowed arrays may still have external readers, so they must never enter the pool.
    public static PooledBytes wrap(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new PooledBytes(ByteBuffer.wrap(bytes), bytes.length);
    }

    public static PooledBytes allocate(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative buffer length");
        }
        if (length == 0) return EMPTY;
        int capacity = Math.toIntExact((length + 4095L) & ~4095L);
        ByteBuffer bytes = null;
        synchronized (POOL) {
            ArrayDeque<ByteBuffer> buffers = POOL.get(capacity);
            if (buffers != null) {
                bytes = buffers.removeFirst();
                pooledBytes -= capacity;
                if (buffers.isEmpty()) {
                    POOL.remove(capacity);
                }
            }
        }
        if (bytes == null) {
            bytes = ByteBuffer.allocateDirect(capacity);
        }
        PooledBytes result = new PooledBytes(bytes, length);
        ByteBuffer storage = bytes;
        CLEANER.register(result, () -> {
            try {
                synchronized (POOL) {
                    if (pooledBytes + storage.capacity() > MAX_POOLED_BYTES) {
                        // A second GC for the direct buffer's cleaner can exhaust the native-memory limit.
                        UNSAFE.invokeCleaner(storage);
                        return;
                    }
                    POOL.computeIfAbsent(storage.capacity(), ignored -> new ArrayDeque<>()).addLast(storage);
                    pooledBytes += storage.capacity();
                }
            } catch (Throwable error) {
                ThreadPoolUtil.crash(Thread.currentThread(), error);
            }
        });
        return result;
    }

    private static Unsafe unsafe() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public int length() {
        return length;
    }

    public int capacity() {
        return bytes.capacity();
    }

    public byte get(int index) {
        try {
            Objects.checkIndex(index, length);
            if (array == null) {
                return MemoryUtil.memGetByte(address + index);
            }
            return array[index];
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void copyFrom(ByteBuffer source) {
        try {
            bytes.put(0, source, source.position(), length);
            source.position(source.position() + length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void copyTo(ByteBuffer target) {
        try {
            int position = target.position();
            target.put(position, bytes, 0, length);
            target.position(position + length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }
}
