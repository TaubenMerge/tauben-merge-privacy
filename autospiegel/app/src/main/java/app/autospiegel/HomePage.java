package app.autospiegel;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Start page: directions, now playing and the newest message side by side. */
final class HomePage extends Page {
    private View setup;
    private TextView setupCode;
    private TextView setupIp;
    private View dashboard;

    private LinearLayout navActive;
    private LinearLayout navIdle;
    private ImageView navIcon;
    private IconView navFallbackIcon;
    private TextView navTitle;
    private TextView navText;
    private TextView navSub;

    private ImageView art;
    private IconView artFallback;
    private TextView musicTitle;
    private TextView musicArtist;
    private IconView playPause;

    private TextView messageHeader;
    private LinearLayout messageBody;

    HomePage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        FrameLayout frame = new FrameLayout(a);
        setup = buildSetup();
        dashboard = buildDashboard();
        frame.addView(setup);
        frame.addView(dashboard);
        return frame;
    }

    private View buildSetup() {
        LinearLayout card = Ui.column(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(a, 24);
        card.setPadding(p, p, p, p);
        card.addView(Ui.text(a, a.getString(R.string.radio_setup_title), 28, Ui.TEXT, true, 1));
        TextView steps = Ui.text(a, a.getString(R.string.radio_setup_text), 19, Ui.TEXT, false, 0);
        card.addView(steps, Ui.margins(Ui.fullWidth(), a, 0, 12, 0, 12));
        setupCode = Ui.text(a, "", 30, Ui.YELLOW, true, 1);
        card.addView(setupCode);
        setupIp = Ui.text(a, "", 17, Ui.DIM, false, 1);
        card.addView(setupIp);
        return card;
    }

    private View buildDashboard() {
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.NO_GRAVITY);
        int p = Ui.dp(a, 8);
        row.setPadding(p, p, p, p);
        row.addView(buildNavCard(), Ui.margins(Ui.weight(1.15f), a, 0, 0, 8, 0));
        LinearLayout right = Ui.column(a);
        right.addView(buildMusicCard(), Ui.margins(Ui.rowWeight(1), a, 0, 0, 0, 8));
        right.addView(buildMessageCard(), Ui.rowWeight(1));
        row.addView(right, Ui.weight(1));
        return row;
    }

    private LinearLayout card(final int page) {
        LinearLayout card = Ui.column(a);
        card.setBackground(Ui.pressable(a, Ui.CARD, 18));
        int p = Ui.dp(a, 14);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.showPage(page);
            }
        });
        return card;
    }

    private View buildNavCard() {
        LinearLayout card = card(RadioActivity.PAGE_NAV);
        card.addView(header(IconView.NAV, a.getString(R.string.radio_nav_title)));

        navActive = Ui.column(a);
        navActive.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout top = Ui.row(a);
        FrameLayout iconBox = new FrameLayout(a);
        iconBox.setBackground(Ui.round(a, Ui.BLUE, 16));
        navIcon = new ImageView(a);
        navIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ip = Ui.dp(a, 8);
        navIcon.setPadding(ip, ip, ip, ip);
        iconBox.addView(navIcon);
        navFallbackIcon = new IconView(a, IconView.NAV);
        iconBox.addView(navFallbackIcon);
        top.addView(iconBox, Ui.size(a, 120, 120));
        LinearLayout texts = Ui.column(a);
        texts.setPadding(Ui.dp(a, 14), 0, 0, 0);
        navTitle = Ui.text(a, "", 40, Ui.TEXT, true, 2);
        texts.addView(navTitle);
        navText = Ui.text(a, "", 22, Ui.TEXT, false, 3);
        texts.addView(navText);
        top.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        navActive.addView(top, Ui.fullWidth());
        navSub = Ui.text(a, "", 19, Ui.DIM, false, 2);
        navActive.addView(navSub, Ui.margins(Ui.fullWidth(), a, 0, 16, 0, 0));
        card.addView(navActive, Ui.rowWeight(1));

        navIdle = Ui.column(a);
        navIdle.setGravity(Gravity.CENTER_VERTICAL);
        navIdle.addView(Ui.text(a, a.getString(R.string.radio_nav_none), 22, Ui.TEXT, true, 2),
                Ui.margins(Ui.fullWidth(), a, 0, 16, 0, 6));
        navIdle.addView(Ui.text(a, a.getString(R.string.radio_nav_none_hint), 16, Ui.DIM,
                false, 4));
        TextView enter = Ui.button(a, a.getString(R.string.radio_nav_enter), Ui.BLUE);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.showPage(RadioActivity.PAGE_NAV);
            }
        });
        navIdle.addView(enter, Ui.margins(Ui.wrap(), a, 0, 16, 0, 0));
        card.addView(navIdle, Ui.rowWeight(1));
        return card;
    }

    private View buildMusicCard() {
        LinearLayout card = card(RadioActivity.PAGE_MUSIC);
        LinearLayout row = Ui.row(a);
        FrameLayout artBox = new FrameLayout(a);
        artBox.setBackground(Ui.round(a, Ui.BUTTON, 12));
        artFallback = new IconView(a, IconView.MUSIC);
        artBox.addView(artFallback);
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art);
        row.addView(artBox, Ui.size(a, 110, 110));

        LinearLayout right = Ui.column(a);
        right.setPadding(Ui.dp(a, 12), 0, 0, 0);
        musicTitle = Ui.text(a, "", 19, Ui.TEXT, true, 1);
        right.addView(musicTitle);
        musicArtist = Ui.text(a, "", 15, Ui.DIM, false, 1);
        right.addView(musicArtist);
        LinearLayout controls = Ui.row(a);
        controls.addView(mediaButton(IconView.PREV, "prev", Ui.BUTTON), Ui.size(a, 52, 52));
        playPause = mediaButton(IconView.PLAY, "toggle", Ui.BLUE);
        controls.addView(playPause, Ui.margins(Ui.size(a, 52, 52), a, 10, 0, 10, 0));
        controls.addView(mediaButton(IconView.NEXT, "next", Ui.BUTTON), Ui.size(a, 52, 52));
        right.addView(controls, Ui.margins(Ui.wrap(), a, 0, 8, 0, 0));
        row.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, Ui.rowWeight(1));
        return card;
    }

    private IconView mediaButton(int icon, final String action, int color) {
        IconView b = Ui.iconButton(a, icon, color);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.command("media", "action", action);
            }
        });
        return b;
    }

    private View buildMessageCard() {
        LinearLayout card = card(RadioActivity.PAGE_MESSAGES);
        LinearLayout head = header(IconView.MESSAGE, a.getString(R.string.radio_messages_title));
        messageHeader = (TextView) head.getChildAt(1);
        card.addView(head);
        messageBody = Ui.column(a);
        card.addView(messageBody, Ui.margins(Ui.fullWidth(), a, 0, 6, 0, 0));
        return card;
    }

    private LinearLayout header(int icon, String title) {
        LinearLayout row = Ui.row(a);
        IconView iconView = new IconView(a, icon);
        iconView.setColor(Ui.DIM);
        iconView.setIconScale(0.4f);
        row.addView(iconView, Ui.size(a, 24, 24));
        TextView text = Ui.text(a, title, 15, Ui.DIM, false, 1);
        row.addView(text, Ui.margins(Ui.wrap(), a, 8, 0, 0, 0));
        return row;
    }

    @Override
    void refresh() {
        boolean connected = a.connection == RadioClient.STATE_CONNECTED;
        setup.setVisibility(connected ? View.GONE : View.VISIBLE);
        dashboard.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (!connected) {
            setupCode.setText(a.getString(R.string.radio_setup_code, a.prefs.radioCode()));
            setupIp.setText(a.connection == RadioClient.STATE_SEARCHING
                    && a.connectionDetail.length() > 0
                    ? a.getString(R.string.radio_setup_ip, a.connectionDetail)
                    : a.connection == RadioClient.STATE_PAIRING
                            ? a.getString(R.string.radio_setup_pair) : "");
            return;
        }

        boolean navigating = a.nav.optBoolean("active");
        navActive.setVisibility(navigating ? View.VISIBLE : View.GONE);
        navIdle.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (navigating) {
            navTitle.setText(a.nav.optString("title"));
            navText.setText(a.nav.optString("text"));
            navSub.setText(a.nav.optString("sub"));
            navIcon.setImageBitmap(a.navIcon);
            navFallbackIcon.setVisibility(a.navIcon == null ? View.VISIBLE : View.GONE);
        }

        boolean music = a.media.optBoolean("active");
        musicTitle.setText(music ? a.media.optString("title")
                : a.getString(R.string.radio_music_none));
        musicArtist.setText(music ? a.media.optString("artist")
                : a.getString(R.string.radio_music_none_hint));
        art.setImageBitmap(a.art);
        artFallback.setVisibility(a.art == null ? View.VISIBLE : View.GONE);
        playPause.setIcon(a.media.optBoolean("playing") ? IconView.PAUSE : IconView.PLAY);

        messageBody.removeAllViews();
        JSONObject newest = null;
        int count = 0;
        for (int i = 0; i < a.messages.length(); i++) {
            JSONObject m = a.messages.optJSONObject(i);
            if (m != null && !m.optBoolean("call")) {
                count++;
                if (newest == null) {
                    newest = m;
                }
            }
        }
        messageHeader.setText(count > 0
                ? a.getString(R.string.radio_messages_title) + " (" + count + ")"
                : a.getString(R.string.radio_messages_title));
        if (newest == null) {
            messageBody.addView(Ui.text(a, a.getString(R.string.radio_messages_none), 17,
                    Ui.DIM, false, 2));
            return;
        }
        messageBody.addView(Ui.text(a, newest.optString("app"), 13, Ui.DIM, false, 1));
        messageBody.addView(Ui.text(a, newest.optString("title"), 17, Ui.TEXT, true, 1));
        messageBody.addView(Ui.text(a, newest.optString("text"), 15, Ui.TEXT, false, 1));
        LinearLayout buttons = Ui.row(a);
        a.addMessageButtons(buttons, newest, false);
        for (int i = 0; i < buttons.getChildCount(); i++) {
            buttons.getChildAt(i).setMinimumHeight(Ui.dp(a, 44));
            ((TextView) buttons.getChildAt(i)).setMinHeight(Ui.dp(a, 44));
        }
        messageBody.addView(buttons, Ui.margins(Ui.wrap(), a, -8, 6, 0, 0));
    }
}
