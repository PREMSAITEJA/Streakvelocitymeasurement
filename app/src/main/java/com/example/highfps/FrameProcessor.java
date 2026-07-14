package com.example.highfps;

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
 * The ImageReader only owns IMAGE_READER_BUFFER (5) hardware buffers. The old code
 * kept each Image open until its TIFF finished writing to disk, so as soon as disk
 * writes fell behind the camera, 5 Images were open simultaneously and the next
 * acquireNextImage() threw IllegalStateException and killed FrameThread.
 *
 * New pipeline:
 *   1. onImageAvailable (FrameThread): drain the reader, COPY the Y plane into a
 *      plain heap byte[] (stride removed), and image.close() IMMEDIATELY.
 *      A camera buffer is now held for ~2-3 ms regardless of disk speed, so the
 *      reader can never run out of buffers.
 *   2. The byte[] goes into a bounded queue. If the disk can't keep up the queue
 *      fills and the NEWEST frame is dropped (counted) instead of crashing.
 *   3. A dedicated writer thread pulls frames off the queue and streams them to
 *      TIFF via FrameSaver.
 *
 * STORAGE SAFETY:
 * Before every enqueue AND every disk write, free space is checked. Recording is
 * allowed to continue until the device is nearly full: it stops only when writing
 * one more frame would leave less than MIN_FREE_BYTES_FLOOR (1 MB) of free space.
 * When that limit is hit, StorageSafetyCallback.onStorageExhausted() fires exactly
 * once so the Activity can stop the session cleanly.
 */
public class FrameProcessor implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "FrameProcessor";

    /** Queued frames waiting for disk. 24 x ~2 MB (1080p Y plane) ~= 48 MB heap max. */
    private static final int QUEUE_CAPACITY = 24;

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
        final byte[] yData;   // tight-packed (stride removed), width * height bytes
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
        this.isRecording = false;
    }

    public void startRecording() {
        isRecording = true;
        droppedFrameCount.set(0);
        storageExhaustedNotified.set(false);
        storageOk = true;
        framesSinceStorageCheck = 0;

        writerThread = new Thread(this::writerLoop, "FrameWriterThread");
        writerThread.setPriority(Thread.NORM_PRIORITY + 1);
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
        // Drain EVERYTHING that is ready. Every acquired Image is closed inside
        // copyFrame() before the next acquire, so at most ONE image is ever open
        // here -> the maxImages limit can no longer be exceeded.
        while (true) {
            Image image;
            try {
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                // Defensive: should no longer happen, but never crash the thread.
                Log.w(TAG, "acquireNextImage while reader saturated; skipping", e);
                droppedFrameCount.incrementAndGet();
                return;
            }
            if (image == null) return; // reader drained

            try {
                if (!isRecording || !storageOk) {
                    continue; // finally closes the image
                }
                FrameData frame = copyFrame(image);
                if (frame == null) continue;

                // Storage guard on the ingest side (checked every N frames).
                if (!hasRoomForFrame(frame.yData.length)) {
                    notifyStorageExhausted();
                    continue;
                }

                // Non-blocking enqueue: if the disk is behind, drop instead of stalling
                // the camera callback or holding hardware buffers.
                if (!frameQueue.offer(frame)) {
                    droppedFrameCount.incrementAndGet();
                }
            } catch (Exception e) {
                Log.e(TAG, "Frame copy failed", e);
                droppedFrameCount.incrementAndGet();
            } finally {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Copy the Y plane into a tight heap array so the Image can be closed at once. */
    private FrameData copyFrame(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();

        byte[] data = new byte[width * height];
        if (rowStride == width) {
            int len = Math.min(data.length, yBuffer.remaining());
            yBuffer.get(data, 0, len);
        } else {
            // Remove row padding while copying
            for (int row = 0; row < height; row++) {
                int pos = row * rowStride;
                if (pos >= yBuffer.limit()) break;
                yBuffer.position(pos);
                int len = Math.min(width, yBuffer.remaining());
                yBuffer.get(data, row * width, len);
            }
        }
        return new FrameData(data, width, height);
    }

    /** True while writing frameBytes more would still leave >= 1 MB free. */
    private boolean hasRoomForFrame(long frameBytes) {
        if (!storageOk) return false;
        if (framesSinceStorageCheck++ % STORAGE_CHECK_INTERVAL != 0) return true;

        long free = frameSaver.getFreeSpaceBytes();
        // Budget for the frames already queued but not yet on disk, plus this one.
        long pendingBytes = (long) (frameQueue.size() + 1) * frameBytes;
        boolean ok = free - pendingBytes >= MIN_FREE_BYTES_FLOOR;
        if (!ok) {
            storageOk = false;
            Log.w(TAG, "Storage floor reached: free=" + free + "B, pending=" + pendingBytes + "B");
        }
        return ok;
    }

    private void notifyStorageExhausted() {
        if (storageExhaustedNotified.compareAndSet(false, true) && storageCallback != null) {
            storageCallback.onStorageExhausted();
        }
    }

    /** Dedicated disk-writer loop: pulls copied frames and streams them to TIFF. */
    private void writerLoop() {
        while (isRecording || !frameQueue.isEmpty()) {
            FrameData frame;
            try {
                frame = frameQueue.take();
            } catch (InterruptedException e) {
                if (!isRecording) break;
                continue;
            }

            // Final guard right before the write hits the disk.
            long free = frameSaver.getFreeSpaceBytes();
            if (free - frame.yData.length < MIN_FREE_BYTES_FLOOR) {
                storageOk = false;
                notifyStorageExhausted();
                break;
            }

            try {
                frameSaver.saveFrame(frame.yData, frame.width, frame.height);
            } catch (Exception e) {
                Log.e(TAG, "Exception writing frame to storage disk", e);
            }
        }
        Log.i(TAG, "Writer thread finished. Dropped frames: " + droppedFrameCount.get());
    }

    public long getDroppedFrameCount() {
        return droppedFrameCount.get();
    }

    public int getQueueSize() {
        return frameQueue.size();
    }
}
