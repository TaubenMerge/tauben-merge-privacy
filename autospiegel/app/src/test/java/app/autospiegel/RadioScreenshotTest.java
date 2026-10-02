package app.autospiegel;

import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/**
 * Draws the radio screens at a typical head unit size (1024 x 600, landscape) with sample data
 * and saves them under build/screenshots, so the layout can be checked without a car.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, qualifiers = "w1024dp-h600dp-land-mdpi")
public class RadioScreenshotTest {
    @Test
    public void drawRadioScreens() throws Exception {
        new Prefs(org.robolectric.RuntimeEnvironment.getApplication()).setMode(Prefs.MODE_RADIO);
        ActivityController<RadioActivity> controller =
                Robolectric.buildActivity(RadioActivity.class).setup();
        RadioActivity radio = controller.get();
        // No phone in a test: stop the search, whose waits Robolectric skips instantly.
        Field client = RadioActivity.class.getDeclaredField("client");
        client.setAccessible(true);
        ((RadioClient) client.get(radio)).stop();
        Thread.sleep(500);
        idle();
        save(radio, "radio-0-not-connected");

        radio.onConnection(RadioClient.STATE_CONNECTED, "Pixel 8");
        radio.onMessage(Protocol.MSG_STATUS, new JSONObject()
                .put("name", "Pixel 8").put("battery", 76).put("charging", true)
                .put("listener", true).put("call", true).put("contacts", true));
        radio.onMessage(Protocol.MSG_MEDIA, new JSONObject()
                .put("active", true).put("app", "Spotify").put("title", "Über den Wolken")
                .put("artist", "Reinhard Mey").put("album", "Mein achtel Lorbeerblatt")
                .put("playing", true).put("position", 83000).put("duration", 238000));
        radio.onMessage(Protocol.MSG_MEDIA_ART, sampleArt());
        radio.onMessage(Protocol.MSG_NAV, new JSONObject()
                .put("active", true).put("app", "Google Maps").put("title", "300 m")
                .put("text", "Rechts abbiegen auf Hauptstraße")
                .put("sub", "12 Min. · 8,4 km · Ankunft 14:32"));
        radio.onMessage(Protocol.MSG_NOTIFICATIONS, new JSONArray()
                .put(new JSONObject().put("key", "1").put("app", "WhatsApp")
                        .put("title", "Anna").put("text", "Bist du schon unterwegs? Ich warte am Eingang.")
                        .put("time", 1759400000000L).put("reply", true)
                        .put("actions", new JSONArray().put("Antworten").put("Als gelesen markieren")))
                .put(new JSONObject().put("key", "2").put("app", "Nachrichten")
                        .put("title", "Papa").put("text", "Ruf mal an, wenn du Zeit hast.")
                        .put("time", 1759399000000L).put("reply", true)
                        .put("actions", new JSONArray())));
        radio.onMessage(Protocol.MSG_CONTACTS, new JSONArray()
                .put(new JSONObject().put("name", "Anna").put("number", "+49 151 1234567"))
                .put(new JSONObject().put("name", "Mama").put("number", "+49 170 7654321"))
                .put(new JSONObject().put("name", "Papa").put("number", "+49 160 1112223")));
        idle();
        save(radio, "radio-1-start");

        String[] names = {"radio-2-musik", "radio-3-nachrichten", "radio-4-telefon", "radio-5-navi"};
        int[] pages = {RadioActivity.PAGE_MUSIC, RadioActivity.PAGE_MESSAGES,
                RadioActivity.PAGE_PHONE, RadioActivity.PAGE_NAV};
        for (int i = 0; i < pages.length; i++) {
            radio.showPage(pages[i]);
            idle();
            save(radio, names[i]);
        }

        radio.showPage(RadioActivity.PAGE_HOME);
        radio.onMessage(Protocol.MSG_NOTIFICATIONS, new JSONArray()
                .put(new JSONObject().put("key", "call").put("app", "Telefon")
                        .put("title", "Mama").put("text", "Eingehender Anruf")
                        .put("time", 1759401000000L).put("call", true).put("reply", false)
                        .put("actions", new JSONArray().put("Ablehnen").put("Annehmen"))));
        idle();
        save(radio, "radio-6-anruf");
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Bitmap sampleArt() {
        Bitmap b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(230, 120, 40));
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.rgb(40, 60, 140));
        c.drawCircle(100, 100, 70, p);
        return b;
    }

    private static void save(RadioActivity activity, String name) throws Exception {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1024, 600);
        Bitmap bitmap = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File dir = new File("build/screenshots");
        dir.mkdirs();
        FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"));
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }
}
