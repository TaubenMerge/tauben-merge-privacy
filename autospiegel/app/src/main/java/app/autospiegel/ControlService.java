package app.autospiegel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Performs the radio's touches on the phone. Accessibility gestures are the only way an
 * ordinary app (without root) may tap and swipe in other apps.
 */
@TargetApi(Build.VERSION_CODES.N)
public class ControlService extends AccessibilityService {
    private static final String TAG = "AutoSpiegel";

    static volatile ControlService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not interested in events, only in dispatching gestures.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    void dispatch(final GestureDescription gesture) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatchGesture(gesture, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Gesture rejected", e);
                }
            }
        });
    }

    void globalAction(int protocolAction) {
        final int action;
        switch (protocolAction) {
            case Protocol.ACTION_BACK:
                action = GLOBAL_ACTION_BACK;
                break;
            case Protocol.ACTION_HOME:
                action = GLOBAL_ACTION_HOME;
                break;
            case Protocol.ACTION_RECENTS:
                action = GLOBAL_ACTION_RECENTS;
                break;
            case Protocol.ACTION_NOTIFICATIONS:
                action = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            default:
                return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(action);
            }
        });
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(context, ControlService.class);
        for (String entry : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }
}
