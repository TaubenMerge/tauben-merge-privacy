package app.autospiegel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class ProtocolTest {
    @Test
    public void jsonMessagesRoundTrip() throws IOException, JSONException {
        JSONObject media = new JSONObject()
                .put("active", true)
                .put("title", "Über den Wolken")
                .put("artist", "Reinhard Mey")
                .put("playing", true)
                .put("position", 61000L)
                .put("duration", 245000L);
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("key", "0|com.whatsapp|1").put("reply", true));

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Protocol.Writer writer = new Protocol.Writer(wire);
        writer.send(Protocol.MSG_MEDIA, Protocol.utf8(media.toString()));
        writer.send(Protocol.MSG_NOTIFICATIONS, Protocol.utf8(messages.toString()));
        writer.send(Protocol.MSG_PING, Protocol.EMPTY);

        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        assertEquals(Protocol.MSG_MEDIA, reader.type);
        JSONObject gotMedia = new JSONObject(reader.text());
        assertEquals("Über den Wolken", gotMedia.getString("title"));
        assertEquals(61000L, gotMedia.getLong("position"));

        reader.next();
        assertEquals(Protocol.MSG_NOTIFICATIONS, reader.type);
        JSONArray gotMessages = new JSONArray(reader.text());
        assertEquals("0|com.whatsapp|1", gotMessages.getJSONObject(0).getString("key"));

        reader.next();
        assertEquals(Protocol.MSG_PING, reader.type);
        assertEquals(0, reader.length);
    }

    @Test
    public void picturesRoundTrip() throws IOException {
        byte[] jpeg = new byte[300000];
        for (int i = 0; i < jpeg.length; i++) {
            jpeg[i] = (byte) (i * 31);
        }
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Protocol.Writer writer = new Protocol.Writer(wire);
        writer.send(Protocol.MSG_MEDIA_ART, jpeg);
        writer.send(Protocol.MSG_NAV_ICON, Protocol.EMPTY);

        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        assertEquals(Protocol.MSG_MEDIA_ART, reader.type);
        assertArrayEquals(jpeg, reader.bytes());
        reader.next();
        assertEquals(Protocol.MSG_NAV_ICON, reader.type);
        assertEquals(0, reader.length);
    }

    @Test(expected = IOException.class)
    public void rejectsAbsurdLengths() throws IOException {
        byte[] bad = {Protocol.MSG_MEDIA_ART, 0x7f, 0, 0, 0};
        new Protocol.Reader(new ByteArrayInputStream(bad)).next();
    }

    @Test
    public void readsMessagesAloudWithAppAndSender() throws JSONException {
        JSONObject m = new JSONObject()
                .put("app", "WhatsApp")
                .put("title", "Anna")
                .put("text", "Bin gleich da");
        assertEquals("WhatsApp. Anna. Bin gleich da", RadioActivity.readable(m));
    }

    @Test
    public void formatsPlaybackTime() {
        assertEquals("0:00", Ui.time(0));
        assertEquals("3:07", Ui.time(187000));
        assertEquals("61:01", Ui.time(3661000));
    }
}
