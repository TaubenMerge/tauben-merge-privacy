package app.autospiegel;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Phone side setup: permissions, pairing code, start/stop. */
@TargetApi(Build.VERSION_CODES.N)
public class PhoneActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private Prefs prefs;
    private TextView statusView;
    private TextView controlState;
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
        controlState = (TextView) findViewById(R.id.control_state);
        overlayState = (TextView) findViewById(R.id.overlay_state);
        apkUrls = (TextView) findViewById(R.id.apk_urls);
        codeInput = (EditText) findViewById(R.id.code);
        startStop = (Button) findViewById(R.id.start_stop);

        codeInput.setText(prefs.trustedRadioCode());

        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MirrorService.running) {
                    startService(new Intent(PhoneActivity.this, MirrorService.class)
                            .setAction(MirrorService.ACTION_STOP));
                } else {
                    requestCapture();
                }
            }
        });
        findViewById(R.id.open_accessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
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

        RadioGroup orientation = (RadioGroup) findViewById(R.id.orientation);
        String current = prefs.orientation();
        orientation.check(Prefs.ORIENTATION_PORTRAIT.equals(current) ? R.id.orientation_portrait
                : Prefs.ORIENTATION_FREE.equals(current) ? R.id.orientation_free
                : R.id.orientation_landscape);
        orientation.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                prefs.setOrientation(checkedId == R.id.orientation_portrait
                        ? Prefs.ORIENTATION_PORTRAIT
                        : checkedId == R.id.orientation_free
                                ? Prefs.ORIENTATION_FREE : Prefs.ORIENTATION_LANDSCAPE);
                if (MirrorService.running) {
                    startService(new Intent(PhoneActivity.this, MirrorService.class)
                            .setAction(MirrorService.ACTION_ORIENTATION));
                }
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

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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

    private void requestCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Whole screen only: mirroring a single app would hide the home screen.
            intent = mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = mpm.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Intent service = new Intent(this, MirrorService.class)
                .setAction(MirrorService.ACTION_START)
                .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void refresh() {
        boolean running = MirrorService.running;
        statusView.setText(running ? MirrorService.status : getString(R.string.status_stopped));
        startStop.setText(running ? R.string.phone_stop : R.string.phone_start);
        controlState.setText(ControlService.isEnabled(this)
                ? R.string.phone_control_on : R.string.phone_control_off);
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
}
