package app.autospiegel;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Keypad and favourite contacts; the call itself runs on the phone, the sound via Bluetooth. */
final class PhonePage extends Page {
    private static final String[][] KEYS = {
            {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"*", "0", "#"}};

    private TextView number;
    private LinearLayout favourites;
    private TextView permissionHint;

    PhonePage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.NO_GRAVITY);
        int p = Ui.dp(a, 8);
        row.setPadding(p, p, p, p);

        LinearLayout pad = Ui.column(a);
        LinearLayout display = Ui.row(a);
        display.setBackground(Ui.round(a, Ui.CARD, 12));
        number = Ui.text(a, "", 28, Ui.TEXT, true, 1);
        number.setEllipsize(TextUtils.TruncateAt.START);
        number.setPadding(Ui.dp(a, 14), 0, 0, 0);
        display.addView(number, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        IconView delete = Ui.iconButton(a, IconView.BACKSPACE, Ui.CARD);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence n = number.getText();
                if (n.length() > 0) {
                    number.setText(n.subSequence(0, n.length() - 1));
                }
            }
        });
        delete.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                number.setText("");
                return true;
            }
        });
        display.addView(delete, Ui.size(a, 56, 52));
        pad.addView(display, Ui.margins(Ui.rowWeight(1), a, 0, 0, 0, 6));

        for (String[] keys : KEYS) {
            LinearLayout keyRow = Ui.row(a);
            for (final String key : keys) {
                TextView b = Ui.button(a, key, Ui.BUTTON);
                b.setTextSize(24);
                b.setMinHeight(0);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        number.append(key);
                    }
                });
                keyRow.addView(b, Ui.margins(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1), a, 3, 3, 3, 3));
            }
            pad.addView(keyRow, Ui.rowWeight(1));
        }
        TextView call = Ui.button(a, a.getString(R.string.radio_phone_call), Ui.GREEN);
        call.setMinHeight(0);
        call.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String n = number.getText().toString();
                if (n.length() > 0) {
                    a.command("call", "number", n);
                }
            }
        });
        pad.addView(call, Ui.margins(Ui.rowWeight(1), a, 3, 3, 3, 0));
        row.addView(pad, Ui.margins(Ui.weight(1), a, 0, 0, 12, 0));

        LinearLayout right = Ui.column(a);
        right.addView(Ui.text(a, a.getString(R.string.radio_phone_favorites), 18, Ui.DIM, false,
                1));
        permissionHint = Ui.text(a, a.getString(R.string.radio_phone_no_permission), 16,
                Ui.YELLOW, false, 0);
        right.addView(permissionHint, Ui.margins(Ui.fullWidth(), a, 0, 6, 0, 0));
        ScrollView scroll = new ScrollView(a);
        favourites = Ui.column(a);
        scroll.addView(favourites);
        right.addView(scroll, Ui.margins(Ui.rowWeight(1), a, 0, 8, 0, 0));
        row.addView(right, Ui.weight(1));
        return row;
    }

    @Override
    void refresh() {
        boolean connected = a.connection == RadioClient.STATE_CONNECTED;
        permissionHint.setVisibility(connected && a.status.has("call")
                && !a.status.optBoolean("call") ? View.VISIBLE : View.GONE);
        favourites.removeAllViews();
        if (a.contacts.length() == 0) {
            favourites.addView(Ui.text(a, a.getString(R.string.radio_phone_no_contacts), 16,
                    Ui.DIM, false, 0));
            return;
        }
        for (int i = 0; i < a.contacts.length(); i++) {
            final JSONObject c = a.contacts.optJSONObject(i);
            if (c == null) {
                continue;
            }
            TextView b = Ui.button(a, c.optString("name"), Ui.CARD);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.command("call", "number", c.optString("number"));
                }
            });
            favourites.addView(b, Ui.margins(Ui.fullWidth(), a, 0, 0, 0, 6));
        }
    }
}
