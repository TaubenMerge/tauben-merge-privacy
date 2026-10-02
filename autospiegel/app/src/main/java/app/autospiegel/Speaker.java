package app.autospiegel;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.util.Locale;

/** Radio side: reads messages aloud through the car speakers. */
final class Speaker implements TextToSpeech.OnInitListener {
    private final Context context;
    private TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private String pending;

    Speaker(Context context) {
        this.context = context;
    }

    void speak(String text) {
        if (failed) {
            Toast.makeText(context, R.string.radio_no_tts, Toast.LENGTH_LONG).show();
            return;
        }
        if (tts == null) {
            pending = text;
            tts = new TextToSpeech(context, this);
            return;
        }
        if (!ready) {
            pending = text;
            return;
        }
        say(text);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true;
            Toast.makeText(context, R.string.radio_no_tts, Toast.LENGTH_LONG).show();
            return;
        }
        ready = true;
        if (tts.setLanguage(Locale.GERMANY) < 0) {
            tts.setLanguage(Locale.getDefault());
        }
        if (pending != null) {
            say(pending);
            pending = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void say(String text) {
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "autospiegel");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    void stop() {
        if (tts != null && ready) {
            tts.stop();
        }
    }

    void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
