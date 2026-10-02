package app.autospiegel;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Phone side setup: permissions, pairing code, start/stop. */
@TargetApi(Build.VERSION_CODES.N)
public class PhoneActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int REQUEST_CALLS = 2;

    private Prefs prefs;
    private TextView statusView;
    private TextView listenerState;
    private TextView callsState;
    private TextView overlayState;
    private TextView apkUrls;
    private EditText codeInput;
    private Button startStop;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_phone);

        statusView = (TextView) findViewById(R.id.status);
        listenerState = (TextView) findViewById(R.id.listener_state);
        callsState = (TextView) findViewById(R.id.calls_state);
        overlayState = (TextView) findViewById(R.id.overlay_state);
        apkUrls = (TextView) findViewById(R.id.apk_urls);
        codeInput = (EditText) findViewById(R.id.code);
        startStop = (Button) findViewById(R.id.start_stop);

        codeInput.setText(prefs.trustedRadioCode());

        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent service = new Intent(PhoneActivity.this, LinkService.class);
                if (LinkService.running) {
                    startService(service.setAction(LinkService.ACTION_STOP));
                } else {
                    service.setAction(LinkService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(service);
                    } else {
                        startService(service);
                    }
                }
            }
        });
        findViewById(R.id.open_listener).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        findViewById(R.id.open_app_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        findViewById(R.id.code_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = codeInput.getText().toString().trim();
                if (!code.matches("\\d{6}")) {
                    Toast.makeText(PhoneActivity.this, R.string.phone_code_invalid,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.setTrustedRadioCode(code);
                Toast.makeText(PhoneActivity.this, R.string.phone_code_saved,
                        Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.calls_permission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(new String[] {
                        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE},
                        REQUEST_CALLS);
            }
        });
        findViewById(R.id.overlay_permission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        findViewById(R.id.switch_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setMode(null);
                startActivity(new Intent(PhoneActivity.this, StartActivity.class));
                finish();
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void refresh() {
        boolean running = LinkService.running;
        statusView.setText(running ? LinkService.status : getString(R.string.status_stopped));
        startStop.setText(running ? R.string.phone_stop : R.string.phone_start);
        listenerState.setText(listenerEnabled()
                ? R.string.phone_listener_on : R.string.phone_listener_off);
        callsState.setText(granted(Manifest.permission.CALL_PHONE)
                && granted(Manifest.permission.READ_CONTACTS)
                ? R.string.phone_calls_on : R.string.phone_calls_off);
        overlayState.setText(Settings.canDrawOverlays(this)
                ? R.string.phone_overlay_on : R.string.phone_overlay_off);

        List<String> ips = Net.lanIps();
        if (!running || ips.isEmpty()) {
            apkUrls.setText(R.string.phone_apk_none);
        } else {
            StringBuilder urls = new StringBuilder();
            for (String ip : ips) {
                if (urls.length() > 0) {
                    urls.append('\n');
                }
                urls.append("http://").append(ip).append(':').append(Protocol.HTTP_PORT);
            }
            apkUrls.setText(urls);
        }
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(this, PhoneListener.class);
        for (String entry : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
