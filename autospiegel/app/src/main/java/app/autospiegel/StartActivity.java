package app.autospiegel;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

/** Asks once whether this device is the phone or the radio, then always opens that screen. */
public class StartActivity extends Activity {
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        String mode = prefs.mode();
        if (Prefs.MODE_PHONE.equals(mode) && Build.VERSION.SDK_INT >= 24) {
            open(PhoneActivity.class);
            return;
        }
        if (Prefs.MODE_RADIO.equals(mode)) {
            open(RadioActivity.class);
            return;
        }

        setContentView(R.layout.activity_start);
        findViewById(R.id.start_phone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < 24) {
                    Toast.makeText(StartActivity.this, R.string.start_phone_too_old,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.setMode(Prefs.MODE_PHONE);
                open(PhoneActivity.class);
            }
        });
        findViewById(R.id.start_radio).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setMode(Prefs.MODE_RADIO);
                open(RadioActivity.class);
            }
        });
    }

    private void open(Class<? extends Activity> screen) {
        startActivity(new Intent(this, screen));
        finish();
    }
}
