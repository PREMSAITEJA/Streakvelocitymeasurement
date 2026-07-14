package com.example.highfps;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * StorageManager: Manages the top-level Media root and reports storage stats.
 *
 * Uses app-specific external media storage (scoped storage compatible):
 * /Android/media/com.example.highfps/Media/<sessionName>/frames/
 *
 * Individual session folder layout (frames/parameters/Averaged_data/results) is
 * owned by SessionPathManager - this class only tracks the shared Media root and
 * free-space estimates, since those aren't specific to any one session.
 */
public class StorageManager {
    private static final String TAG = "StorageManager";

    private final Context context;
    private File mediaRootDir;

    public StorageManager(Context context) {
        this.context = context;
    }

    /**
     * Initialize the shared Media root directory (Media/<sessionName>/... folders
     * are created per-session via SessionPathManager once a session name is known).
     *
     * @return true if directory is ready, false if creation failed
     */
    public boolean initFramesDirectory() {
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs == null || mediaDirs.length == 0) {
                Log.e(TAG, "External media storage not available");
                return false;
            }

            File externalMediaDir = mediaDirs[0];
            if (externalMediaDir == null) {
                Log.e(TAG, "External media dir is null");
                return false;
            }

            mediaRootDir = SessionPathManager.getMediaRoot(context);

            if (!mediaRootDir.canWrite()) {
                Log.e(TAG, "Media root directory is not writable");
                return false;
            }

            Log.i(TAG, "Media storage ready: " + mediaRootDir.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error initializing Media root directory", e);
            return false;
        }
    }

    /** Root folder containing one subfolder per recording session. */
    public File getFramesDirectory() {
        return mediaRootDir;
    }

    public double getAvailableStorageGB() {
        if (mediaRootDir == null) return 0;
        try {
            long availableBytes = mediaRootDir.getFreeSpace();
            return availableBytes / (1024.0 * 1024.0 * 1024.0);
        } catch (Exception e) {
            Log.e(TAG, "Error checking available space", e);
            return 0;
        }
    }

    public long estimateRecordingTimeSeconds() {
        double availableGB = getAvailableStorageGB();
        double bytesPerSecond = 528 * 1024 * 1024;  // ~528 MB/s
        double availableBytes = availableGB * 1024 * 1024 * 1024;
        return (long) (availableBytes / bytesPerSecond);
    }

    public int getFrameCount() {
        if (mediaRootDir == null) return 0;
        File[] files = mediaRootDir.listFiles((dir, name) -> name.endsWith(".tiff"));
        return files != null ? files.length : 0;
    }

    public boolean clearFramesDirectory() {
        if (mediaRootDir == null) return false;
        try {
            File[] files = mediaRootDir.listFiles((dir, name) -> name.endsWith(".tiff"));
            if (files == null) return true;
            int deletedCount = 0;
            for (File file : files) {
                if (file.delete()) deletedCount++;
            }
            Log.i(TAG, "Deleted " + deletedCount + " TIFF files");
            return deletedCount == files.length;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing frames directory", e);
            return false;
        }
    }

    public String getStorageStatus() {
        if (mediaRootDir == null) return "Storage not initialized";
        double availableGB = getAvailableStorageGB();
        long recordTimeSeconds = estimateRecordingTimeSeconds();
        int frameCount = getFrameCount();
        long minutes = recordTimeSeconds / 60;
        long seconds = recordTimeSeconds % 60;
        return String.format(
                "Available: %.1f GB (max ~%d:%02d recording)\nFrames: %d",
                availableGB, minutes, seconds, frameCount
        );
    }
}
