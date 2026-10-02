package app.autospiegel;

import android.util.SparseArray;

import java.util.Arrays;

/**
 * Phone side: the latest state of each kind ({@link Protocol} message type to payload).
 * {@link PhoneListener} and {@link LinkService} publish here; {@link LinkService} forwards
 * changes to the connected radio and sends everything to a radio that just connected.
 */
final class PhoneHub {
    private PhoneHub() {}

    interface Listener {
        /** Called with the hub locked; must return quickly. */
        void onUpdate(int type, byte[] payload);
    }

    private static final SparseArray<byte[]> STATE = new SparseArray<>();
    private static Listener listener;

    static synchronized void publish(int type, byte[] payload) {
        byte[] old = STATE.get(type);
        if (old != null && Arrays.equals(old, payload)) {
            return;
        }
        STATE.put(type, payload);
        if (listener != null) {
            listener.onUpdate(type, payload);
        }
    }

    static void publish(int type, String json) {
        publish(type, Protocol.utf8(json));
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    static synchronized SparseArray<byte[]> snapshot() {
        return STATE.clone();
    }
}
