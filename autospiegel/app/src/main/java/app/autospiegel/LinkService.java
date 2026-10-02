package app.autospiegel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/**
 * Phone side: keeps the connection to the radio. Waits for it on the local network, sends it
 * everything {@link PhoneHub} holds and carries out its commands. Runs in the background,
 * the phone may be locked and in a pocket.
 */
@TargetApi(Build.VERSION_CODES.N)
public class LinkService extends Service implements PhoneHub.Listener {
    static final String ACTION_START = "app.autospiegel.START";
    static final String ACTION_STOP = "app.autospiegel.STOP";

    private static final String TAG = "AutoSpiegel";
    private static final String CHANNEL_ID = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_CONTACTS = 30;

    /** Read by {@link PhoneActivity} to show what is going on. */
    static volatile boolean running;
    static volatile String status = "";

    private Prefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread sendThread;
    private Handler sendHandler;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver batteryReceiver;
    private Intent lastBattery;
    private View launchOverlay;
    private volatile boolean stopped;

    private volatile ServerSocket server;
    private volatile ServerSocket httpServer;
    private volatile DatagramSocket beaconSocket;

    private final Object sessionLock = new Object();
    private Session session;
    private long lastFailedAuth;

    /** One connected radio. */
    private static final class Session {
        final Socket socket;
        final Protocol.Writer writer;
        final String radioName;
        volatile boolean closed;

        Session(Socket socket, Protocol.Writer writer, String radioName) {
            this.socket = socket;
            this.writer = writer;
            this.radioName = radioName;
        }

        void send(int type, byte[] payload) {
            if (closed) {
                return;
            }
            try {
                writer.send(type, payload);
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            closed = true;
            Net.close(socket);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.setAutoStart(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundWithNotification();
        } catch (RuntimeException e) {
            // Android 12+ may refuse a restart from the background; the user starts it again.
            Log.w(TAG, "Cannot run in the foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_STICKY;
        }
        prefs.setAutoStart(true);
        running = true;
        stopped = false;
        status = getString(R.string.status_waiting);

        sendThread = new HandlerThread("link-send");
        sendThread.start();
        sendHandler = new Handler(sendThread.getLooper());
        PhoneHub.setListener(this);

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent battery) {
                lastBattery = battery;
                publishStatus();
            }
        };
        lastBattery = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        publishStatus();
        publishContactsAsync();

        startThread("link-accept", new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        startThread("link-beacon", new Runnable() {
            @Override
            public void run() {
                beaconLoop();
            }
        });
        startThread("link-http", new Runnable() {
            @Override
            public void run() {
                httpLoop();
            }
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        running = false;
        status = "";
        PhoneHub.setListener(null);
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
        }
        Net.close(server);
        Net.close(httpServer);
        Net.close(beaconSocket);
        synchronized (sessionLock) {
            if (session != null) {
                session.close();
                session = null;
            }
        }
        if (sendThread != null) {
            sendThread.quitSafely();
        }
        removeLaunchOverlay();
        releaseWakeLock();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- state to the radio

    @Override
    public void onUpdate(final int type, final byte[] payload) {
        final Session s;
        synchronized (sessionLock) {
            s = session;
        }
        if (s != null) {
            sendHandler.post(new Runnable() {
                @Override
                public void run() {
                    s.send(type, payload);
                }
            });
        }
    }

    private void publishStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", Build.MODEL);
            Intent b = lastBattery;
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                o.put("battery", level < 0 ? -1 : level * 100 / Math.max(1, scale));
                o.put("charging", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            }
            o.put("listener", PhoneListener.instance != null);
            o.put("call", granted(Manifest.permission.CALL_PHONE));
            o.put("contacts", granted(Manifest.permission.READ_CONTACTS));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        PhoneHub.publish(Protocol.MSG_STATUS, o.toString());
    }

    private void publishContactsAsync() {
        startThread("link-contacts", new Runnable() {
            @Override
            public void run() {
                PhoneHub.publish(Protocol.MSG_CONTACTS, favouriteContacts().toString());
            }
        });
    }

    /** Starred contacts with their numbers, for the radio's phone page. */
    private JSONArray favouriteContacts() {
        JSONArray array = new JSONArray();
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return array;
        }
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        Set<String> seen = new HashSet<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, ContactsContract.CommonDataKinds.Phone.STARRED + "=1", null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            while (c != null && c.moveToNext() && array.length() < MAX_CONTACTS) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (number == null || !seen.add(name + "|" + number.replace(" ", ""))) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", name == null ? number : name);
                o.put("number", number);
                array.put(o);
            }
        } catch (RuntimeException | JSONException e) {
            Log.w(TAG, "Cannot read contacts", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return array;
    }

    // ---------------------------------------------------------------- commands from the radio

    private void handleCommand(JSONObject cmd) {
        PhoneListener listener = PhoneListener.instance;
        String name = cmd.optString("cmd");
        switch (name) {
            case "media":
                if (listener != null) {
                    listener.mediaCommand(cmd.optString("action"));
                } else {
                    result(getString(R.string.result_no_listener));
                }
                break;
            case "action":
                if (listener == null
                        || !listener.runAction(cmd.optString("key"), cmd.optInt("index", -1))) {
                    result(getString(R.string.result_action_failed));
                }
                break;
            case "reply":
                boolean sent = listener != null
                        && listener.reply(cmd.optString("key"), cmd.optString("text"));
                result(getString(sent ? R.string.result_reply_sent : R.string.result_reply_failed));
                break;
            case "dismiss":
                if (listener != null) {
                    listener.dismiss(cmd.optString("key"));
                }
                break;
            case "call":
                placeCall(cmd.optString("number"));
                break;
            case "navigate":
                startNavigation(cmd.optString("query"));
                break;
            default:
                break;
        }
    }

    private void placeCall(String number) {
        if (number.length() == 0) {
            return;
        }
        if (!granted(Manifest.permission.CALL_PHONE)) {
            result(getString(R.string.result_no_call_permission));
            return;
        }
        try {
            TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
            telecom.placeCall(Uri.fromParts("tel", number, null), new Bundle());
            result(getString(R.string.result_calling, number));
        } catch (SecurityException e) {
            result(getString(R.string.result_no_call_permission));
        }
    }

    /** Opens the navigation app on the phone; its directions then show up on the radio. */
    private void startNavigation(String query) {
        if (query.length() == 0) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(query)));
        if (getPackageManager().resolveActivity(intent, 0) == null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Android only lets a background app open another app while it shows an overlay.
        boolean allowed = showLaunchOverlay();
        try {
            startActivity(intent);
            result(getString(allowed ? R.string.result_navigation_started
                    : R.string.result_navigation_maybe_blocked));
        } catch (ActivityNotFoundException e) {
            result(getString(R.string.result_no_navigation_app));
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeLaunchOverlay();
            }
        }, 3000);
    }

    private boolean showLaunchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            return false;
        }
        if (launchOverlay != null) {
            return true;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        @SuppressWarnings("deprecation")
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        View view = new View(this);
        try {
            wm.addView(view, lp);
            launchOverlay = view;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot show overlay", e);
            return false;
        }
    }

    private void removeLaunchOverlay() {
        if (launchOverlay != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(launchOverlay);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            launchOverlay = null;
        }
    }

    private void result(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("message", message);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        onUpdate(Protocol.MSG_RESULT, Protocol.utf8(o.toString()));
    }

    // ---------------------------------------------------------------- networking

    private void acceptLoop() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Protocol.TCP_PORT));
            server = s;
            if (stopped) {
                Net.close(s);
                return;
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot listen", e);
            status = getString(R.string.status_error, String.valueOf(e.getMessage()));
            return;
        }
        while (!stopped) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (stopped) {
                    return;
                }
                SystemClock.sleep(200);
                continue;
            }
            startThread("link-client", new Runnable() {
                @Override
                public void run() {
                    handleClient(socket);
                }
            });
        }
    }

    private void handleClient(Socket socket) {
        Session s = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            Protocol.Reader reader = new Protocol.Reader(socket.getInputStream());
            Protocol.Writer writer = new Protocol.Writer(socket.getOutputStream());

            reader.next();
            if (reader.type != Protocol.MSG_HELLO) {
                return;
            }
            JSONObject hello = new JSONObject(reader.text());
            String radioName = hello.optString("name", "Radio");
            if (!authorize(hello.optString("code"))) {
                writer.send(Protocol.MSG_AUTH_FAIL, Protocol.EMPTY);
                status = getString(R.string.status_wrong_code, radioName);
                return;
            }
            JSONObject ok = new JSONObject();
            ok.put("name", Build.MODEL);
            writer.send(Protocol.MSG_HELLO_OK, Protocol.utf8(ok.toString()));

            s = new Session(socket, writer, radioName);
            synchronized (sessionLock) {
                if (session != null) {
                    session.close();
                }
                session = s;
                acquireWakeLock();
            }
            status = getString(R.string.status_connected, radioName);
            publishStatus();
            publishContactsAsync();
            sendSnapshot(s);

            while (!s.closed) {
                reader.next();
                if (reader.type == Protocol.MSG_COMMAND) {
                    final JSONObject cmd = new JSONObject(reader.text());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            handleCommand(cmd);
                        }
                    });
                }
            }
        } catch (IOException | JSONException e) {
            Log.i(TAG, "Radio disconnected: " + e.getMessage());
        } finally {
            Net.close(socket);
            if (s != null) {
                s.close();
                synchronized (sessionLock) {
                    if (session == s) {
                        session = null;
                        releaseWakeLock();
                        if (!stopped) {
                            status = getString(R.string.status_waiting);
                        }
                    }
                }
            }
        }
    }

    private void sendSnapshot(final Session s) {
        // Through the send thread, so it cannot overtake or interleave with updates.
        sendHandler.post(new Runnable() {
            @Override
            public void run() {
                SparseArray<byte[]> all = PhoneHub.snapshot();
                for (int i = 0; i < all.size(); i++) {
                    if (all.keyAt(i) != Protocol.MSG_RESULT) {
                        s.send(all.keyAt(i), all.valueAt(i));
                    }
                }
            }
        });
    }

    /** Checks the radio's pairing code; after a wrong code, waits two seconds (no guessing). */
    private boolean authorize(String code) {
        synchronized (sessionLock) {
            long now = SystemClock.elapsedRealtime();
            if (lastFailedAuth != 0 && now - lastFailedAuth < 2000) {
                lastFailedAuth = now;
                return false;
            }
            String trusted = prefs.trustedRadioCode();
            if (trusted.length() == 6 && trusted.equals(code)) {
                return true;
            }
            lastFailedAuth = now;
            return false;
        }
    }

    /** Announces the phone to radios on the same network and keeps the session alive. */
    private void beaconLoop() {
        byte[] message = Protocol.utf8(
                Protocol.BEACON_MAGIC + "|" + Protocol.TCP_PORT + "|" + Build.MODEL);
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            beaconSocket = socket;
        } catch (IOException e) {
            Log.w(TAG, "No beacon socket", e);
        }
        int tick = 0;
        while (!stopped) {
            DatagramSocket socket = beaconSocket;
            if (socket != null) {
                for (InetAddress address : Net.broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(message, message.length, address,
                                Protocol.BEACON_PORT));
                    } catch (IOException ignored) {
                        // Interface went away; try again next round.
                    }
                }
            }
            final Session s;
            synchronized (sessionLock) {
                s = session;
            }
            if (s != null) {
                onUpdate(Protocol.MSG_PING, Protocol.EMPTY);
            }
            if (++tick % 5 == 0) {
                // Picks up notification access or permissions granted in the meantime.
                publishStatus();
            }
            SystemClock.sleep(2000);
        }
        Net.close(beaconSocket);
    }

    /** Minimal HTTP server that hands out this app's APK, for installing it on the radio. */
    private void httpLoop() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Protocol.HTTP_PORT));
            httpServer = s;
            if (stopped) {
                Net.close(s);
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "No APK server", e);
            return;
        }
        while (!stopped) {
            Socket socket = null;
            try {
                socket = httpServer.accept();
                socket.setSoTimeout(5000);
                serveApk(socket);
            } catch (IOException e) {
                if (stopped) {
                    return;
                }
            } finally {
                Net.close(socket);
            }
        }
    }

    private void serveApk(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder request = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && request.length() < 16384) {
            request.append((char) c);
            int n = request.length();
            if (c == '\n' && n >= 2 && (request.charAt(n - 2) == '\n'
                    || (n >= 3 && request.charAt(n - 2) == '\r' && request.charAt(n - 3) == '\n'))) {
                break; // blank line: end of headers
            }
        }
        String requestLine = request.toString().split("\\r?\\n", 2)[0];
        OutputStream out = socket.getOutputStream();
        if (requestLine.contains("favicon")) {
            out.write(Protocol.utf8("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n"));
            out.flush();
            return;
        }
        File apk = new File(getApplicationInfo().sourceDir);
        String header = "HTTP/1.0 200 OK\r\n"
                + "Content-Type: application/vnd.android.package-archive\r\n"
                + "Content-Length: " + apk.length() + "\r\n"
                + "Content-Disposition: attachment; filename=\"AutoSpiegel.apk\"\r\n"
                + "Connection: close\r\n\r\n";
        out.write(Protocol.utf8(header));
        FileInputStream file = new FileInputStream(apk);
        try {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = file.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } finally {
            Net.close(file);
        }
        out.flush();
    }

    // ---------------------------------------------------------------- helpers

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = legacyBuilder();
        }
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, PhoneActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, LinkService.class).setAction(ACTION_STOP), piFlags);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_stop), stop).build());
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder legacyBuilder() {
        return new Notification.Builder(this);
    }

    /** Keeps the phone reachable while a radio is connected, even with the screen off. */
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSpiegel:link");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
