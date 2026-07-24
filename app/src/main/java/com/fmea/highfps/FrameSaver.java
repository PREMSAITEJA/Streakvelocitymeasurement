package com.fmea.highfps;

import android.content.Context;
import android.media.Image;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * FrameSaver: converts incoming YUV_420_888 Image frames or raw bytes to grayscale TIFF files
 * and stores them under a timestamped session folder.
 *
 * OPTIMIZATIONS & FEATURES:
 * - Direct row-by-row streaming to file (no large frame buffer allocation in heap memory).
 * - Immediate image buffer releases to keep the Camera2 pipeline streaming without frame drops.
 * - Dynamic Orientation Protection: Flips and saves portrait or landscape matrices natively.
 * - Critical Storage Guardian: Halts recording instantly when storage falls below 10MB to protect device integrity.
 */
public class FrameSaver {
	private static final String TAG = "HighFPSRecorder";
	private static final long MIN_STORAGE_THRESHOLD_BYTES = 10 * 1024 * 1024; // 10 MB fallback safety margin

	private final Context context;
	private final String sessionName;
	private final File sessionDir;
	private final File framesDir;
	private final WindowManager windowManager;
	private int frameCounter = 0;
	private boolean isStorageExhausted = false;

	public FrameSaver(Context ctx) throws IOException {
		this.context = ctx.getApplicationContext();
		this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

		this.sessionName = "session_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
		this.sessionDir = SessionPathManager.getSessionDir(this.context, sessionName);
		this.framesDir = SessionPathManager.getFramesDirectory(this.context, sessionName);

		if (!framesDir.exists() && !framesDir.mkdirs()) {
			throw new IOException("Failed to create frames folder: " + framesDir.getAbsolutePath());
		}
		Log.i(TAG, "FrameSaver session created: " + sessionDir.getAbsolutePath());
	}

	/** Session identifier, e.g. "session_20260714_134500" */
	public String getSessionName() {
		return sessionName;
	}

	/**
	 * Save a single image frame directly to TIFF while dynamically accounting for device rotation.
	 */
	public synchronized void saveFrame(Image image) {
		if (image == null || isStorageExhausted) return;

		// Check explicit remaining space on current storage volume block
		if (getFreeSpaceBytes() < MIN_STORAGE_THRESHOLD_BYTES) {
			isStorageExhausted = true;
			Log.e(TAG, "Storage exhausted (under 10MB remaining). Halting frame records safely.");
			return;
		}

		try {
			Image.Plane yPlane = image.getPlanes()[0];
			ByteBuffer yBuffer = yPlane.getBuffer();
			int width = image.getWidth();
			int height = image.getHeight();
			int rowStride = yPlane.getRowStride();

			// Detect matching display orientation status
			int rotation = windowManager.getDefaultDisplay().getRotation();
			boolean isPortrait = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180);

			frameCounter++;
			String fileName = String.format(Locale.US, "frame_%05d_%d.tiff", frameCounter, System.currentTimeMillis());
			File outFile = new File(framesDir, fileName);

			writeUncompressed8bitGrayTIFFStreamed(outFile, yBuffer, rowStride, width, height, isPortrait);
			Log.d(TAG, "Saved frame " + frameCounter + " [Portrait=" + isPortrait + "] (" + width + "x" + height + ")");
		} catch (Exception e) {
			Log.e(TAG, "Error saving frame via camera stream pipeline", e);
		} finally {
			try { image.close(); } catch (Exception ignored) {}
		}
	}

	/**
	 * Save a frame from an already-copied, tight-packed (no row stride padding) grayscale byte array.
	 */
	public synchronized void saveFrame(byte[] yData, int width, int height) {
		if (yData == null || isStorageExhausted) return;

		if (getFreeSpaceBytes() < MIN_STORAGE_THRESHOLD_BYTES) {
			isStorageExhausted = true;
			Log.e(TAG, "Storage exhausted (under 10MB remaining). Halting buffer records safely.");
			return;
		}

		try {
			int rotation = windowManager.getDefaultDisplay().getRotation();
			boolean isPortrait = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180);

			frameCounter++;
			String fileName = String.format(Locale.US, "frame_%05d_%d.tiff", frameCounter, System.currentTimeMillis());
			File outFile = new File(framesDir, fileName);

			// For raw packed arrays, rowStride is exactly the original width
			writeUncompressed8bitGrayTIFFStreamed(outFile, ByteBuffer.wrap(yData), width, width, height, isPortrait);
		} catch (Exception e) {
			Log.e(TAG, "Error saving frame from copypool buffer", e);
		}
	}

	/** Free bytes remaining on the volume that holds this session's frames. */
	public long getFreeSpaceBytes() {
		try {
			return framesDir.getUsableSpace();
		} catch (Exception e) {
			Log.e(TAG, "Error checking space allocation limits", e);
			return 0;
		}
	}

	public synchronized int getFrameCount() {
		return frameCounter;
	}

	public File getSessionDir() {
		return sessionDir;
	}

	/** Returns if writing was forcefully turned off by safety rules */
	public boolean isStorageExhausted() {
		return isStorageExhausted;
	}

	/**
	 * Memory-optimized TIFF writer: streams transformations row-by-row without massive buffer re-allocations.
	 */
	private void writeUncompressed8bitGrayTIFFStreamed(File outFile, ByteBuffer yBuffer, int rowStride, int srcWidth, int srcHeight, boolean isPortrait) throws IOException {
		OutputStream fos = null;
		try {
			// CRITICAL: 128KB buffer significantly speeds up disk I/O on Android
			fos = new BufferedOutputStream(new FileOutputStream(outFile), 131072);

			// Compute output width and height layout properties depending on mode
			int outWidth = isPortrait ? srcHeight : srcWidth;
			int outHeight = isPortrait ? srcWidth : srcHeight;

			int headerSize = 8;
			int numEntries = 9;
			int ifdSize = 2 + numEntries * 12 + 4;
			int imageDataOffset = headerSize + ifdSize;
			int bytesPerStrip = outWidth * outHeight;

			// Little Endian Magic Byte Header Assignment
			fos.write('I'); fos.write('I');
			writeShortLE(fos, 42);
			writeIntLE(fos, 8);

			// Write Image File Directory Metadata tags
			writeShortLE(fos, numEntries);
			writeTagLE(fos, 256, 4, 1, outWidth);            // ImageWidth
			writeTagLE(fos, 257, 4, 1, outHeight);           // ImageLength
			writeTagLE(fos, 258, 3, 1, 8);                   // BitsPerSample
			writeTagLE(fos, 259, 3, 1, 1);                   // Compression (None)
			writeTagLE(fos, 262, 3, 1, 1);                   // PhotometricInterpretation
			writeTagLE(fos, 273, 4, 1, imageDataOffset);     // StripOffsets
			writeTagLE(fos, 278, 4, 1, outHeight);           // RowsPerStrip
			writeTagLE(fos, 279, 4, 1, bytesPerStrip);       // StripByteCounts
			writeTagLE(fos, 277, 3, 1, 1);                   // SamplesPerPixel

			writeIntLE(fos, 0); // Terminate IFD offset chain

			ByteBuffer dupBuffer = yBuffer.duplicate();
			dupBuffer.rewind();

			if (!isPortrait) {
				// Direct native streaming pass-through for landscape layouts
				byte[] rowBuffer = new byte[outWidth];
				for (int rowIdx = 0; rowIdx < outHeight; rowIdx++) {
					int pos = rowIdx * rowStride;
					dupBuffer.position(pos);
					int bytesToRead = Math.min(outWidth, dupBuffer.remaining());
					dupBuffer.get(rowBuffer, 0, bytesToRead);
					fos.write(rowBuffer, 0, bytesToRead);
				}
			} else {
				// On-the-fly streaming transformation rotate mapping to save real portrait frames
				byte[] rowBuffer = new byte[outWidth]; // matching outWidth (srcHeight)
				for (int outRow = 0; outRow < outHeight; outRow++) { // matching outHeight (srcWidth)
					int srcCol = outRow;
					for (int outCol = 0; outCol < outWidth; outCol++) {
						int srcRow = srcHeight - 1 - outCol; // Clockwise 90-degree mapping computation
						int pos = (srcRow * rowStride) + srcCol;
						rowBuffer[outCol] = dupBuffer.get(pos);
					}
					fos.write(rowBuffer, 0, outWidth);
				}
			}
			fos.flush();
		} finally {
			if (fos != null) {
				try { fos.close(); } catch (IOException ignored) {}
			}
		}
	}

	private static void writeShortLE(OutputStream os, int value) throws IOException {
		os.write(value & 0xFF);
		os.write((value >> 8) & 0xFF);
	}

	private static void writeIntLE(OutputStream os, int value) throws IOException {
		os.write(value & 0xFF);
		os.write((value >> 8) & 0xFF);
		os.write((value >> 16) & 0xFF);
		os.write((value >> 24) & 0xFF);
	}

	private static void writeTagLE(OutputStream os, int tag, int type, int count, int valueOrOffset) throws IOException {
		writeShortLE(os, tag);
		writeShortLE(os, type);
		writeIntLE(os, count);
		writeIntLE(os, valueOrOffset);
	}
}
