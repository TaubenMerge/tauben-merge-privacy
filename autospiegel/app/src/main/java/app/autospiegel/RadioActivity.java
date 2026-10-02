package app.autospiegel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Radio side: full-screen video of the phone plus Back / Home / Recents buttons. */
public class RadioActivity extends Activity implements RadioClient.Listener {
    private static final int SIDEBAR_DP = 80;

    private Prefs prefs;
    private RadioClient client;
    private FrameLayout videoArea;
    private AspectSurfaceView videoView;
    private TextView statusView;
    private TextView controlHint;
    private volatile int areaWidth;
    private volatile int areaHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF000000);

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(0xFF1C1C1C);
        addButton(sidebar, IconButton.BACK, R.string.radio_back, Protocol.ACTION_BACK);
        addButton(sidebar, IconButton.HOME, R.string.radio_home, Protocol.ACTION_HOME);
        addButton(sidebar, IconButton.RECENTS, R.string.radio_recents, Protocol.ACTION_RECENTS);
        addButton(sidebar, IconButton.MENU, R.string.radio_menu, 0);
        root.addView(sidebar, new LinearLayout.LayoutParams(
                dp(SIDEBAR_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        videoArea = new FrameLayout(this);
        videoView = new AspectSurfaceView(this);
        videoArea.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setBackgroundColor(0xE6000000);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(24));
        videoArea.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controlHint = new TextView(this);
        controlHint.setText(R.string.radio_no_control);
        controlHint.setTextColor(0xFF000000);
        controlHint.setBackgroundColor(0xFFFFD54F);
        controlHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        controlHint.setGravity(Gravity.CENTER);
        controlHint.setPadding(dp(12), dp(10), dp(12), dp(10));
        controlHint.setVisibility(View.GONE);
        videoArea.addView(controlHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        root.addView(videoArea, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);

        videoArea.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                areaWidth = right - left;
                areaHeight = bottom - top;
            }
        });

        client = new RadioClient(this, prefs, this);
        videoView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                client.setSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // The decoder scales to whatever size the view has.
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                client.setSurface(null);
            }
        });
        videoView.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                forwardTouch(v, event);
                return true;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.start();
        hideSystemUi();
    }

    @Override
    protected void onStop() {
        client.stop();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void forwardTouch(View v, MotionEvent e) {
        float w = Math.max(1, v.getWidth());
        float h = Math.max(1, v.getHeight());
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                boolean down = action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_POINTER_DOWN;
                client.sendTouch(down ? Protocol.TOUCH_DOWN : Protocol.TOUCH_UP,
                        e.getPointerId(i), clamp(e.getX(i) / w), clamp(e.getY(i) / h),
                        e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    client.sendTouch(Protocol.TOUCH_MOVE, e.getPointerId(i),
                            clamp(e.getX(i) / w), clamp(e.getY(i) / h), e.getEventTime());
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                client.sendTouch(Protocol.TOUCH_CANCEL, 0, 0, 0, e.getEventTime());
                break;
            default:
                break;
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void addButton(LinearLayout bar, int icon, int description, final int action) {
        IconButton button = new IconButton(this, icon, getString(description));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action == 0) {
                    showMenu();
                } else {
                    client.sendGlobalAction(action);
                }
            }
        });
        bar.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showMenu() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), 0);
        TextView code = new TextView(this);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        code.setText(getString(R.string.radio_menu_code, prefs.radioCode()));
        content.addView(code);
        final EditText ip = new EditText(this);
        ip.setHint(R.string.radio_menu_ip_hint);
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText(prefs.manualPhoneIp());
        content.addView(ip);

        new AlertDialog.Builder(this)
                .setTitle(R.string.radio_menu_title)
                .setView(content)
                .setPositiveButton(R.string.radio_menu_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setManualPhoneIp(ip.getText().toString().trim());
                    }
                })
                .setNeutralButton(R.string.switch_mode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setMode(null);
                        startActivity(new Intent(RadioActivity.this, StartActivity.class));
                        finish();
                    }
                })
                .setNegativeButton(R.string.radio_menu_close, null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // RadioClient.Listener, called on background threads.

    @Override
    public void onStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(text);
                statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
            }
        });
    }

    @Override
    public void onVideoSize(final int width, final int height) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                videoView.setVideoSize(width, height);
            }
        });
    }

    @Override
    public void onControlState(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                controlHint.setVisibility(enabled ? View.GONE : View.VISIBLE);
            }
        });
    }

    @Override
    public int[] videoAreaSize() {
        if (areaWidth > 0 && areaHeight > 0) {
            return new int[] {areaWidth, areaHeight};
        }
        DisplayMetrics m = getResources().getDisplayMetrics();
        return new int[] {Math.max(1, m.widthPixels - dp(SIDEBAR_DP)), m.heightPixels};
    }
}
