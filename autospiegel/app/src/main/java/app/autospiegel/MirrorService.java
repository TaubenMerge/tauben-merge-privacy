package app.autospiegel;

import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.io.DataInputStream;
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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * Phone side: captures the screen, encodes it as H.264 and streams it to the radio. Also
 * announces itself on the local network and serves its own APK so the radio can install it.
 */
@TargetApi(Build.VERSION_CODES.N)
public class MirrorService extends Service {
    static final String ACTION_START = "app.autospiegel.START";
    static final String ACTION_STOP = "app.autospiegel.STOP";
    /** Re-applies the orientation setting while running. */
    static final String ACTION_ORIENTATION = "app.autospiegel.ORIENTATION";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "AutoSpiegel";
    private static final String CHANNEL_ID = "mirror";
    private static final int NOTIFICATION_ID = 1;
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int MAX_LONG_SIDE = 1280;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Read by {@link PhoneActivity} to show what is going on. */
    static volatile boolean running;
    static volatile String status = "";

    private Prefs prefs;
    private Handler mainHandler;
    private HandlerThread worker;
    private Handler workerHandler;
    private MediaProjection projection;
    private PowerManager.WakeLock wakeLock;
    private View orientationView;
    private DisplayManager.DisplayListener displayListener;
    private volatile boolean stopped;

    private volatile ServerSocket server;
    private volatile ServerSocket httpServer;
    private volatile DatagramSocket beaconSocket;

    private final Object sessionLock = new Object();
    private Session session;
    private long lastFailedAuth;

    // Only touched on the worker thread, except reads of the volatile encoder.
    private VirtualDisplay virtualDisplay;
    private volatile Encoder encoder;

    /** One connected radio. */
    private static final class Session {
        final Socket socket;
        final Protocol.Writer writer;
        final int maxWidth;
        final int maxHeight;
        final GestureBuilder gestures = new GestureBuilder();
        volatile boolean closed;
        /** Touch control state the radio was last told about. */
        volatile boolean controlReported;

        Session(Socket socket, Protocol.Writer writer, int maxWidth, int maxHeight,
                boolean controlReported) {
            this.socket = socket;
            this.writer = writer;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.controlReported = controlReported;
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
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ORIENTATION.equals(intent.getAction())) {
            if (projection == null) {
                stopSelf();
            } else {
                hideOrientationOverlay();
                showOrientationOverlay();
            }
            return START_NOT_STICKY;
        }
        if (projection != null) {
            return START_NOT_STICKY;
        }
        // Android 14+ requires the foreground service before the projection is created.
        startForegroundWithNotification();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        @SuppressWarnings("deprecation")
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = data == null ? null : mpm.getMediaProjection(resultCode, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start screen capture", e);
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // The user ended screen sharing from the system UI.
                stopSelf();
            }
        }, mainHandler);

        running = true;
        stopped = false;
        setStatus(getString(R.string.status_waiting));

        acquireWakeLock();
        showOrientationOverlay();

        worker = new HandlerThread("mirror-worker");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                createVirtualDisplay();
            }
        });

        startThread("mirror-accept", new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        startThread("mirror-beacon", new Runnable() {
            @Override
            public void run() {
                beaconLoop();
            }
        });
        startThread("mirror-http", new Runnable() {
            @Override
            public void run() {
                httpLoop();
            }
        });

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY && workerHandler != null) {
                    workerHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onRotationMaybeChanged();
                        }
                    });
                }
            }
        };
        displayManager().registerDisplayListener(displayListener, mainHandler);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        running = false;
        setStatus("");
        if (displayListener != null) {
            displayManager().unregisterDisplayListener(displayListener);
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
        if (workerHandler != null) {
            final MediaProjection p = projection;
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopEncoder();
                    if (virtualDisplay != null) {
                        virtualDisplay.release();
                        virtualDisplay = null;
                    }
                    if (p != null) {
                        p.stop();
                    }
                }
            });
            worker.quitSafely();
        } else if (projection != null) {
            projection.stop();
        }
        projection = null;
        hideOrientationOverlay();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
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
            setStatus(getString(R.string.status_error, e.getMessage()));
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
            startThread("mirror-client", new Runnable() {
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
            DataInputStream hello = reader.payload();
            hello.readInt(); // protocol version, only 1 exists so far
            String code = hello.readUTF();
            int maxWidth = hello.readInt();
            int maxHeight = hello.readInt();
            String radioName = hello.readUTF();

            if (!authorize(code)) {
                writer.send(Protocol.MSG_AUTH_FAIL, Protocol.EMPTY);
                setStatus(getString(R.string.status_wrong_code, radioName));
                return;
            }
            boolean controlEnabled = ControlService.instance != null;
            writer.send(Protocol.MSG_HELLO_OK, Protocol.helloOk(Build.MODEL, controlEnabled));

            s = new Session(socket, writer, maxWidth, maxHeight, controlEnabled);
            synchronized (sessionLock) {
                if (session != null) {
                    session.close();
                }
                session = s;
            }
            setStatus(getString(R.string.status_connected, radioName));
            restartEncoder(s);

            while (!s.closed) {
                reader.next();
                switch (reader.type) {
                    case Protocol.MSG_TOUCH:
                        handleTouch(s, reader.payload());
                        break;
                    case Protocol.MSG_GLOBAL_ACTION: {
                        int action = reader.payload().readInt();
                        ControlService control = ControlService.instance;
                        if (control != null) {
                            control.globalAction(action);
                        }
                        break;
                    }
                    case Protocol.MSG_KEYFRAME_REQUEST:
                        // A new encoder reliably renders a full frame, even on a static screen.
                        restartEncoder(s);
                        break;
                    default:
                        // Ping or unknown message.
                        break;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Radio disconnected: " + e.getMessage());
        } finally {
            Net.close(socket);
            if (s != null) {
                s.close();
                boolean wasCurrent;
                synchronized (sessionLock) {
                    wasCurrent = session == s;
                    if (wasCurrent) {
                        session = null;
                    }
                }
                if (wasCurrent && !stopped) {
                    setStatus(getString(R.string.status_waiting));
                    postToWorker(new Runnable() {
                        @Override
                        public void run() {
                            stopEncoder();
                        }
                    });
                }
            }
        }
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

    private void handleTouch(Session s, DataInputStream p) throws IOException {
        int action = p.readByte();
        int pointer = p.readByte();
        float nx = p.readFloat();
        float ny = p.readFloat();
        long time = p.readLong();

        ControlService control = ControlService.instance;
        if (control == null) {
            return;
        }
        Point real = realDisplaySize();
        Encoder enc = encoder;
        int videoWidth = enc != null ? enc.width : real.x;
        int videoHeight = enc != null ? enc.height : real.y;
        // The mirrored screen is letterboxed inside the video; undo that.
        float scale = Math.min((float) videoWidth / real.x, (float) videoHeight / real.y);
        float offsetX = (videoWidth - real.x * scale) / 2f;
        float offsetY = (videoHeight - real.y * scale) / 2f;
        float x = (nx * videoWidth - offsetX) / scale;
        float y = (ny * videoHeight - offsetY) / scale;

        GestureDescription gesture = s.gestures.onTouch(action, pointer, x, y, time, real.x, real.y);
        if (gesture != null) {
            control.dispatch(gesture);
        }
    }

    /** Announces the phone to radios on the same network and keeps the session alive. */
    private void beaconLoop() {
        byte[] message = (Protocol.BEACON_MAGIC + "|" + Protocol.TCP_PORT + "|" + Build.MODEL)
                .getBytes(UTF8);
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            beaconSocket = socket;
        } catch (IOException e) {
            Log.w(TAG, "No beacon socket", e);
        }
        while (!stopped) {
            DatagramSocket socket = beaconSocket;
            if (socket != null) {
                for (InetAddress address : Net.broadcastAddresses()) {
                    sendBeacon(socket, message, address);
                }
                try {
                    sendBeacon(socket, message, InetAddress.getByName("255.255.255.255"));
                } catch (IOException ignored) {
                    // Not resolvable, cannot happen for a literal.
                }
            }
            Session s;
            synchronized (sessionLock) {
                s = session;
            }
            if (s != null) {
                try {
                    s.writer.send(Protocol.MSG_PING, Protocol.EMPTY);
                    // Lets the radio show a hint until touch control is switched on.
                    boolean control = ControlService.instance != null;
                    if (control != s.controlReported) {
                        s.writer.send(Protocol.MSG_CONTROL_STATE, Protocol.controlState(control));
                        s.controlReported = control;
                    }
                } catch (IOException e) {
                    s.close();
                }
            }
            SystemClock.sleep(1000);
        }
        Net.close(beaconSocket);
    }

    private static void sendBeacon(DatagramSocket socket, byte[] message, InetAddress address) {
        try {
            socket.send(new DatagramPacket(message, message.length, address, Protocol.BEACON_PORT));
        } catch (IOException ignored) {
            // Interface went away; try again next round.
        }
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
            out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(UTF8));
            out.flush();
            return;
        }
        File apk = new File(getApplicationInfo().sourceDir);
        String header = "HTTP/1.0 200 OK\r\n"
                + "Content-Type: application/vnd.android.package-archive\r\n"
                + "Content-Length: " + apk.length() + "\r\n"
                + "Content-Disposition: attachment; filename=\"AutoSpiegel.apk\"\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(UTF8));
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

    // ---------------------------------------------------------------- video

    private void createVirtualDisplay() {
        if (projection == null || virtualDisplay != null) {
            return;
        }
        Point real = realDisplaySize();
        int[] size = fit(real.x, real.y, 640, 640);
        try {
            // Created right away (without a surface) because a projection may only create one
            // display; radios connecting later just swap in their encoder surface.
            virtualDisplay = projection.createVirtualDisplay("AutoSpiegel", size[0], size[1],
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot create virtual display", e);
            setStatus(getString(R.string.status_error, String.valueOf(e.getMessage())));
        }
    }

    private void restartEncoder(final Session s) {
        postToWorker(new Runnable() {
            @Override
            public void run() {
                startEncoder(s);
            }
        });
    }

    /** Worker thread only. */
    private void startEncoder(Session s) {
        stopEncoder();
        if (s.closed || stopped || virtualDisplay == null) {
            return;
        }
        Point real = realDisplaySize();
        int[] size = fit(real.x, real.y, s.maxWidth, s.maxHeight);
        try {
            Encoder e = new Encoder(s, size[0], size[1]);
            encoder = e;
            virtualDisplay.resize(size[0], size[1], getResources().getDisplayMetrics().densityDpi);
            virtualDisplay.setSurface(e.surface);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Cannot start encoder", e);
            setStatus(getString(R.string.status_error, String.valueOf(e.getMessage())));
            stopEncoder();
            s.close();
        }
    }

    /** Worker thread only. */
    private void stopEncoder() {
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
        }
        Encoder e = encoder;
        encoder = null;
        if (e != null) {
            e.release();
        }
    }

    /** Worker thread only: re-encodes when the phone turned between portrait and landscape. */
    private void onRotationMaybeChanged() {
        Encoder e = encoder;
        if (e == null) {
            return;
        }
        Point real = realDisplaySize();
        if ((real.x > real.y) != (e.width > e.height)) {
            startEncoder(e.session);
        }
    }

    /**
     * Scales the phone screen to fit the radio's video area, at most {@link #MAX_LONG_SIDE}
     * pixels wide, with both sides a multiple of 16 as many encoders require.
     */
    static int[] fit(int srcWidth, int srcHeight, int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            maxWidth = 1280;
            maxHeight = 720;
        }
        double scale = Math.min((double) maxWidth / srcWidth, (double) maxHeight / srcHeight);
        scale = Math.min(scale, (double) MAX_LONG_SIDE / Math.max(srcWidth, srcHeight));
        scale = Math.min(scale, 1.0);
        int width = Math.max(160, ((int) (srcWidth * scale)) & ~15);
        int height = Math.max(160, ((int) (srcHeight * scale)) & ~15);
        return new int[] {width, height};
    }

    /** One H.264 encoder whose output goes to one radio. */
    private final class Encoder implements Runnable {
        final Session session;
        final int width;
        final int height;
        final MediaCodec codec;
        final Surface surface;
        private final Thread thread;
        private volatile boolean active = true;

        Encoder(Session session, int width, int height) throws IOException {
            this.session = session;
            this.width = width;
            this.height = height;
            MediaCodec c = MediaCodec.createEncoderByType(MIME);
            try {
                c.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException e) {
                // Some encoders reject an explicit profile; fall back to their default.
                c.release();
                c = MediaCodec.createEncoderByType(MIME);
                c.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            codec = c;
            surface = c.createInputSurface();
            c.start();
            thread = new Thread(this, "mirror-encoder");
            thread.start();
        }

        private MediaFormat format(boolean baselineProfile) {
            MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1500000, width * height * 6));
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            // Keep frames flowing for a moment when the screen does not change.
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100000);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (baselineProfile) {
                // Baseline has no B-frames: lowest latency, and old radios can decode it.
                f.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            }
            return f;
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] data = new byte[256 * 1024];
            boolean configSent = false;
            try {
                while (active) {
                    int index = codec.dequeueOutputBuffer(info, 100000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !configSent) {
                        byte[] csd = codecConfig(codec.getOutputFormat());
                        if (csd != null) {
                            session.writer.sendVideoConfig(width, height, csd, csd.length);
                            configSent = true;
                        }
                    }
                    if (index < 0) {
                        continue;
                    }
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        if (data.length < info.size) {
                            data = new byte[info.size + 64 * 1024];
                        }
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.get(data, 0, info.size);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            if (!configSent) {
                                session.writer.sendVideoConfig(width, height, data, info.size);
                                configSent = true;
                            }
                        } else {
                            session.writer.sendVideoFrame(
                                    info.flags, info.presentationTimeUs, data, info.size);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (IOException e) {
                session.close();
            } catch (RuntimeException e) {
                // The codec was released while we were waiting; just end.
            }
        }

        /** SPS and PPS with start codes, or null if the format does not carry them. */
        private byte[] codecConfig(MediaFormat format) {
            ByteBuffer sps = format.getByteBuffer("csd-0");
            ByteBuffer pps = format.getByteBuffer("csd-1");
            if (sps == null) {
                return null;
            }
            int spsSize = sps.remaining();
            int ppsSize = pps == null ? 0 : pps.remaining();
            byte[] csd = new byte[spsSize + ppsSize];
            sps.duplicate().get(csd, 0, spsSize);
            if (pps != null) {
                pps.duplicate().get(csd, spsSize, ppsSize);
            }
            return csd;
        }

        void release() {
            active = false;
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or in an error state.
            }
            codec.release();
            surface.release();
        }
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
                this, 1, new Intent(this, MirrorService.class).setAction(ACTION_STOP), piFlags);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notification_stop), stop).build());
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder legacyBuilder() {
        return new Notification.Builder(this);
    }

    /** Keeps the phone screen on (dimmed): the capture shows nothing once it turns off. */
    @SuppressWarnings("deprecation")
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "AutoSpiegel:mirror");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    /**
     * An invisible 1×1 overlay that requests an orientation. The system then shows every app
     * that way: upright (the phone does not turn in its holder) or sideways (the picture fills
     * the wide radio screen).
     */
    private void showOrientationOverlay() {
        String orientation = prefs.orientation();
        int requested;
        if (Prefs.ORIENTATION_PORTRAIT.equals(orientation)) {
            requested = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (Prefs.ORIENTATION_LANDSCAPE.equals(orientation)) {
            requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            return;
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
        lp.screenOrientation = requested;
        View view = new View(this);
        try {
            wm.addView(view, lp);
            orientationView = view;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot set orientation", e);
        }
    }

    private void hideOrientationOverlay() {
        if (orientationView != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(orientationView);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            orientationView = null;
        }
    }

    @SuppressWarnings("deprecation")
    private Point realDisplaySize() {
        Point p = new Point();
        displayManager().getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p);
        return p;
    }

    private DisplayManager displayManager() {
        return (DisplayManager) getSystemService(DISPLAY_SERVICE);
    }

    private void postToWorker(Runnable r) {
        Handler h = workerHandler;
        if (h != null) {
            h.post(r);
        }
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void setStatus(String text) {
        status = text;
    }
}
