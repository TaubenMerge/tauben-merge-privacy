package app.autospiegel;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phone side: reads what the radio shows from the phone's notifications and media sessions:
 * now playing, the navigation app's next turn, and messages and calls. Also carries out the
 * radio's commands that act on them. Needs the user to allow notification access.
 */
@TargetApi(Build.VERSION_CODES.N)
public class PhoneListener extends NotificationListenerService {
    private static final String TAG = "AutoSpiegel";
    private static final int MAX_MESSAGES = 15;
    private static final long PUBLISH_DELAY_MS = 300;
    private static final long MEDIA_RECHECK_MS = 5000;
    private static final Set<String> NAV_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.apps.maps",
            "com.waze",
            "net.osmand",
            "net.osmand.plus",
            "com.here.app.maps",
            "com.sygic.aura",
            "com.generalmagic.magicearth",
            "cz.seznam.mapy",
            "com.mapfactor.navigator"));

    static volatile PhoneListener instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, StatusBarNotification> messages = new LinkedHashMap<>();
    private String navKey;
    private MediaSessionManager sessionManager;
    private MediaController controller;
    private String lastArtKey;

    private final Runnable publishMessages = new Runnable() {
        @Override
        public void run() {
            publishMessagesNow();
        }
    };

    private final Runnable mediaRecheck = new Runnable() {
        @Override
        public void run() {
            // A paused player should give way to another one that started playing.
            if (controller == null || !isPlaying(controller)) {
                refreshSessions();
            }
            handler.postDelayed(this, MEDIA_RECHECK_MS);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    chooseController(controllers);
                }
            };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishMedia();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onListenerConnected() {
        instance = this;
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component(),
                    handler);
        } catch (SecurityException e) {
            Log.w(TAG, "No access to media sessions", e);
        }
        refreshSessions();
        handler.removeCallbacks(mediaRecheck);
        handler.postDelayed(mediaRecheck, MEDIA_RECHECK_MS);

        StatusBarNotification[] active = null;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read notifications", e);
        }
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                handlePosted(sbn);
            }
        }
        if (navKey == null) {
            publishNav(null);
        }
        publishMessagesNow();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        handler.removeCallbacks(mediaRecheck);
        handler.removeCallbacks(publishMessages);
        if (sessionManager != null) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        }
        setController(null);
        messages.clear();
        navKey = null;
        publishNav(null);
        publishMessagesNow();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handlePosted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (key.equals(navKey)) {
            navKey = null;
            publishNav(null);
        }
        if (messages.remove(key) != null) {
            schedulePublishMessages();
        }
    }

    private void handlePosted(StatusBarNotification sbn) {
        if (isNavigation(sbn)) {
            navKey = sbn.getKey();
            publishNav(sbn);
        } else if (isMessage(sbn)) {
            messages.remove(sbn.getKey());
            messages.put(sbn.getKey(), sbn);
            schedulePublishMessages();
        }
    }

    private ComponentName component() {
        return new ComponentName(this, PhoneListener.class);
    }

    // ---------------------------------------------------------------- media

    private void refreshSessions() {
        if (sessionManager == null) {
            return;
        }
        try {
            chooseController(sessionManager.getActiveSessions(component()));
        } catch (SecurityException e) {
            Log.w(TAG, "No access to media sessions", e);
        }
    }

    private void chooseController(List<MediaController> controllers) {
        MediaController best = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (isPlaying(c)) {
                    best = c;
                    break;
                }
            }
            if (best == null && !controllers.isEmpty()) {
                // Keep showing the current player while paused, else the most recent one.
                best = controllers.get(0);
                if (controller != null) {
                    for (MediaController c : controllers) {
                        if (c.getSessionToken().equals(controller.getSessionToken())) {
                            best = c;
                            break;
                        }
                    }
                }
            }
        }
        setController(best);
    }

    private void setController(MediaController c) {
        boolean same = controller != null && c != null
                && controller.getSessionToken().equals(c.getSessionToken());
        if (!same) {
            if (controller != null) {
                controller.unregisterCallback(mediaCallback);
            }
            controller = c;
            if (c != null) {
                c.registerCallback(mediaCallback, handler);
            }
        }
        publishMedia();
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState state = c.getPlaybackState();
        return state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void publishMedia() {
        MediaController c = controller;
        MediaMetadata meta = c == null ? null : c.getMetadata();
        JSONObject o = new JSONObject();
        try {
            if (meta == null) {
                o.put("active", false);
                PhoneHub.publish(Protocol.MSG_MEDIA, o.toString());
                publishArt(null, null);
                return;
            }
            PlaybackState state = c.getPlaybackState();
            boolean playing = isPlaying(c);
            long position = 0;
            if (state != null) {
                position = state.getPosition();
                if (state.getState() == PlaybackState.STATE_PLAYING) {
                    position += (long) ((SystemClock.elapsedRealtime()
                            - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
                }
            }
            o.put("active", true);
            o.put("app", appLabel(c.getPackageName()));
            o.put("title", first(meta.getText(MediaMetadata.METADATA_KEY_TITLE),
                    meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)));
            o.put("artist", first(meta.getText(MediaMetadata.METADATA_KEY_ARTIST),
                    meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)));
            o.put("album", first(meta.getText(MediaMetadata.METADATA_KEY_ALBUM)));
            o.put("playing", playing);
            o.put("position", Math.max(0, position));
            o.put("duration", Math.max(0, meta.getLong(MediaMetadata.METADATA_KEY_DURATION)));
            PhoneHub.publish(Protocol.MSG_MEDIA, o.toString());
            publishArt(meta, o.optString("title") + "|" + o.optString("artist") + "|"
                    + o.optString("album"));
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private void publishArt(MediaMetadata meta, String key) {
        Bitmap art = null;
        if (meta != null) {
            art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (art == null) {
                art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
        }
        String artKey = art == null ? "none" : key + "|" + art.getWidth() + "x" + art.getHeight();
        if (artKey.equals(lastArtKey)) {
            return;
        }
        lastArtKey = artKey;
        PhoneHub.publish(Protocol.MSG_MEDIA_ART,
                art == null ? Protocol.EMPTY : compress(art, 320, Bitmap.CompressFormat.JPEG));
    }

    /** Media buttons on the radio. */
    void mediaCommand(String action) {
        MediaController c = controller;
        if (c == null) {
            // Nothing playing yet: a media key wakes up the last used player.
            if ("play".equals(action) || "toggle".equals(action)) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            }
            return;
        }
        MediaController.TransportControls controls = c.getTransportControls();
        switch (action) {
            case "play":
                controls.play();
                break;
            case "pause":
                controls.pause();
                break;
            case "toggle":
                if (isPlaying(c)) {
                    controls.pause();
                } else {
                    controls.play();
                }
                break;
            case "next":
                controls.skipToNext();
                break;
            case "prev":
                controls.skipToPrevious();
                break;
            default:
                break;
        }
    }

    private void sendMediaKey(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    // ---------------------------------------------------------------- navigation

    private boolean isNavigation(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n.category != null) {
            // Trust the app's own label; e.g. Maps' location sharing is ongoing too.
            return "navigation".equals(n.category);
        }
        return NAV_PACKAGES.contains(sbn.getPackageName()) && sbn.isOngoing()
                && !isMediaStyle(n);
    }

    private void publishNav(StatusBarNotification sbn) {
        JSONObject o = new JSONObject();
        try {
            if (sbn == null) {
                o.put("active", false);
                PhoneHub.publish(Protocol.MSG_NAV, o.toString());
                PhoneHub.publish(Protocol.MSG_NAV_ICON, Protocol.EMPTY);
                return;
            }
            Notification n = sbn.getNotification();
            Bundle extras = n.extras;
            o.put("active", true);
            o.put("app", appLabel(sbn.getPackageName()));
            o.put("title", first(extras.getCharSequence(Notification.EXTRA_TITLE)));
            o.put("text", first(extras.getCharSequence(Notification.EXTRA_TEXT),
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)));
            o.put("sub", first(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)));
            PhoneHub.publish(Protocol.MSG_NAV, o.toString());
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        Bitmap icon = largeIcon(sbn.getNotification());
        PhoneHub.publish(Protocol.MSG_NAV_ICON,
                icon == null ? Protocol.EMPTY : compress(icon, 192, Bitmap.CompressFormat.PNG));
    }

    private Bitmap largeIcon(Notification n) {
        Icon icon = n.getLargeIcon();
        if (icon != null) {
            try {
                Drawable d = icon.loadDrawable(this);
                if (d != null) {
                    return toBitmap(d);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot load icon", e);
            }
        }
        Parcelable legacy = n.extras.getParcelable(Notification.EXTRA_LARGE_ICON);
        return legacy instanceof Bitmap ? (Bitmap) legacy : null;
    }

    // ---------------------------------------------------------------- messages and calls

    private boolean isMessage(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) {
            return false;
        }
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || isMediaStyle(n)) {
            return false;
        }
        if (Notification.CATEGORY_CALL.equals(n.category)) {
            return true;
        }
        if (sbn.isOngoing() || !hasText(n)) {
            return false;
        }
        // Skip quiet system chatter such as "charging" or "USB connected".
        Ranking ranking = new Ranking();
        RankingMap map = getCurrentRanking();
        if (map != null && map.getRanking(sbn.getKey(), ranking)
                && ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            return false;
        }
        return true;
    }

    private static boolean hasText(Notification n) {
        return first(n.extras.getCharSequence(Notification.EXTRA_TITLE),
                n.extras.getCharSequence(Notification.EXTRA_TEXT)).length() > 0;
    }

    private static boolean isMediaStyle(Notification n) {
        String template = n.extras.getString(Notification.EXTRA_TEMPLATE);
        return template != null && template.contains("MediaStyle");
    }

    private void schedulePublishMessages() {
        handler.removeCallbacks(publishMessages);
        handler.postDelayed(publishMessages, PUBLISH_DELAY_MS);
    }

    private void publishMessagesNow() {
        List<StatusBarNotification> list = new ArrayList<>(messages.values());
        Collections.sort(list, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification a, StatusBarNotification b) {
                return Long.compare(b.getPostTime(), a.getPostTime());
            }
        });
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < list.size() && i < MAX_MESSAGES; i++) {
                array.put(describe(list.get(i)));
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        PhoneHub.publish(Protocol.MSG_NOTIFICATIONS, array.toString());
    }

    private JSONObject describe(StatusBarNotification sbn) throws JSONException {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        String title = first(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = first(extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_TEXT));
        // Chat apps: show the newest message rather than a summary line.
        Parcelable[] chat = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (chat != null && chat.length > 0 && chat[chat.length - 1] instanceof Bundle) {
            CharSequence last = ((Bundle) chat[chat.length - 1]).getCharSequence("text");
            if (last != null && last.length() > 0) {
                text = last.toString();
            }
        }
        JSONObject o = new JSONObject();
        o.put("key", sbn.getKey());
        o.put("app", appLabel(sbn.getPackageName()));
        o.put("title", title);
        o.put("text", text);
        o.put("time", sbn.getPostTime());
        o.put("call", Notification.CATEGORY_CALL.equals(n.category));
        o.put("reply", replyAction(n) != null);
        JSONArray actions = new JSONArray();
        for (Button button : buttons(n)) {
            actions.put(button.title);
        }
        o.put("actions", actions);
        return o;
    }

    /** A button the radio can press: a notification action or a call style button. */
    private static final class Button {
        final String title;
        final PendingIntent intent;

        Button(String title, PendingIntent intent) {
            this.title = title;
            this.intent = intent;
        }
    }

    /**
     * The notification's actions plus, for calls from Android 12+ phone apps, the answer,
     * decline and hang-up buttons that the call style keeps outside the action list.
     */
    private List<Button> buttons(Notification n) {
        List<Button> list = new ArrayList<>();
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action.actionIntent != null) {
                    list.add(new Button(action.title == null ? "" : action.title.toString(),
                            action.actionIntent));
                }
            }
        }
        addCallButton(list, n, "android.answerIntent", R.string.call_answer);
        addCallButton(list, n, "android.declineIntent", R.string.call_decline);
        addCallButton(list, n, "android.hangUpIntent", R.string.call_hang_up);
        return list;
    }

    private void addCallButton(List<Button> list, Notification n, String extra, int title) {
        Parcelable intent = n.extras.getParcelable(extra);
        if (!(intent instanceof PendingIntent)) {
            return;
        }
        for (Button b : list) {
            if (b.intent.equals(intent)) {
                return;
            }
        }
        list.add(new Button(getString(title), (PendingIntent) intent));
    }

    private static Notification.Action replyAction(Notification n) {
        if (n.actions == null) {
            return null;
        }
        for (Notification.Action action : n.actions) {
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null) {
                continue;
            }
            for (RemoteInput input : inputs) {
                if (input.getAllowFreeFormInput()) {
                    return action;
                }
            }
        }
        return null;
    }

    private StatusBarNotification find(String key) {
        StatusBarNotification sbn = messages.get(key);
        if (sbn != null) {
            return sbn;
        }
        try {
            StatusBarNotification[] found = getActiveNotifications(new String[] {key});
            return found != null && found.length > 0 ? found[0] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Presses one of the notification's buttons, e.g. "Answer" on a call. */
    boolean runAction(String key, int index) {
        StatusBarNotification sbn = find(key);
        List<Button> list = sbn == null ? null : buttons(sbn.getNotification());
        if (list == null || index < 0 || index >= list.size()) {
            return false;
        }
        try {
            list.get(index).intent.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    /** Answers a chat message the way a watch or Android Auto does. */
    boolean reply(String key, String text) {
        StatusBarNotification sbn = find(key);
        Notification.Action action = sbn == null ? null : replyAction(sbn.getNotification());
        if (action == null) {
            return false;
        }
        RemoteInput[] inputs = action.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) {
            results.putCharSequence(input.getResultKey(), text);
        }
        Intent fillIn = new Intent();
        RemoteInput.addResultsToIntent(inputs, fillIn, results);
        try {
            action.actionIntent.send(this, 0, fillIn);
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    void dismiss(String key) {
        try {
            cancelNotification(key);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot dismiss", e);
        }
        if (messages.remove(key) != null) {
            schedulePublishMessages();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String appLabel(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static String first(CharSequence... values) {
        for (CharSequence v : values) {
            if (v != null && v.length() > 0) {
                return v.toString();
            }
        }
        return "";
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 128);
        int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 128);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        return b;
    }

    /** Scales down to at most {@code maxSide} pixels and compresses for the radio. */
    private static byte[] compress(Bitmap source, int maxSide, Bitmap.CompressFormat format) {
        if (Build.VERSION.SDK_INT >= 26 && source.getConfig() == Bitmap.Config.HARDWARE) {
            // Hardware bitmaps cannot be scaled in software.
            source = source.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap b = source;
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest > maxSide) {
            float scale = (float) maxSide / longest;
            b = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(format, 80, out);
        return out.toByteArray();
    }
}
