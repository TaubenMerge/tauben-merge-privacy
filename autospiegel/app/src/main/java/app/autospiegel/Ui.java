package app.autospiegel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small view factories for the radio's dark, large-touch-target interface. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0E1216;
    static final int RAIL = 0xFF161C22;
    static final int CARD = 0xFF1F272F;
    static final int BUTTON = 0xFF2E3943;
    static final int TEXT = 0xFFFFFFFF;
    static final int DIM = 0xFFA7B4BE;
    static final int ACCENT = 0xFF4FC3F7;
    static final int BLUE = 0xFF1E88E5;
    static final int GREEN = 0xFF2E7D32;
    static final int RED = 0xFFC62828;
    static final int YELLOW = 0xFFFFD54F;

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** Background that lights up while pressed. */
    static Drawable pressable(Context c, int color, float radiusDp) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_pressed},
                round(c, lighten(color), radiusDp));
        d.addState(new int[0], round(c, color, radiusDp));
        return d;
    }

    static int lighten(int color) {
        int a = Color.alpha(color);
        int r = Color.red(color) + (255 - Color.red(color)) / 4;
        int g = Color.green(color) + (255 - Color.green(color)) / 4;
        int b = Color.blue(color) + (255 - Color.blue(color)) / 4;
        return Color.argb(a == 0 ? 0x40 : a, r, g, b);
    }

    static TextView text(Context c, CharSequence value, float sp, int color, boolean bold,
            int maxLines) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        if (maxLines > 0) {
            t.setMaxLines(maxLines);
            t.setEllipsize(TextUtils.TruncateAt.END);
        }
        return t;
    }

    /** A big text button. */
    static TextView button(Context c, CharSequence label, int color) {
        TextView b = text(c, label, 18, TEXT, true, 2);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 56));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setBackground(pressable(c, color, 12));
        return b;
    }

    /** A round icon button. */
    static IconView iconButton(Context c, int icon, int color) {
        IconView v = new IconView(c, icon);
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackground(pressable(c, color, 999));
        return v;
    }

    static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    static LinearLayout.LayoutParams rowWeight(float weight) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
    }

    static LinearLayout.LayoutParams size(Context c, float widthDp, float heightDp) {
        return new LinearLayout.LayoutParams(dp(c, widthDp), dp(c, heightDp));
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, Context c,
            float left, float top, float right, float bottom) {
        lp.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        return lp;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Minutes and seconds, e.g. 3:07. */
    static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long s = seconds % 60;
        return (seconds / 60) + ":" + (s < 10 ? "0" : "") + s;
    }
}
