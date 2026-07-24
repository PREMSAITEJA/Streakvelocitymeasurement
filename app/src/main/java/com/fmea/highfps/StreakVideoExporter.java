package com.fmea.highfps;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Encodes a sequence of same-sized Bitmaps into an H.264 MP4 using MediaCodec + MediaMuxer.
 * Used to build the "Streak Vector Flow" video requested in modification #5/#7.
 *
 * This is a synchronous, ByteBuffer-input encoder (not Surface-input) so it works down to
 * minSdk 21 without a GL context. It's CPU-bound (colour conversion happens on the Java
 * side) so a few hundred frames at moderate resolution take a handful of seconds - fine for
 * a background "Download Results" action, not for real-time preview.
 *
 * NOTE: MediaCodec behaviour (supported color formats, buffer timing) varies more across
 * real devices than anything else in this app. This has the standard structure Android
 * documents for ByteBuffer encoding, but you should test it on your actual target device(s)
 * and watch logcat for "StreakVideoExporter" if a video comes out corrupted or empty -
 * that's the one piece of this change I can't verify without a device/emulator.
 */
public class StreakVideoExporter {
    private static final String TAG = "StreakVideoExporter";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 10; // playback fps for the exported review video
    private static final int I_FRAME_INTERVAL = 1;
    private static final int BIT_RATE = 4_000_000;
    private static final long TIMEOUT_US = 10_000;

    /**
     * @param frames  ordered list of equally-sized bitmaps to encode (e.g. the per-frame
     *                streak overlay PNGs already saved into results/)
     * @param outFile destination .mp4 file
     */
    public static void export(List<Bitmap> frames, File outFile) throws Exception {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }

        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        // H.264 encoders commonly require even dimensions.
        width -= width % 2;
        height -= height % 2;

        MediaCodecInfo.CodecCapabilities caps = null;
        MediaCodec codec = MediaCodec.createEncoderByType(MIME_TYPE);
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int trackIndex = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long frameDurationUs = 1_000_000L / FRAME_RATE;
        long presentationTimeUs = 0;

        try {
            for (int i = 0; i < frames.size(); i++) {
                byte[] yuv = bitmapToYuv420Planar(frames.get(i), width, height);
                int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(yuv);
                    codec.queueInputBuffer(inputBufferIndex, 0, yuv.length, presentationTimeUs, 0);
                    presentationTimeUs += frameDurationUs;
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                while (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                }

                if (!muxerStarted) {
                    int idx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                }
            }

            // Signal end-of-stream and drain remaining encoded frames.
            int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                codec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            boolean eos = false;
            while (!eos) {
                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true;
                    }
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception e) { Log.w(TAG, "codec.stop failed", e); }
            codec.release();
            try {
                if (muxerStarted) muxer.stop();
            } catch (Exception e) {
                Log.w(TAG, "muxer.stop failed", e);
            }
            muxer.release();
        }
    }

    /** Converts an ARGB_8888 Bitmap into I420 (YUV 4:2:0 planar) bytes for the encoder. */
    private static byte[] bitmapToYuv420Planar(Bitmap bitmap, int width, int height) {
        int frameSize = width * height;
        byte[] yuv = new byte[frameSize * 3 / 2];

        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pixel = argb[j * width + i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[yIndex++] = (byte) clamp(y);

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uIndex++] = (byte) clamp(u);
                    yuv[vIndex++] = (byte) clamp(v);
                }
            }
        }
        return yuv;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
