package app.autospiegel;

import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.graphics.Path;
import android.os.Build;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the radio's touch events and turns them into one accessibility gesture once the
 * last finger is lifted. Taps, long presses, swipes and two-finger pinches keep their timing.
 */
@TargetApi(Build.VERSION_CODES.N)
final class GestureBuilder {
    private static final int MAX_STROKES = 10;
    private static final long STALE_MS = 30000;

    private static final class Stroke {
        final Path path = new Path();
        float lastX;
        float lastY;
        long down;
        long up;
    }

    private final SparseArray<Stroke> active = new SparseArray<>();
    private final List<Stroke> strokes = new ArrayList<>();
    private long start;

    /**
     * Adds one touch event in phone screen pixels.
     *
     * @return the finished gesture when the last finger went up, otherwise null
     */
    GestureDescription onTouch(int action, int pointer, float x, float y, long time,
            int screenWidth, int screenHeight) {
        x = clamp(x, screenWidth - 1);
        y = clamp(y, screenHeight - 1);
        switch (action) {
            case Protocol.TOUCH_DOWN: {
                if (!strokes.isEmpty() && time - start > STALE_MS) {
                    reset(); // a lift got lost; do not glue the next touch onto it
                }
                if (active.get(pointer) != null || strokes.size() >= MAX_STROKES) {
                    return null;
                }
                if (strokes.isEmpty()) {
                    start = time;
                }
                Stroke s = new Stroke();
                s.path.moveTo(x, y);
                s.lastX = x;
                s.lastY = y;
                s.down = time;
                active.put(pointer, s);
                strokes.add(s);
                return null;
            }
            case Protocol.TOUCH_MOVE: {
                Stroke s = active.get(pointer);
                if (s != null && Math.abs(x - s.lastX) + Math.abs(y - s.lastY) >= 1f) {
                    s.path.lineTo(x, y);
                    s.lastX = x;
                    s.lastY = y;
                }
                return null;
            }
            case Protocol.TOUCH_UP: {
                Stroke s = active.get(pointer);
                if (s == null) {
                    return null;
                }
                if (Math.abs(x - s.lastX) + Math.abs(y - s.lastY) >= 1f) {
                    s.path.lineTo(x, y);
                }
                s.up = time;
                active.remove(pointer);
                if (active.size() > 0) {
                    return null;
                }
                GestureDescription gesture = build();
                reset();
                return gesture;
            }
            default:
                reset();
                return null;
        }
    }

    private GestureDescription build() {
        long maxDuration = GestureDescription.getMaxGestureDuration();
        GestureDescription.Builder builder = new GestureDescription.Builder();
        int added = 0;
        for (Stroke s : strokes) {
            long startTime = Math.max(0, s.down - start);
            if (startTime >= maxDuration) {
                continue;
            }
            long duration = Math.min(Math.max(1, s.up - s.down), maxDuration - startTime);
            builder.addStroke(new GestureDescription.StrokeDescription(s.path, startTime, duration));
            added++;
        }
        if (added == 0) {
            return null;
        }
        try {
            return builder.build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void reset() {
        active.clear();
        strokes.clear();
    }

    private static float clamp(float value, int max) {
        return Math.max(0f, Math.min(value, max));
    }
}
