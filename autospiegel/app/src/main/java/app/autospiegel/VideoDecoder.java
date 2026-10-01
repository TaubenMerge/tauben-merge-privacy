package app.autospiegel;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Radio side: hardware H.264 decoding straight onto the screen. */
final class VideoDecoder {
    interface ErrorListener {
        void onDecoderError(String message);
    }

    private static final String TAG = "AutoSpiegel";
    private static final String MIME = "video/avc";

    private final ErrorListener errorListener;
    private Surface surface;
    private MediaCodec codec;
    private ByteBuffer[] inputBuffers;
    private int width;
    private int height;
    private byte[] csd;
    private boolean waitForKeyFrame = true;
    private Thread outputThread;
    private volatile boolean outputRunning;

    VideoDecoder(ErrorListener errorListener) {
        this.errorListener = errorListener;
    }

    synchronized void setSurface(Surface s) {
        releaseCodec();
        surface = s;
        if (surface != null && csd != null) {
            startCodec();
        }
    }

    synchronized void configure(int w, int h, byte[] config) {
        width = w;
        height = h;
        csd = config;
        releaseCodec();
        if (surface != null) {
            startCodec();
        }
    }

    /** Forgets the current stream, e.g. after a disconnect. */
    synchronized void reset() {
        releaseCodec();
        csd = null;
    }

    synchronized void feed(int flags, long pts, byte[] data, int offset, int length) {
        if (codec == null) {
            return;
        }
        if (waitForKeyFrame && (flags & Protocol.FLAG_KEY_FRAME) == 0) {
            return;
        }
        try {
            int index = codec.dequeueInputBuffer(30000);
            if (index < 0) {
                // Decoder is behind: skip ahead to the next key frame instead of lagging.
                waitForKeyFrame = true;
                return;
            }
            ByteBuffer buffer = inputBuffer(index);
            if (buffer == null || length > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                waitForKeyFrame = true;
                return;
            }
            buffer.clear();
            buffer.put(data, offset, length);
            codec.queueInputBuffer(index, 0, length, pts, 0);
            waitForKeyFrame = false;
        } catch (RuntimeException e) {
            Log.e(TAG, "Decoder failed", e);
            releaseCodec();
            errorListener.onDecoderError(String.valueOf(e.getMessage()));
        }
    }

    @SuppressWarnings("deprecation")
    private ByteBuffer inputBuffer(int index) {
        if (Build.VERSION.SDK_INT >= 21) {
            return codec.getInputBuffer(index);
        }
        return inputBuffers[index];
    }

    @SuppressWarnings("deprecation")
    private void startCodec() {
        MediaCodec c = null;
        try {
            c = MediaCodec.createDecoderByType(MIME);
            try {
                c.configure(format(true), surface, null, 0);
            } catch (RuntimeException e) {
                c.release();
                c = MediaCodec.createDecoderByType(MIME);
                c.configure(format(false), surface, null, 0);
            }
            c.start();
            codec = c;
            inputBuffers = Build.VERSION.SDK_INT < 21 ? c.getInputBuffers() : null;
            waitForKeyFrame = true;
            outputRunning = true;
            final MediaCodec running = c;
            outputThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain(running);
                }
            }, "decoder-output");
            outputThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Cannot start decoder", e);
            if (c != null) {
                c.release();
            }
            codec = null;
            errorListener.onDecoderError(String.valueOf(e.getMessage()));
        }
    }

    private MediaFormat format(boolean lowLatency) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
        int split = secondStartCode(csd);
        if (split > 0) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(csd, 0, split)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(csd, split, csd.length)));
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 512 * 1024));
        if (lowLatency && Build.VERSION.SDK_INT >= 30) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return f;
    }

    /** Position of the PPS start code in "00 00 00 01 SPS 00 00 00 01 PPS", or -1. */
    private static int secondStartCode(byte[] b) {
        for (int i = 4; i + 3 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (outputRunning) {
                int index = c.dequeueOutputBuffer(info, 50000);
                if (index >= 0) {
                    // Show every frame immediately; low latency matters more than smoothness.
                    c.releaseOutputBuffer(index, true);
                }
            }
        } catch (RuntimeException ignored) {
            // Codec was stopped.
        }
    }

    private void releaseCodec() {
        if (codec == null) {
            return;
        }
        outputRunning = false;
        if (outputThread != null) {
            try {
                outputThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            outputThread = null;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // Already in an error state.
        }
        codec.release();
        codec = null;
        inputBuffers = null;
    }
}
