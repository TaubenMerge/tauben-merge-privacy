package app.autospiegel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
 * Radio side: finds the phone on the local network, receives its state and sends commands.
 * Runs between {@link #start()} and {@link #stop()}; reconnects on its own.
 */
final class RadioClient {
    static final int STATE_SEARCHING = 0;
    static final int STATE_PAIRING = 1;
    static final int STATE_CONNECTED = 2;

    interface Listener {
        /**
         * Connection changed. {@code detail} is this radio's IP while searching, the pairing
         * code while pairing and the phone's name when connected. Background thread.
         */
        void onConnection(int state, String detail);

        /**
         * A message from the phone: a {@link JSONObject}, a {@link JSONArray} or a
         * {@link Bitmap} (null when the picture was removed). Background thread.
         */
        void onMessage(int type, Object value);
    }

    private static final String TAG = "AutoSpiegel";

    private final Context context;
    private final Prefs prefs;
    private final Listener listener;
    private volatile Worker worker;

    RadioClient(Context context, Prefs prefs, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
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

    /** Sends a command (see {@link Protocol#MSG_COMMAND}); dropped while disconnected. */
    boolean command(JSONObject cmd) {
        Worker w = worker;
        return w != null && w.send(Protocol.MSG_COMMAND, Protocol.utf8(cmd.toString()));
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

        boolean send(final int type, final byte[] payload) {
            final Protocol.Writer w = writer;
            if (w == null || !active) {
                return false;
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
            return true;
        }

        private void connectLoop() {
            showSearching();
            while (active) {
                boolean pairing = false;
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
                    pairing = runSession(s, host);
                    break;
                }
                if (!active) {
                    return;
                }
                if (!pairing) {
                    // Also refreshes the shown IP once the radio joins a network.
                    showSearching();
                }
                SystemClock.sleep(1000);
            }
        }

        /** Returns true if the phone does not know this radio's code yet. */
        private boolean runSession(Socket s, String host) {
            boolean connected = false;
            try {
                s.setTcpNoDelay(true);
                s.setSoTimeout(15000);
                Protocol.Reader reader = new Protocol.Reader(s.getInputStream());
                Protocol.Writer w = new Protocol.Writer(s.getOutputStream());
                JSONObject hello = new JSONObject();
                hello.put("v", Protocol.VERSION);
                hello.put("code", prefs.radioCode());
                hello.put("name", Build.MODEL);
                w.send(Protocol.MSG_HELLO, Protocol.utf8(hello.toString()));

                reader.next();
                if (reader.type == Protocol.MSG_AUTH_FAIL) {
                    listener.onConnection(STATE_PAIRING, prefs.radioCode());
                    SystemClock.sleep(2000);
                    return true;
                }
                if (reader.type != Protocol.MSG_HELLO_OK) {
                    return false;
                }
                String phoneName = new JSONObject(reader.text()).optString("name", "Handy");
                prefs.setLastPhoneIp(host);
                writer = w;
                connected = true;
                listener.onConnection(STATE_CONNECTED, phoneName);

                long lastPing = 0;
                while (active) {
                    reader.next();
                    if (!active) {
                        break;
                    }
                    deliver(reader);
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPing > 2000) {
                        lastPing = now;
                        send(Protocol.MSG_PING, Protocol.EMPTY);
                    }
                }
            } catch (IOException | JSONException e) {
                Log.i(TAG, "Connection ended: " + e.getMessage());
            } finally {
                writer = null;
                Net.close(s);
            }
            if (connected && active) {
                listener.onConnection(STATE_SEARCHING, ownIp());
            }
            return false;
        }

        private void deliver(Protocol.Reader reader) {
            try {
                switch (reader.type) {
                    case Protocol.MSG_STATUS:
                    case Protocol.MSG_MEDIA:
                    case Protocol.MSG_NAV:
                    case Protocol.MSG_RESULT:
                        listener.onMessage(reader.type, new JSONObject(reader.text()));
                        break;
                    case Protocol.MSG_NOTIFICATIONS:
                    case Protocol.MSG_CONTACTS:
                        listener.onMessage(reader.type, new JSONArray(reader.text()));
                        break;
                    case Protocol.MSG_MEDIA_ART:
                    case Protocol.MSG_NAV_ICON:
                        Bitmap picture = reader.length == 0 ? null
                                : BitmapFactory.decodeByteArray(reader.buffer, 0, reader.length);
                        listener.onMessage(reader.type, picture);
                        break;
                    default:
                        // Ping or a message from a newer version.
                        break;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Bad message " + reader.type, e);
            }
        }

        /** Where the phone might be, most likely first. */
        private Set<String> candidates() {
            Set<String> hosts = new LinkedHashSet<>();
            String manual = prefs.manualPhoneIp();
            if (manual.length() > 0) {
                hosts.add(manual);
            }
            String beacon = beaconAddress;
            if (beacon != null && SystemClock.elapsedRealtime() - beaconTime < 6000) {
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
                                new String(packet.getData(), 0, packet.getLength(), Protocol.UTF8);
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
            listener.onConnection(STATE_SEARCHING, ownIp());
        }
    }

    private String ownIp() {
        List<String> ips = Net.lanIps();
        return ips.isEmpty() ? "" : ips.get(0);
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
