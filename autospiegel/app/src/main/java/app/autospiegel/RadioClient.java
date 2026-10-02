package app.autospiegel;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Radio side: finds the phone on the local network, receives the video and sends touches.
 * Runs between {@link #start()} and {@link #stop()}; reconnects on its own.
 */
final class RadioClient {
    interface Listener {
        /** New status text, or null to hide it. Called on a background thread. */
        void onStatus(String text);

        /** Size of the incoming video. Called on a background thread. */
        void onVideoSize(int width, int height);

        /** Whether the phone accepts touches. Reported as true again after a disconnect. */
        void onControlState(boolean enabled);

        /** Pixel size of the area the video may use. */
        int[] videoAreaSize();
    }

    private static final String TAG = "AutoSpiegel";
    private static final int NOT_FOUND = 0;
    private static final int CONNECTED = 1;
    private static final int AUTH_FAILED = 2;

    private final Context context;
    private final Prefs prefs;
    private final Listener listener;
    private final VideoDecoder decoder;
    private volatile Worker worker;

    RadioClient(Context context, Prefs prefs, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
        this.decoder = new VideoDecoder(new VideoDecoder.ErrorListener() {
            @Override
            public void onDecoderError(String message) {
                RadioClient.this.listener.onStatus(
                        RadioClient.this.context.getString(R.string.radio_decoder_error, message));
            }
        });
    }

    void start() {
        if (worker == null) {
            worker = new Worker();
            worker.start();
        }
    }

    void stop() {
        Worker w = worker;
        worker = null;
        if (w != null) {
            w.shutdown();
        }
    }

    void setSurface(Surface surface) {
        decoder.setSurface(surface);
        if (surface != null) {
            send(Protocol.MSG_KEYFRAME_REQUEST, Protocol.EMPTY);
        }
    }

    void sendTouch(int action, int pointer, float x, float y, long time) {
        send(Protocol.MSG_TOUCH, Protocol.touch(action, pointer, x, y, time));
    }

    void sendGlobalAction(int action) {
        send(Protocol.MSG_GLOBAL_ACTION, Protocol.globalAction(action));
    }

    private void send(int type, byte[] payload) {
        Worker w = worker;
        if (w != null) {
            w.send(type, payload);
        }
    }

    /** One start/stop cycle with its own threads and sockets. */
    private final class Worker {
        private volatile boolean active = true;
        private volatile Socket socket;
        private volatile Protocol.Writer writer;
        private volatile DatagramSocket beaconSocket;
        private volatile String beaconAddress;
        private volatile long beaconTime;
        private final HandlerThread sendThread = new HandlerThread("radio-send");
        private Handler sendHandler;
        private WifiManager.MulticastLock multicastLock;

        void start() {
            WifiManager wifi = wifiManager();
            if (wifi != null) {
                try {
                    multicastLock = wifi.createMulticastLock("AutoSpiegel");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                } catch (RuntimeException e) {
                    multicastLock = null;
                }
            }
            sendThread.start();
            sendHandler = new Handler(sendThread.getLooper());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connectLoop();
                }
            }, "radio-connect").start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    beaconLoop();
                }
            }, "radio-beacon").start();
        }

        void shutdown() {
            active = false;
            final Socket s = socket;
            final DatagramSocket b = beaconSocket;
            // Closing sockets may touch the network; keep it off the UI thread.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Net.close(s);
                    Net.close(b);
                }
            }).start();
            sendThread.quit();
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        }

        void send(final int type, final byte[] payload) {
            final Protocol.Writer w = writer;
            if (w == null || !active) {
                return;
            }
            sendHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.send(type, payload);
                    } catch (IOException e) {
                        Net.close(socket);
                    }
                }
            });
        }

        private void connectLoop() {
            showSearching();
            while (active) {
                int result = NOT_FOUND;
                for (String host : candidates()) {
                    if (!active) {
                        return;
                    }
                    Socket s = new Socket();
                    socket = s;
                    try {
                        s.connect(new InetSocketAddress(host, Protocol.TCP_PORT), 1500);
                    } catch (IOException e) {
                        Net.close(s);
                        continue;
                    }
                    result = runSession(s, host);
                    break;
                }
                if (!active) {
                    return;
                }
                if (result != AUTH_FAILED) {
                    // Also refreshes the shown IP once the radio joins a network.
                    showSearching();
                }
                SystemClock.sleep(1000);
            }
        }

        /** Returns {@link #AUTH_FAILED} if the phone does not know this radio's code yet. */
        private int runSession(Socket s, String host) {
            try {
                s.setTcpNoDelay(true);
                s.setSoTimeout(15000);
                Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
                Protocol.Writer w = new Protocol.Writer(s.getOutputStream());
                int[] area = listener.videoAreaSize();
                w.send(Protocol.MSG_HELLO,
                        Protocol.hello(prefs.radioCode(), area[0], area[1], Build.MODEL));

                reader.next();
                if (reader.type == Protocol.MSG_AUTH_FAIL) {
                    listener.onStatus(context.getString(R.string.radio_pair, prefs.radioCode()));
                    SystemClock.sleep(2000);
                    return AUTH_FAILED;
                }
                if (reader.type != Protocol.MSG_HELLO_OK) {
                    return CONNECTED;
                }
                DataInputStream ok = reader.payload();
                String phoneName = ok.readUTF();
                boolean controlEnabled = ok.readBoolean();
                prefs.setLastPhoneIp(host);
                writer = w;
                listener.onStatus(context.getString(R.string.radio_connected, phoneName));
                listener.onControlState(controlEnabled);

                long lastPing = 0;
                while (active) {
                    reader.next();
                    if (!active) {
                        break;
                    }
                    if (reader.type == Protocol.MSG_VIDEO_FRAME && reader.length >= 12) {
                        decoder.feed(Protocol.readInt(reader.buffer, 0),
                                Protocol.readLong(reader.buffer, 4),
                                reader.buffer, 12, reader.length - 12);
                    } else if (reader.type == Protocol.MSG_VIDEO_CONFIG && reader.length >= 8) {
                        int width = Protocol.readInt(reader.buffer, 0);
                        int height = Protocol.readInt(reader.buffer, 4);
                        byte[] csd = new byte[reader.length - 8];
                        System.arraycopy(reader.buffer, 8, csd, 0, csd.length);
                        listener.onVideoSize(width, height);
                        decoder.configure(width, height, csd);
                        listener.onStatus(null);
                    } else if (reader.type == Protocol.MSG_CONTROL_STATE && reader.length >= 1) {
                        listener.onControlState(reader.buffer[0] != 0);
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPing > 2000) {
                        lastPing = now;
                        send(Protocol.MSG_PING, Protocol.EMPTY);
                    }
                }
            } catch (IOException e) {
                Log.i(TAG, "Connection ended: " + e.getMessage());
            } finally {
                writer = null;
                Net.close(s);
                listener.onControlState(true);
                // After a quick stop/start a newer worker may already own the decoder.
                Worker current = worker;
                if (current == null || current == this) {
                    decoder.reset();
                }
            }
            return CONNECTED;
        }

        /** Where the phone might be, most likely first. */
        private Set<String> candidates() {
            Set<String> hosts = new LinkedHashSet<>();
            String manual = prefs.manualPhoneIp();
            if (manual.length() > 0) {
                hosts.add(manual);
            }
            String beacon = beaconAddress;
            if (beacon != null && SystemClock.elapsedRealtime() - beaconTime < 5000) {
                hosts.add(beacon);
            }
            // With the phone's hotspot, the phone is the radio's gateway.
            String gateway = gatewayIp();
            if (gateway != null) {
                hosts.add(gateway);
            }
            String last = prefs.lastPhoneIp();
            if (last.length() > 0) {
                hosts.add(last);
            }
            return hosts;
        }

        private void beaconLoop() {
            String prefix = Protocol.BEACON_MAGIC + "|";
            byte[] buffer = new byte[512];
            while (active) {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.setBroadcast(true);
                    ds.bind(new InetSocketAddress(Protocol.BEACON_PORT));
                    ds.setSoTimeout(1000);
                    beaconSocket = ds;
                    while (active) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try {
                            ds.receive(packet);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        String message =
                                new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        if (message.startsWith(prefix) && packet.getAddress() != null) {
                            beaconAddress = packet.getAddress().getHostAddress();
                            beaconTime = SystemClock.elapsedRealtime();
                        }
                    }
                } catch (IOException e) {
                    if (active) {
                        SystemClock.sleep(1000);
                    }
                } finally {
                    Net.close(ds);
                }
            }
        }

        private void showSearching() {
            List<String> ips = Net.lanIps();
            String ip = ips.isEmpty() ? context.getString(R.string.radio_no_ip) : ips.get(0);
            listener.onStatus(context.getString(R.string.radio_searching, prefs.radioCode(), ip));
        }
    }

    private String gatewayIp() {
        WifiManager wifi = wifiManager();
        if (wifi == null) {
            return null;
        }
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (dhcp == null || dhcp.gateway == 0) {
            return null;
        }
        return Net.intToIp(dhcp.gateway);
    }

    private WifiManager wifiManager() {
        return (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }
}
