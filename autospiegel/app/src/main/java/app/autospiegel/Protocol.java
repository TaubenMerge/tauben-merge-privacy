package app.autospiegel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wire format shared by phone and radio.
 *
 * <p>The radio connects to the phone over TCP. Every message is framed as
 * {@code [type: 1 byte][payload length: 4 bytes][payload]}, big endian.
 */
final class Protocol {
    private Protocol() {}

    static final int VERSION = 1;
    static final int TCP_PORT = 47800;
    static final int BEACON_PORT = 47801;
    static final int HTTP_PORT = 47802;
    static final String BEACON_MAGIC = "AUTOSPIEGEL1";

    /** Radio to phone: version, pairing code, max video width/height, radio name. */
    static final int MSG_HELLO = 1;
    /** Phone to radio: phone name, whether touch control is enabled. */
    static final int MSG_HELLO_OK = 2;
    /** Phone to radio: unknown pairing code. */
    static final int MSG_AUTH_FAIL = 3;
    /** Phone to radio: width, height, H.264 codec config (SPS/PPS). */
    static final int MSG_VIDEO_CONFIG = 4;
    /** Phone to radio: codec flags, presentation time, H.264 access unit. */
    static final int MSG_VIDEO_FRAME = 5;
    /** Radio to phone: action, pointer id, x and y in 0..1 of the video, event time in ms. */
    static final int MSG_TOUCH = 6;
    /** Radio to phone: one of the ACTION_* constants. */
    static final int MSG_GLOBAL_ACTION = 7;
    /** Radio to phone: the decoder was recreated and needs a fresh config and key frame. */
    static final int MSG_KEYFRAME_REQUEST = 8;
    /** Both directions: keep-alive. */
    static final int MSG_PING = 9;

    static final int TOUCH_DOWN = 0;
    static final int TOUCH_MOVE = 1;
    static final int TOUCH_UP = 2;
    static final int TOUCH_CANCEL = 3;

    static final int ACTION_BACK = 1;
    static final int ACTION_HOME = 2;
    static final int ACTION_RECENTS = 3;
    static final int ACTION_NOTIFICATIONS = 4;

    /** Codec buffer flag for key frames (same value on all API levels). */
    static final int FLAG_KEY_FRAME = 1;

    static final byte[] EMPTY = new byte[0];
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    /** Writes framed messages; safe to use from several threads. */
    static final class Writer {
        private final DataOutputStream out;

        Writer(OutputStream os) {
            out = new DataOutputStream(new BufferedOutputStream(os, 64 * 1024));
        }

        synchronized void send(int type, byte[] payload) throws IOException {
            out.writeByte(type);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }

        synchronized void sendVideoConfig(int width, int height, byte[] csd, int length)
                throws IOException {
            out.writeByte(MSG_VIDEO_CONFIG);
            out.writeInt(8 + length);
            out.writeInt(width);
            out.writeInt(height);
            out.write(csd, 0, length);
            out.flush();
        }

        synchronized void sendVideoFrame(int flags, long pts, byte[] data, int length)
                throws IOException {
            out.writeByte(MSG_VIDEO_FRAME);
            out.writeInt(12 + length);
            out.writeInt(flags);
            out.writeLong(pts);
            out.write(data, 0, length);
            out.flush();
        }
    }

    /** Reads framed messages into one reused buffer. Not thread safe. */
    static final class Reader {
        private final DataInputStream in;
        byte[] buffer = new byte[64 * 1024];
        int type;
        int length;

        Reader(InputStream is) {
            in = new DataInputStream(new BufferedInputStream(is, 64 * 1024));
        }

        void next() throws IOException {
            type = in.readUnsignedByte();
            length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD) {
                throw new IOException("Invalid message length " + length);
            }
            if (buffer.length < length) {
                buffer = new byte[Math.min(MAX_PAYLOAD, Math.max(length, buffer.length * 2))];
            }
            in.readFully(buffer, 0, length);
        }

        DataInputStream payload() {
            return new DataInputStream(new ByteArrayInputStream(buffer, 0, length));
        }
    }

    static byte[] hello(String code, int maxWidth, int maxHeight, String name) {
        Payload p = new Payload();
        try {
            p.data.writeInt(VERSION);
            p.data.writeUTF(code);
            p.data.writeInt(maxWidth);
            p.data.writeInt(maxHeight);
            p.data.writeUTF(name);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return p.bytes();
    }

    static byte[] helloOk(String name, boolean controlEnabled) {
        Payload p = new Payload();
        try {
            p.data.writeUTF(name);
            p.data.writeBoolean(controlEnabled);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return p.bytes();
    }

    static byte[] touch(int action, int pointer, float x, float y, long time) {
        Payload p = new Payload();
        try {
            p.data.writeByte(action);
            p.data.writeByte(pointer);
            p.data.writeFloat(x);
            p.data.writeFloat(y);
            p.data.writeLong(time);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return p.bytes();
    }

    static byte[] globalAction(int action) {
        Payload p = new Payload();
        try {
            p.data.writeInt(action);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return p.bytes();
    }

    static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 24)
                | ((b[offset + 1] & 0xff) << 16)
                | ((b[offset + 2] & 0xff) << 8)
                | (b[offset + 3] & 0xff);
    }

    static long readLong(byte[] b, int offset) {
        return ((long) readInt(b, offset) << 32) | (readInt(b, offset + 4) & 0xffffffffL);
    }

    private static final class Payload {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        final DataOutputStream data = new DataOutputStream(bytes);

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
