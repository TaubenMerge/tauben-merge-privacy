package app.autospiegel;

import android.view.View;

/** One screen of the radio interface, switched by the rail on the left. */
abstract class Page {
    final RadioActivity a;

    Page(RadioActivity activity) {
        a = activity;
    }

    /** Builds the page once. */
    abstract View view();

    /** Shows the latest state; called when the page appears and when data changes. */
    void refresh() {}

    /** Called every second while the page is visible. */
    void tick() {}
}
