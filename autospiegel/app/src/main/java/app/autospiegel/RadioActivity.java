package app.autospiegel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Radio side: an interface of its own, made for the wide car screen. A rail on the left
 * switches between Start, Music, Messages, Phone, Navigation and the radio's own apps. The
 * phone only supplies data, so it stays upright and undistorted on its own screen.
 */
public class RadioActivity extends Activity implements RadioClient.Listener {
    static final int PAGE_HOME = 0;
    static final int PAGE_MUSIC = 1;
    static final int PAGE_MESSAGES = 2;
    static final int PAGE_PHONE = 3;
    static final int PAGE_NAV = 4;
    static final int PAGE_APPS = 5;

    private static final long POPUP_MS = 8000;

    Prefs prefs;
    private RadioClient client;
    private Speaker speaker;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Latest data from the phone; UI thread only.
    int connection = RadioClient.STATE_SEARCHING;
    String connectionDetail = "";
    String phoneName = "";
    JSONObject status = new JSONObject();
    JSONObject media = new JSONObject();
    JSONObject nav = new JSONObject();
    Bitmap art;
    Bitmap navIcon;
    JSONArray messages = new JSONArray();
    JSONArray contacts = new JSONArray();
    private long mediaReceivedAt;
    private final Map<String, String> seenMessages = new HashMap<>();
    private boolean messagesPrimed;

    private Page[] pages;
    private View[] pageViews;
    private int currentPage = -1;
    private final LinearLayout[] railItems = new LinearLayout[6];
    private TextView badge;
    private View statusDot;
    private TextView statusText;
    private TextView clock;
    private FrameLayout banner;
    private boolean callBannerShown;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            clock.setText(DateFormat.getTimeFormat(RadioActivity.this).format(new Date()));
            if (currentPage >= 0) {
                pages[currentPage].tick();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable hidePopup = new Runnable() {
        @Override
        public void run() {
            if (!callBannerShown) {
                banner.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        speaker = new Speaker(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = Ui.row(this);
        root.setGravity(Gravity.NO_GRAVITY);
        root.setBackgroundColor(Ui.BG);

        LinearLayout rail = Ui.column(this);
        rail.setBackgroundColor(Ui.RAIL);
        int[] icons = {IconView.HOME, IconView.MUSIC, IconView.MESSAGE, IconView.PHONE,
                IconView.NAV, IconView.APPS};
        int[] labels = {R.string.radio_rail_home, R.string.radio_rail_music,
                R.string.radio_rail_messages, R.string.radio_rail_phone, R.string.radio_rail_nav,
                R.string.radio_rail_apps};
        for (int i = 0; i < icons.length; i++) {
            railItems[i] = railItem(rail, icons[i], labels[i], i);
        }
        railItem(rail, IconView.MENU, R.string.radio_rail_menu, -1);
        root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 88),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = Ui.column(this);
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        statusDot = new View(this);
        top.addView(statusDot, Ui.margins(Ui.size(this, 12, 12), this, 0, 0, 10, 0));
        statusText = Ui.text(this, "", 16, Ui.DIM, false, 1);
        top.addView(statusText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clock = Ui.text(this, "", 20, Ui.TEXT, true, 1);
        top.addView(clock, Ui.wrap());
        main.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 44)));

        FrameLayout content = new FrameLayout(this);
        pages = new Page[] {new HomePage(this), new MusicPage(this), new MessagesPage(this),
                new PhonePage(this), new NavPage(this), new AppsPage(this)};
        pageViews = new View[pages.length];
        for (int i = 0; i < pages.length; i++) {
            pageViews[i] = pages[i].view();
            pageViews[i].setVisibility(View.GONE);
            content.addView(pageViews[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        banner = new FrameLayout(this);
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        int m = Ui.dp(this, 8);
        bannerLp.setMargins(m, 0, m, 0);
        content.addView(banner, bannerLp);
        main.addView(content, Ui.rowWeight(1));
        root.addView(main, Ui.weight(1));
        setContentView(root);

        client = new RadioClient(this, prefs, this);
        showPage(PAGE_HOME);
        refreshStatusBar();
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.start();
        handler.post(ticker);
        hideSystemUi();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        client.stop();
        speaker.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentPage != PAGE_HOME) {
            showPage(PAGE_HOME);
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- navigation between pages

    private LinearLayout railItem(LinearLayout rail, int icon, int label, final int page) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setBackground(Ui.pressable(this, 0x00000000, 0));
        IconView iconView = new IconView(this, icon);
        iconView.setIconScale(0.34f);
        item.addView(iconView, Ui.size(this, 40, 36));
        TextView text = Ui.text(this, getString(label), 12, Ui.DIM, false, 1);
        text.setGravity(Gravity.CENTER);
        item.addView(text, Ui.wrap());
        if (page == PAGE_MESSAGES) {
            badge = Ui.text(this, "", 11, Ui.TEXT, true, 1);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.round(this, Ui.RED, 999));
            badge.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            badge.setVisibility(View.GONE);
            item.addView(badge, Ui.wrap());
        }
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (page < 0) {
                    showSettings();
                } else {
                    showPage(page);
                }
            }
        });
        rail.addView(item, Ui.rowWeight(1));
        return item;
    }

    void showPage(int page) {
        if (page == currentPage) {
            pages[page].refresh();
            return;
        }
        for (int i = 0; i < pageViews.length; i++) {
            pageViews[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            railItems[i].setBackground(
                    Ui.pressable(this, i == page ? 0xFF2A3743 : 0x00000000, 0));
        }
        currentPage = page;
        pages[page].refresh();
    }

    private void refreshVisiblePage() {
        if (currentPage >= 0) {
            pages[currentPage].refresh();
        }
    }

    // ---------------------------------------------------------------- data from the phone

    @Override
    public void onConnection(final int state, final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connection = state;
                connectionDetail = detail == null ? "" : detail;
                if (state == RadioClient.STATE_CONNECTED) {
                    phoneName = connectionDetail;
                } else {
                    // Do not show stale music or directions from a phone that went away.
                    media = new JSONObject();
                    nav = new JSONObject();
                    art = null;
                    navIcon = null;
                    messages = new JSONArray();
                    messagesPrimed = false;
                    seenMessages.clear();
                    updateBanner();
                }
                refreshStatusBar();
                refreshVisiblePage();
            }
        });
    }

    @Override
    public void onMessage(final int type, final Object value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (type) {
                    case Protocol.MSG_STATUS:
                        status = (JSONObject) value;
                        refreshStatusBar();
                        break;
                    case Protocol.MSG_MEDIA:
                        media = (JSONObject) value;
                        mediaReceivedAt = SystemClock.elapsedRealtime();
                        break;
                    case Protocol.MSG_MEDIA_ART:
                        art = (Bitmap) value;
                        break;
                    case Protocol.MSG_NAV:
                        nav = (JSONObject) value;
                        break;
                    case Protocol.MSG_NAV_ICON:
                        navIcon = (Bitmap) value;
                        break;
                    case Protocol.MSG_NOTIFICATIONS:
                        messages = (JSONArray) value;
                        onMessagesChanged();
                        break;
                    case Protocol.MSG_CONTACTS:
                        contacts = (JSONArray) value;
                        break;
                    case Protocol.MSG_RESULT:
                        String text = ((JSONObject) value).optString("message");
                        if (text.length() > 0) {
                            Toast.makeText(RadioActivity.this, text, Toast.LENGTH_LONG).show();
                        }
                        return;
                    default:
                        return;
                }
                refreshVisiblePage();
            }
        });
    }

    /** Current playback position, moved on since the phone last reported it. */
    long mediaPosition() {
        long position = media.optLong("position");
        if (media.optBoolean("playing")) {
            position += SystemClock.elapsedRealtime() - mediaReceivedAt;
        }
        long duration = media.optLong("duration");
        return duration > 0 ? Math.min(position, duration) : position;
    }

    private void onMessagesChanged() {
        int count = 0;
        JSONObject newest = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            if (!m.optBoolean("call")) {
                count++;
            }
            String key = m.optString("key");
            String text = m.optString("title") + "|" + m.optString("text");
            String before = seenMessages.put(key, text);
            if (messagesPrimed && newest == null && !m.optBoolean("call") && !text.equals(before)) {
                newest = m;
            }
        }
        // Messages that were already there when the phone connected are not "new".
        messagesPrimed = true;
        badge.setText(String.valueOf(count));
        badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        updateBanner();
        if (newest != null && !callBannerShown) {
            showMessagePopup(newest);
            if (prefs.autoRead()) {
                speak(readable(newest));
            }
        }
    }

    // ---------------------------------------------------------------- banners

    private void updateBanner() {
        JSONObject call = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && m.optBoolean("call")) {
                call = m;
                break;
            }
        }
        if (call != null) {
            showCallBanner(call);
        } else if (callBannerShown) {
            callBannerShown = false;
            banner.setVisibility(View.GONE);
        }
    }

    private void showCallBanner(final JSONObject call) {
        callBannerShown = true;
        handler.removeCallbacks(hidePopup);
        LinearLayout row = bannerRow(0xFF1B3A24, IconView.PHONE, call);
        JSONArray actions = call.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0; i < actions.length() && i < 3; i++) {
                final int index = i;
                String title = actions.optString(i);
                TextView b = Ui.button(this, title, actionColor(title));
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        command("action", "key", call.optString("key"), "index", index);
                    }
                });
                row.addView(b, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
            }
        }
        setBanner(row);
    }

    private static int actionColor(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("annehm") || t.contains("answer") || t.contains("accept")) {
            return Ui.GREEN;
        }
        if (t.contains("ablehn") || t.contains("auflegen") || t.contains("beend")
                || t.contains("decline") || t.contains("hang") || t.contains("end")) {
            return Ui.RED;
        }
        return Ui.BUTTON;
    }

    private void showMessagePopup(final JSONObject m) {
        LinearLayout row = bannerRow(0xFF1D2A38, IconView.MESSAGE, m);
        addMessageButtons(row, m, false);
        IconView close = Ui.iconButton(this, IconView.CLOSE, Ui.BUTTON);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                banner.setVisibility(View.GONE);
            }
        });
        row.addView(close, Ui.margins(Ui.size(this, 52, 52), this, 8, 0, 0, 0));
        setBanner(row);
        handler.removeCallbacks(hidePopup);
        handler.postDelayed(hidePopup, POPUP_MS);
    }

    private LinearLayout bannerRow(int color, int icon, JSONObject m) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.round(this, color, 16));
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        IconView iconView = new IconView(this, icon);
        row.addView(iconView, Ui.size(this, 44, 44));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        texts.addView(Ui.text(this, m.optString("app"), 13, Ui.DIM, false, 1));
        texts.addView(Ui.text(this, m.optString("title"), 19, Ui.TEXT, true, 1));
        texts.addView(Ui.text(this, m.optString("text"), 16, Ui.TEXT, false, 2));
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void setBanner(View content) {
        banner.removeAllViews();
        banner.addView(content);
        banner.setVisibility(View.VISIBLE);
    }

    /** Adds "Read aloud" and, if possible, "Reply" for a message to a row of buttons. */
    void addMessageButtons(LinearLayout row, final JSONObject m, boolean withDone) {
        TextView read = Ui.button(this, getString(R.string.radio_read), Ui.BUTTON);
        read.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speak(readable(m));
            }
        });
        row.addView(read, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        if (m.optBoolean("reply")) {
            TextView reply = Ui.button(this, getString(R.string.radio_reply), Ui.BLUE);
            reply.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showReplyDialog(m);
                }
            });
            row.addView(reply, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        }
        if (withDone) {
            TextView done = Ui.button(this, getString(R.string.radio_done), Ui.BUTTON);
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    command("dismiss", "key", m.optString("key"));
                }
            });
            row.addView(done, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        }
    }

    static String readable(JSONObject m) {
        return m.optString("app") + ". " + m.optString("title") + ". " + m.optString("text");
    }

    // ---------------------------------------------------------------- actions

    /** Sends a command to the phone; values come in name/value pairs. */
    boolean command(String name, Object... values) {
        JSONObject o = new JSONObject();
        try {
            o.put("cmd", name);
            for (int i = 0; i + 1 < values.length; i += 2) {
                o.put(String.valueOf(values[i]), values[i + 1]);
            }
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        if (!client.command(o)) {
            Toast.makeText(this, R.string.radio_not_connected, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    void speak(String text) {
        speaker.speak(text);
    }

    void showReplyDialog(final JSONObject m) {
        LinearLayout content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, p, p, 0);
        final AlertDialog[] dialog = new AlertDialog[1];
        for (final String reply : getResources().getStringArray(R.array.quick_replies)) {
            TextView b = Ui.button(this, reply, Ui.BUTTON);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    command("reply", "key", m.optString("key"), "text", reply);
                    dialog[0].dismiss();
                }
            });
            content.addView(b, Ui.margins(Ui.fullWidth(), this, 0, 0, 0, 8));
        }
        LinearLayout own = Ui.row(this);
        final EditText input = new EditText(this);
        input.setHint(R.string.radio_reply_own_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        own.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView send = Ui.button(this, getString(R.string.radio_send), Ui.BLUE);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() > 0) {
                    command("reply", "key", m.optString("key"), "text", text);
                    dialog[0].dismiss();
                }
            }
        });
        own.addView(send, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        content.addView(own, Ui.fullWidth());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        dialog[0] = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.radio_reply_title, m.optString("title")))
                .setView(scroll)
                .setNegativeButton(R.string.radio_cancel, null)
                .create();
        showDialog(dialog[0]);
    }

    private void showSettings() {
        LinearLayout content = Ui.column(this);
        int p = Ui.dp(this, 20);
        content.setPadding(p, Ui.dp(this, 12), p, 0);
        TextView code = new TextView(this);
        code.setTextSize(20);
        code.setText(getString(R.string.radio_settings_code, prefs.radioCode()));
        content.addView(code);
        final CheckBox autoRead = new CheckBox(this);
        autoRead.setText(R.string.radio_settings_auto_read);
        autoRead.setChecked(prefs.autoRead());
        content.addView(autoRead);
        final EditText ip = new EditText(this);
        ip.setHint(R.string.radio_settings_ip_hint);
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText(prefs.manualPhoneIp());
        content.addView(ip);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.radio_settings_title)
                .setView(content)
                .setPositiveButton(R.string.radio_settings_save,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.setAutoRead(autoRead.isChecked());
                                prefs.setManualPhoneIp(ip.getText().toString().trim());
                            }
                        })
                .setNeutralButton(R.string.switch_mode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.setMode(null);
                        startActivity(new Intent(RadioActivity.this, StartActivity.class));
                        finish();
                    }
                })
                .setNegativeButton(R.string.radio_settings_close, null)
                .create();
        showDialog(dialog);
    }

    private void showDialog(AlertDialog dialog) {
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                hideSystemUi();
            }
        });
        dialog.show();
    }

    // ---------------------------------------------------------------- status line

    private void refreshStatusBar() {
        int dot;
        String text;
        switch (connection) {
            case RadioClient.STATE_CONNECTED:
                dot = 0xFF66BB6A;
                StringBuilder s = new StringBuilder(phoneName);
                int battery = status.optInt("battery", -1);
                if (battery >= 0) {
                    s.append(getString(R.string.radio_battery, battery));
                    if (status.optBoolean("charging")) {
                        s.append(getString(R.string.radio_charging));
                    }
                }
                if (status.has("listener") && !status.optBoolean("listener")) {
                    s.append(" · ").append(getString(R.string.radio_no_listener_short));
                }
                text = s.toString();
                break;
            case RadioClient.STATE_PAIRING:
                dot = Ui.YELLOW;
                text = getString(R.string.radio_pairing, connectionDetail);
                break;
            default:
                dot = 0xFFEF5350;
                text = connectionDetail.length() > 0
                        ? getString(R.string.radio_searching, connectionDetail)
                        : getString(R.string.radio_searching_no_ip);
                break;
        }
        statusDot.setBackground(Ui.round(this, dot, 999));
        statusText.setText(text);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 19) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }
}
