package app.autospiegel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Wire format shared by phone and radio.
 *
 * <p>The radio connects to the phone over TCP. Every message is framed as
 * {@code [type: 1 byte][payload length: 4 bytes][payload]}, big endian. Most payloads are
 * UTF-8 JSON; pictures are JPEG or PNG bytes.
 *
 * <p>The phone does not send its screen. It sends what the radio needs to draw its own
 * landscape interface: now playing, navigation instructions, messages and favourite contacts.
 * The radio sends back commands.
 */
final class Protocol {
    private Protocol() {}

    static final int VERSION = 2;
    static final int TCP_PORT = 47800;
    static final int BEACON_PORT = 47801;
    static final int HTTP_PORT = 47802;
    static final String BEACON_MAGIC = "AUTOSPIEGEL2";
    static final Charset UTF8 = Charset.forName("UTF-8");

    /** Radio to phone, JSON: {"v", "code", "name"}. */
    static final int MSG_HELLO = 1;
    /** Phone to radio, JSON: {"name"}. */
    static final int MSG_HELLO_OK = 2;
    /** Phone to radio: unknown pairing code. */
    static final int MSG_AUTH_FAIL = 3;
    /** Both directions: keep-alive. */
    static final int MSG_PING = 9;

    /** Phone to radio, JSON: {"name", "battery", "charging", "listener", "call", "contacts"}. */
    static final int MSG_STATUS = 20;
    /**
     * Phone to radio, JSON: {"active", "app", "title", "artist", "album", "playing",
     * "position", "duration"} with times in milliseconds.
     */
    static final int MSG_MEDIA = 21;
    /** Phone to radio: album art as JPEG, empty if there is none. */
    static final int MSG_MEDIA_ART = 22;
    /** Phone to radio, JSON: {"active", "app", "title", "text", "sub"}. */
    static final int MSG_NAV = 23;
    /** Phone to radio: next-turn picture as PNG, empty if there is none. */
    static final int MSG_NAV_ICON = 24;
    /**
     * Phone to radio, JSON array, newest first: {"key", "app", "title", "text", "time",
     * "call", "reply", "actions": [titles]}.
     */
    static final int MSG_NOTIFICATIONS = 25;
    /** Phone to radio, JSON array: {"name", "number"}. */
    static final int MSG_CONTACTS = 26;
    /**
     * Radio to phone, JSON {"cmd", ...}:
     * media {"action": play|pause|toggle|next|prev}, action {"key", "index"},
     * reply {"key", "text"}, dismiss {"key"}, call {"number"}, navigate {"query"}.
     */
    static final int MSG_COMMAND = 30;
    /** Phone to radio, JSON: {"message"} to show briefly. */
    static final int MSG_RESULT = 31;

    static final byte[] EMPTY = new byte[0];
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    /** Writes framed messages; safe to use from several threads. */
    static final class Writer {
        private final DataOutputStream out;

        Writer(OutputStream os) {
            out = new DataOutputStream(new BufferedOutputStream(os, 32 * 1024));
        }

        synchronized void send(int type, byte[] payload) throws IOException {
            out.writeByte(type);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    /** Reads framed messages into one reused buffer. Not thread safe. */
    static final class Reader {
        private final DataInputStream in;
        byte[] buffer = new byte[16 * 1024];
        int type;
        int length;

        Reader(InputStream is) {
            in = new DataInputStream(new BufferedInputStream(is, 32 * 1024));
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

        String text() {
            return new String(buffer, 0, length, UTF8);
        }

        byte[] bytes() {
            byte[] copy = new byte[length];
            System.arraycopy(buffer, 0, copy, 0, length);
            return copy;
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(UTF8);
    }
}
