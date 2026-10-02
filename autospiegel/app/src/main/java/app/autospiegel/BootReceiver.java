package app.autospiegel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Phone side: brings the connection service back after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Prefs prefs = new Prefs(context);
        if (!Prefs.MODE_PHONE.equals(prefs.mode()) || !prefs.autoStart()
                || Build.VERSION.SDK_INT < 24) {
            return;
        }
        Intent service = new Intent(context, LinkService.class).setAction(LinkService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException e) {
            Log.w("AutoSpiegel", "Cannot start after boot", e);
        }
    }
}
