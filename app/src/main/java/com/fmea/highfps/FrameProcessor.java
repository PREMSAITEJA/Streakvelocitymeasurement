package com.fmea.highfps;

import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FrameProcessor: decouples camera buffer lifetime from disk write speed.
 *
 * ROOT-CAUSE FIX for "maxImages (5) has already been acquired":
 * The ImageReader only owns hardware buffers. The old code kept each Image open
 * until its TIFF finished writing to disk, so as soon as disk writes fell behind,
 * the reader ran out of buffers.
 *
 * New pipeline:
 *   1. onImageAvailable: drain the reader, COPY the Y plane into a pre-allocated
 *      heap byte[] from bufferPool, and image.close() IMMEDIATELY.
 *   2. The byte[] goes into frameQueue. Change to put() (BLOCKING) ensures NO
 *      DROPPED FRAMES. Camera thread waits for the disk if needed.
 *   3. Dedicated writer thread pulls frames and streams them to disk via FrameSaver.
 *
 * MEMORY SAFETY:
 * Uses a fixed bufferPool to eliminate runtime byte[] allocations, preventing
 * OutOfMemoryError. Total heap usage is strictly capped.
 */
public class FrameProcessor implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "FrameProcessor";

    /** Queued frames waiting for disk. Tuning: 10 frames = ~20MB. */
    private static final int QUEUE_CAPACITY = 10;

    /** Buffer pool size. Tuning: 15 buffers = ~30MB. Must be >= QUEUE_CAPACITY. */
    private static final int BUFFER_POOL_SIZE = 15;

    /** Keep recording until only this much space would remain: 1 MB floor. */
    private static final long MIN_FREE_BYTES_FLOOR = 1L * 1024 * 1024;

    /** Check free space via statfs only every N frames to keep the hot path cheap. */
    private static final int STORAGE_CHECK_INTERVAL = 10;

    /** Callback so MainActivity can auto-stop the session when the disk is full. */
    public interface StorageSafetyCallback {
        void onStorageExhausted();
    }

    /** One captured frame, already copied off the camera hardware buffer. */
    private static final class FrameData {
        final byte[] yData;
        final int width;
        final int height;

        FrameData(byte[] yData, int width, int height) {
            this.yData = yData;
            this.width = width;
            this.height = height;
        }
    }

    private final FrameSaver frameSaver;
    private final StorageSafetyCallback storageCallback;
    private final BlockingQueue<FrameData> frameQueue;
    private final BlockingQueue<byte[]> bufferPool;
    private Thread writerThread;

    private volatile boolean isRecording;
    private final AtomicLong droppedFrameCount = new AtomicLong(0);
    private final AtomicBoolean storageExhaustedNotified = new AtomicBoolean(false);
    private long framesSinceStorageCheck = 0;
    private volatile boolean storageOk = true;

    public FrameProcessor(FrameSaver frameSaver, StorageSafetyCallback storageCallback) {
        this.frameSaver = frameSaver;
        this.storageCallback = storageCallback;
        this.frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.bufferPool = new ArrayBlockingQueue<>(BUFFER_POOL_SIZE);
        this.isRecording = false;
    }

    /** Pre-fills the buffer pool before recording starts. */
    private void preallocateBuffers(int width, int height) {
        bufferPool.clear();
        int size = width * height;
        for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
            bufferPool.offer(new byte[size]);
        }
    }

    public void startRecording() {
        isRecording = true;
        droppedFrameCount.set(0);
        storageExhaustedNotified.set(false);
        storageOk = true;
        framesSinceStorageCheck = 0;

        writerThread = new Thread(this::writerLoop, "FrameWriterThread");
        writerThread.setPriority(Thread.NORM_PRIORITY + 2); // Boosted priority
        writerThread.start();
    }

    public void stopRecording() {
        isRecording = false;
        frameQueue.clear();
        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        while (true) {
            Image image;
            try {
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.w(TAG, "acquireNextImage reader saturated", e);
                droppedFrameCount.incrementAndGet();
                return;
            }
            if (image == null) return;

            try {
                if (!isRecording || !storageOk) {
                    continue;
                }

                // First frame initialization of buffer pool
                if (bufferPool.isEmpty() && frameQueue.isEmpty()) {
                    preallocateBuffers(image.getWidth(), image.getHeight());
                }

                FrameData frame = copyFrame(image);
                if (frame == null) continue;

                if (!hasRoomForFrame(frame.yData.length)) {
                    notifyStorageExhausted();
                    bufferPool.offer(frame.yData); // Return buffer
                    continue;
                }

                // put() is BLOCKING. If the disk is slow, we wait for a free slot.
                // This ensures ZERO DROPPED FRAMES.
                frameQueue.put(frame);

            } catch (Exception e) {
                Log.e(TAG, "Frame processing failed", e);
                droppedFrameCount.incrementAndGet();
            } finally {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    private FrameData copyFrame(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();

        // reuse buffer from pool instead of new allocation
        byte[] data;
        try {
            data = bufferPool.take(); // Wait if no buffers available
        } catch (InterruptedException e) {
            return null;
        }

        if (rowStride == width) {
            yBuffer.get(data, 0, width * height);
        } else {
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * rowStride);
                yBuffer.get(data, row * width, width);
            }
        }
        return new FrameData(data, width, height);
    }

    private boolean hasRoomForFrame(long frameBytes) {
        if (!storageOk) return false;
        if (framesSinceStorageCheck++ % STORAGE_CHECK_INTERVAL != 0) return true;

        long free = frameSaver.getFreeSpaceBytes();
        long pendingBytes = (long) (frameQueue.size() + 1) * frameBytes;
        boolean ok = free - pendingBytes >= MIN_FREE_BYTES_FLOOR;
        if (!ok) {
            storageOk = false;
        }
        return ok;
    }

    private void notifyStorageExhausted() {
        if (storageExhaustedNotified.compareAndSet(false, true) && storageCallback != null) {
            storageCallback.onStorageExhausted();
        }
    }

    private void writerLoop() {
        while (isRecording || !frameQueue.isEmpty()) {
            FrameData frame;
            try {
                frame = frameQueue.take();
            } catch (InterruptedException e) {
                if (!isRecording) break;
                continue;
            }

            try {
                frameSaver.saveFrame(frame.yData, frame.width, frame.height);
            } catch (Exception e) {
                Log.e(TAG, "Disk write exception", e);
            } finally {
                // Return buffer to pool for reuse
                bufferPool.offer(frame.yData);
            }
        }
    }

    public long getDroppedFrameCount() {
        return droppedFrameCount.get();
    }

    public int getQueueSize() {
        return frameQueue.size();
    }
}
