package app.autospiegel;

import android.text.format.DateFormat;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;

/** Messages and calls from the phone: read aloud, reply, dismiss, answer. */
final class MessagesPage extends Page {
    private LinearLayout list;

    MessagesPage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        ScrollView scroll = new ScrollView(a);
        list = Ui.column(a);
        int p = Ui.dp(a, 8);
        list.setPadding(p, p, p, p);
        scroll.addView(list);
        return scroll;
    }

    @Override
    void refresh() {
        list.removeAllViews();
        if (a.connection == RadioClient.STATE_CONNECTED && a.status.has("listener")
                && !a.status.optBoolean("listener")) {
            list.addView(hint(a.getString(R.string.radio_no_listener)), Ui.fullWidth());
        }
        if (a.messages.length() == 0) {
            list.addView(hint(a.getString(a.connection == RadioClient.STATE_CONNECTED
                    ? R.string.radio_messages_none : R.string.radio_not_connected)),
                    Ui.fullWidth());
            return;
        }
        for (int i = 0; i < a.messages.length(); i++) {
            JSONObject m = a.messages.optJSONObject(i);
            if (m != null) {
                list.addView(item(m), Ui.margins(Ui.fullWidth(), a, 0, 0, 0, 8));
            }
        }
    }

    private View item(final JSONObject m) {
        LinearLayout card = Ui.column(a);
        card.setBackground(Ui.round(a, m.optBoolean("call") ? 0xFF1B3A24 : Ui.CARD, 16));
        int p = Ui.dp(a, 14);
        card.setPadding(p, p, p, p);
        String when = DateFormat.getTimeFormat(a).format(new Date(m.optLong("time")));
        card.addView(Ui.text(a, m.optString("app") + " · " + when, 14, Ui.DIM, false, 1));
        card.addView(Ui.text(a, m.optString("title"), 20, Ui.TEXT, true, 2));
        card.addView(Ui.text(a, m.optString("text"), 17, Ui.TEXT, false, 4));

        LinearLayout buttons = Ui.row(a);
        if (m.optBoolean("call")) {
            JSONArray actions = m.optJSONArray("actions");
            for (int i = 0; actions != null && i < actions.length(); i++) {
                final int index = i;
                TextView b = Ui.button(a, actions.optString(i), Ui.BUTTON);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.command("action", "key", m.optString("key"), "index", index);
                    }
                });
                buttons.addView(b, Ui.margins(Ui.wrap(), a, 8, 0, 0, 0));
            }
        } else {
            a.addMessageButtons(buttons, m, true);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.addView(buttons);
        card.addView(scroll, Ui.margins(Ui.fullWidth(), a, -8, 10, 0, 0));
        return card;
    }

    private TextView hint(String text) {
        TextView t = Ui.text(a, text, 18, Ui.DIM, false, 0);
        int p = Ui.dp(a, 16);
        t.setPadding(p, p, p, p);
        return t;
    }
}
