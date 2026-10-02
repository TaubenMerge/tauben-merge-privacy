package app.autospiegel;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Destination entry; navigation then runs on the phone and its directions show up here. */
final class NavPage extends Page {
    private TextView current;
    private EditText input;
    private LinearLayout recent;

    NavPage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        ScrollView scroll = new ScrollView(a);
        LinearLayout column = Ui.column(a);
        int p = Ui.dp(a, 16);
        column.setPadding(p, Ui.dp(a, 8), p, p);

        current = Ui.text(a, "", 20, Ui.ACCENT, true, 2);
        column.addView(current, Ui.margins(Ui.fullWidth(), a, 0, 0, 0, 8));

        column.addView(Ui.text(a, a.getString(R.string.radio_nav_where), 18, Ui.DIM, false, 1));
        LinearLayout row = Ui.row(a);
        input = new EditText(a);
        input.setTextSize(22);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setBackground(Ui.round(a, Ui.CARD, 12));
        int ip = Ui.dp(a, 14);
        input.setPadding(ip, ip, ip, ip);
        input.setSingleLine(true);
        input.setHint(R.string.radio_nav_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                go(input.getText().toString());
                return true;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1));
        TextView go = Ui.button(a, a.getString(R.string.radio_nav_go), Ui.BLUE);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(input.getText().toString());
            }
        });
        row.addView(go, Ui.margins(Ui.wrap(), a, 8, 0, 0, 0));
        column.addView(row, Ui.fullWidth());

        column.addView(Ui.text(a, a.getString(R.string.radio_nav_recent), 18, Ui.DIM, false, 1),
                Ui.margins(Ui.fullWidth(), a, 0, 16, 0, 6));
        recent = Ui.column(a);
        column.addView(recent, Ui.fullWidth());
        column.addView(Ui.text(a, a.getString(R.string.radio_nav_info), 15, Ui.DIM, false, 0),
                Ui.margins(Ui.fullWidth(), a, 0, 16, 0, 0));
        scroll.addView(column);
        return scroll;
    }

    private void go(String destination) {
        String d = destination.trim();
        if (d.length() == 0) {
            return;
        }
        if (a.command("navigate", "query", d)) {
            a.prefs.addDestination(d);
            input.setText("");
            InputMethodManager imm =
                    (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            a.showPage(RadioActivity.PAGE_HOME);
        }
    }

    @Override
    void refresh() {
        boolean navigating = a.nav.optBoolean("active");
        current.setVisibility(navigating ? View.VISIBLE : View.GONE);
        current.setText(a.getString(R.string.radio_nav_current,
                (a.nav.optString("title") + " " + a.nav.optString("text")).trim()));
        recent.removeAllViews();
        for (final String d : a.prefs.destinations()) {
            TextView b = Ui.button(a, d, Ui.CARD);
            b.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    go(d);
                }
            });
            recent.addView(b, Ui.margins(Ui.fullWidth(), a, 0, 0, 0, 6));
        }
    }
}
