package app.autospiegel;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Now playing on the phone, with big controls. */
final class MusicPage extends Page {
    private ImageView art;
    private IconView artFallback;
    private TextView app;
    private TextView title;
    private TextView artist;
    private TextView album;
    private ProgressBar progress;
    private TextView position;
    private TextView duration;
    private IconView playPause;

    MusicPage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        LinearLayout row = Ui.row(a);
        int p = Ui.dp(a, 16);
        row.setPadding(p, p, p, p);

        FrameLayout artBox = new FrameLayout(a);
        artBox.setBackground(Ui.round(a, Ui.CARD, 18));
        artFallback = new IconView(a, IconView.MUSIC);
        artBox.addView(artFallback);
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art);
        row.addView(artBox, Ui.size(a, 230, 230));

        LinearLayout info = Ui.column(a);
        info.setPadding(Ui.dp(a, 24), 0, 0, 0);
        app = Ui.text(a, "", 15, Ui.DIM, false, 1);
        info.addView(app);
        title = Ui.text(a, "", 28, Ui.TEXT, true, 2);
        info.addView(title);
        artist = Ui.text(a, "", 20, Ui.TEXT, false, 1);
        info.addView(artist);
        album = Ui.text(a, "", 16, Ui.DIM, false, 1);
        info.addView(album);

        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        info.addView(progress, Ui.margins(Ui.fullWidth(), a, 0, 16, 0, 0));
        LinearLayout times = Ui.row(a);
        position = Ui.text(a, "", 14, Ui.DIM, false, 1);
        times.addView(position, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        duration = Ui.text(a, "", 14, Ui.DIM, false, 1);
        times.addView(duration, Ui.wrap());
        info.addView(times, Ui.fullWidth());

        LinearLayout controls = Ui.row(a);
        controls.setGravity(Gravity.CENTER);
        controls.addView(button(IconView.PREV, "prev", Ui.BUTTON), Ui.size(a, 72, 72));
        playPause = button(IconView.PLAY, "toggle", Ui.BLUE);
        controls.addView(playPause, Ui.margins(Ui.size(a, 88, 88), a, 24, 0, 24, 0));
        controls.addView(button(IconView.NEXT, "next", Ui.BUTTON), Ui.size(a, 72, 72));
        info.addView(controls, Ui.margins(Ui.fullWidth(), a, 0, 12, 0, 0));

        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1));
        return row;
    }

    private IconView button(int icon, final String action, int color) {
        IconView b = Ui.iconButton(a, icon, color);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.command("media", "action", action);
            }
        });
        return b;
    }

    @Override
    void refresh() {
        boolean active = a.media.optBoolean("active");
        app.setText(active ? a.media.optString("app") : "");
        title.setText(active ? a.media.optString("title") : a.getString(R.string.radio_music_none));
        artist.setText(active ? a.media.optString("artist")
                : a.getString(R.string.radio_music_none_hint));
        album.setText(active ? a.media.optString("album") : "");
        art.setImageBitmap(a.art);
        artFallback.setVisibility(a.art == null ? View.VISIBLE : View.GONE);
        playPause.setIcon(a.media.optBoolean("playing") ? IconView.PAUSE : IconView.PLAY);
        tick();
    }

    @Override
    void tick() {
        long total = a.media.optLong("duration");
        long now = a.mediaPosition();
        boolean known = a.media.optBoolean("active") && total > 0;
        progress.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        progress.setProgress(known ? (int) (now * 1000 / total) : 0);
        position.setText(known ? Ui.time(now) : "");
        duration.setText(known ? Ui.time(total) : "");
    }
}
