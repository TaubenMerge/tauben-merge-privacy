package app.autospiegel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.junit.Test;

public class ProtocolTest {
    @Test
    public void helloRoundTrip() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new Protocol.Writer(wire).send(Protocol.MSG_HELLO,
                Protocol.hello("123456", 944, 600, "Radio"));

        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        assertEquals(Protocol.MSG_HELLO, reader.type);
        DataInputStream p = reader.payload();
        assertEquals(Protocol.VERSION, p.readInt());
        assertEquals("123456", p.readUTF());
        assertEquals(944, p.readInt());
        assertEquals(600, p.readInt());
        assertEquals("Radio", p.readUTF());
    }

    @Test
    public void videoMessagesUseTheOffsetsTheRadioReads() throws IOException {
        byte[] csd = {0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68};
        byte[] frame = new byte[200000];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) i;
        }
        long pts = 0x123456789AL;

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Protocol.Writer writer = new Protocol.Writer(wire);
        writer.sendVideoConfig(928, 416, csd, csd.length);
        writer.sendVideoFrame(Protocol.FLAG_KEY_FRAME, pts, frame, frame.length);
        writer.send(Protocol.MSG_PING, Protocol.EMPTY);

        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        assertEquals(Protocol.MSG_VIDEO_CONFIG, reader.type);
        assertEquals(928, Protocol.readInt(reader.buffer, 0));
        assertEquals(416, Protocol.readInt(reader.buffer, 4));
        byte[] gotCsd = new byte[reader.length - 8];
        System.arraycopy(reader.buffer, 8, gotCsd, 0, gotCsd.length);
        assertArrayEquals(csd, gotCsd);

        reader.next();
        assertEquals(Protocol.MSG_VIDEO_FRAME, reader.type);
        assertEquals(Protocol.FLAG_KEY_FRAME, Protocol.readInt(reader.buffer, 0));
        assertEquals(pts, Protocol.readLong(reader.buffer, 4));
        assertEquals(frame.length, reader.length - 12);
        for (int i = 0; i < frame.length; i += 997) {
            assertEquals(frame[i], reader.buffer[12 + i]);
        }

        reader.next();
        assertEquals(Protocol.MSG_PING, reader.type);
        assertEquals(0, reader.length);
    }

    @Test
    public void touchRoundTrip() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        new Protocol.Writer(wire).send(Protocol.MSG_TOUCH,
                Protocol.touch(Protocol.TOUCH_MOVE, 1, 0.25f, 0.75f, 987654321L));
        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        DataInputStream p = reader.payload();
        assertEquals(Protocol.TOUCH_MOVE, p.readByte());
        assertEquals(1, p.readByte());
        assertEquals(0.25f, p.readFloat(), 0f);
        assertEquals(0.75f, p.readFloat(), 0f);
        assertEquals(987654321L, p.readLong());
    }

    @Test
    public void controlStateRoundTrip() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Protocol.Writer writer = new Protocol.Writer(wire);
        writer.send(Protocol.MSG_CONTROL_STATE, Protocol.controlState(false));
        writer.send(Protocol.MSG_CONTROL_STATE, Protocol.controlState(true));
        Protocol.Reader reader = new Protocol.Reader(new ByteArrayInputStream(wire.toByteArray()));
        reader.next();
        assertEquals(Protocol.MSG_CONTROL_STATE, reader.type);
        assertEquals(0, reader.buffer[0]);
        reader.next();
        assertEquals(1, reader.buffer[0]);
    }

    @Test(expected = IOException.class)
    public void rejectsAbsurdLengths() throws IOException {
        byte[] bad = {Protocol.MSG_VIDEO_FRAME, 0x7f, 0, 0, 0};
        new Protocol.Reader(new ByteArrayInputStream(bad)).next();
    }

    @Test
    public void sidewaysPhoneCoversTheWholeRadioScreen() {
        // 20:9 phone content on a 1024x600 radio: at least as tall as the screen, so filling
        // it never upscales much; capped at 1280 px.
        int[] size = MirrorService.videoSize(2400, 1080, 1024, 600);
        assertEquals(1280, size[0]);
        assertEquals(576, size[1]);

        // Small radio: just big enough to cover 800x480.
        size = MirrorService.videoSize(2400, 1080, 800, 480);
        assertTrue(size[0] >= 1040 && size[0] <= 1066);
        assertTrue(size[1] >= 464 && size[1] <= 480);
        assertEquals(0, size[0] % 16);
        assertEquals(0, size[1] % 16);

        // Unknown radio size falls back to 1280x720.
        size = MirrorService.videoSize(1920, 1080, 0, 0);
        assertEquals(1280, size[0]);
        assertEquals(720, size[1]);
    }

    @Test
    public void uprightPhoneOnlyFitsTheRadioHeight() {
        int[] size = MirrorService.videoSize(1080, 2400, 1024, 600);
        assertEquals(256, size[0]);
        assertEquals(592, size[1]);
    }
}
