package com.mcworldexplorer.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Request-owned, byte-bounded save pipeline. The writer owns admitted payloads until terminal completion. */
final class MapPersistence {
    // One session owns all summary/image submissions belonging to a tile load.
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    static final int ENCODING_RESERVATION_BYTES = 16 << 20;
    static final int RETAINED_AND_CODEC_GUARD_BYTES = 8 << 20;
    static final int PNG_WORKSPACE_BYTES = 1 << 20; // reclassified from the existing 9 MiB guard, not added
    static final long PRODUCER_BYTES = 2L << 20;
    private static final Semaphore PRODUCERS = new Semaphore(2, true);
    static final class Producer implements AutoCloseable {
        private boolean closed;
        public void close() { if (!closed) { closed = true; PRODUCERS.release(); } }
    }
    static Producer producer() {
        try {
            while (true) { checkCancelled(); if (PRODUCERS.tryAcquire(25, TimeUnit.MILLISECONDS)) return new Producer(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Producer admission interrupted"); }
    }
    static final long PENDING_LIMIT = 8L << 20;
    private static final Object QUEUE_LOCK = new Object();
    private static long pendingBytes;
    private static int pendingTasks;
    private static final java.util.concurrent.ThreadPoolExecutor WRITER = writer();
    private static java.util.concurrent.ThreadPoolExecutor writer() {
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(16), runnable -> {
                    var thread = new Thread(runnable, "map-cache-save"); thread.setDaemon(true); return thread;
                });
        executor.allowCoreThreadTimeOut(true); return executor;
    }
    static final class Session {
        final java.util.function.BooleanSupplier cancelled;
        MapTileCacheResult.SaveState state = MapTileCacheResult.SaveState.SAVED;
        Throwable error;
        int pending;
        boolean abandoned;
        Session(java.util.function.BooleanSupplier cancelled) { this.cancelled = cancelled; }
    }
    @FunctionalInterface interface SaveAction { void run() throws IOException; }
    static void begin() { begin(() -> false); }
    static void begin(java.util.function.BooleanSupplier cancelled) {
        if (SESSION.get() != null) throw new IllegalStateException("Nested save session");
        SESSION.set(new Session(cancelled));
    }
    static void failed(MapTileCacheResult.SaveState state) {
        var session = SESSION.get();
        if (session != null) synchronized (session) {
            if (session.state == MapTileCacheResult.SaveState.SAVED) session.state = state;
        }
    }
    static MapTileCacheResult finish(MapTileCacheResult value) {
        var session = SESSION.get();
        if (session == null) return value;
        synchronized (session) {
            if (session.state == MapTileCacheResult.SaveState.SAVED) return value;
            return new MapTileCacheResult(value.imagePath(), value.metadataPath(), value.image(), value.markers(),
                    value.sources(), value.identity(), session.state);
        }
    }
    /** Ownership transfers only after admission. Bytes include the executing task, not just the queue. */
    static void submit(long retainedBytes, SaveAction action) throws IOException {
        var session = SESSION.get();
        if (session == null) { action.run(); return; } // direct generator/cache consumers remain synchronous
        if (retainedBytes < 0 || retainedBytes > PRODUCER_BYTES - 512) throw new LimitExceeded();
        long charge = retainedBytes + 512; // task, session links and executor node
        synchronized (QUEUE_LOCK) {
            while (pendingTasks >= 16 || charge > PENDING_LIMIT - pendingBytes) {
                checkCancelled();
                try { QUEUE_LOCK.wait(25); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Save admission interrupted"); }
            }
            checkCancelled(); pendingTasks++; pendingBytes += charge;
        }
        synchronized (session) { session.pending++; }
        try {
            WRITER.execute(() -> {
                SESSION.set(session);
                try { checkCancelled(); action.run(); }
                catch (Throwable failure) { synchronized (session) { if (session.error == null) session.error = failure; } }
                finally {
                    SESSION.remove();
                    synchronized (QUEUE_LOCK) { pendingTasks--; pendingBytes -= charge; QUEUE_LOCK.notifyAll(); }
                    synchronized (session) { session.pending--; session.notifyAll(); }
                }
            });
        } catch (RuntimeException failure) {
            synchronized (QUEUE_LOCK) { pendingTasks--; pendingBytes -= charge; QUEUE_LOCK.notifyAll(); }
            synchronized (session) { session.pending--; session.notifyAll(); }
            throw failure;
        }
    }
    static void await() throws IOException {
        var session = SESSION.get(); if (session == null) return;
        synchronized (session) {
            while (session.pending != 0) {
                try { session.wait(25); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Save wait interrupted"); }
            }
            if (session.error instanceof IOException e) throw e;
            if (session.error instanceof RuntimeException e) throw e;
            if (session.error instanceof Error e) throw e;
            if (session.error != null) throw new IOException("Save worker failed", session.error);
        }
        checkCancelled();
    }
    /** Keep service activity alive until every owned task releases its payload, including on cancellation. */
    static void end() {
        var session = SESSION.get(); if (session == null) return;
        boolean interrupted = Thread.interrupted();
        synchronized (session) {
            session.abandoned = true;
            while (session.pending != 0) {
                try { session.wait(); } catch (InterruptedException e) { interrupted = true; }
            }
        }
        SESSION.remove(); if (interrupted) Thread.currentThread().interrupt();
    }
    static long pendingBytes() { synchronized (QUEUE_LOCK) { return pendingBytes; } }
    static long textBytes(String value) { return 64L + 2L * value.length(); }
    private static final ThreadLocal<Lease> ACTIVE = new ThreadLocal<>();
    private static final Semaphore ENCODING = new Semaphore(1, true);
    static void checkCancelled() {
        var session = SESSION.get();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Cache save interrupted");
        if (session != null) {
            boolean abandoned;
            synchronized (session) { abandoned = session.abandoned; }
            if (abandoned || session.cancelled.getAsBoolean()) throw new CancellationException("Cache save invalidated");
        }
    }
    static Lease acquire() {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested encoding admission");
        try {
            while (true) {
                checkCancelled();
                if (ENCODING.tryAcquire(25, TimeUnit.MILLISECONDS)) { var lease = new Lease(); ACTIVE.set(lease); return lease; }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("Cache encoding interrupted");
        }
    }
    static final class Lease implements AutoCloseable {
        private long used = RETAINED_AND_CODEC_GUARD_BYTES, peak = RETAINED_AND_CODEC_GUARD_BYTES;
        private boolean closed;
        synchronized void reserve(long bytes) {
            checkCancelled();
            if (closed || bytes < 0 || bytes > ENCODING_RESERVATION_BYTES - used) throw new LimitExceeded();
            used += bytes; peak = Math.max(peak, used);
        }
        synchronized void release(long bytes) { used -= bytes; }
        synchronized long usedBytes() { return used; }
        synchronized long peakBytes() { return peak; }
        public void close() {
            if (closed) return;
            closed = true; used = 0; ACTIVE.remove(); ENCODING.release();
        }
    }
    /** Charges array capacity, transient old/new overlap and every returned copy to its owning lease. */
    static final class Bytes extends ByteArrayOutputStream {
        private final int limit;
        private final Lease owner;
        Bytes(int limit) {
            super(0);
            if (limit < 0) throw new IllegalArgumentException("Negative buffer limit");
            this.limit = limit; owner = ACTIVE.get();
            if (owner != null) owner.reserve(allocation(0));
        }
        private static long allocation(int length) { return (16L + length + 7) & ~7L; }
        private void ensure(int n) {
            if (n < 0 || n > limit - count) throw new LimitExceeded();
            int required = count + n;
            if (required <= buf.length) return;
            int capacity = (int)Math.min(limit, Math.max(required, Math.max(1024L, (long)buf.length * 2)));
            if (owner != null) owner.reserve(allocation(capacity));
            byte[] replacement;
            try { replacement = java.util.Arrays.copyOf(buf, capacity); }
            catch (Throwable failure) { if (owner != null) owner.release(allocation(capacity)); throw failure; }
            int previous = buf.length; buf = replacement;
            if (owner != null) owner.release(allocation(previous));
        }
        @Override public synchronized void write(int b) { ensure(1); buf[count++] = (byte)b; }
        @Override public synchronized void write(byte[] b, int off, int len) {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            ensure(len); System.arraycopy(b, off, buf, count, len); count += len;
        }
        @Override public synchronized byte[] toByteArray() {
            if (owner != null) owner.reserve(allocation(count));
            try { return java.util.Arrays.copyOf(buf, count); }
            catch (Throwable failure) { if (owner != null) owner.release(allocation(count)); throw failure; }
        }
    }
    static final class LimitExceeded extends RuntimeException { }
}
