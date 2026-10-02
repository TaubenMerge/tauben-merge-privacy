package app.autospiegel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Icons drawn by hand, so they look the same on every radio: old radios lack the fonts and
 * vector support for symbol characters or icon sets.
 */
final class IconView extends View {
    static final int HOME = 0;
    static final int MUSIC = 1;
    static final int MESSAGE = 2;
    static final int PHONE = 3;
    static final int NAV = 4;
    static final int APPS = 5;
    static final int MENU = 6;
    static final int PLAY = 7;
    static final int PAUSE = 8;
    static final int NEXT = 9;
    static final int PREV = 10;
    static final int SPEAK = 11;
    static final int BACKSPACE = 12;
    static final int CLOSE = 13;

    private int icon;
    private float scale = 0.28f;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    IconView(Context context, int icon) {
        super(context);
        this.icon = icon;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        thick.setStyle(Paint.Style.STROKE);
        setColor(Ui.TEXT);
    }

    void setIcon(int icon) {
        if (this.icon != icon) {
            this.icon = icon;
            invalidate();
        }
    }

    void setColor(int color) {
        fill.setColor(color);
        stroke.setColor(color);
        thick.setColor(color);
        invalidate();
    }

    /** Icon size relative to the view, 0.28 by default. */
    void setIconScale(float value) {
        scale = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * scale;
        stroke.setStrokeWidth(Math.max(2f, r * 0.2f));
        path.reset();
        switch (icon) {
            case HOME:
                path.moveTo(cx - r, cy - 0.05f * r);
                path.lineTo(cx, cy - r);
                path.lineTo(cx + r, cy - 0.05f * r);
                canvas.drawPath(path, stroke);
                canvas.drawRect(cx - 0.68f * r, cy - 0.2f * r, cx + 0.68f * r, cy + 0.9f * r,
                        stroke);
                break;
            case MUSIC:
                canvas.drawCircle(cx - 0.55f * r, cy + 0.62f * r, 0.32f * r, fill);
                canvas.drawCircle(cx + 0.6f * r, cy + 0.42f * r, 0.32f * r, fill);
                canvas.drawLine(cx - 0.25f * r, cy + 0.62f * r, cx - 0.25f * r, cy - 0.7f * r,
                        stroke);
                canvas.drawLine(cx + 0.9f * r, cy + 0.42f * r, cx + 0.9f * r, cy - 0.95f * r,
                        stroke);
                canvas.drawLine(cx - 0.25f * r, cy - 0.7f * r, cx + 0.9f * r, cy - 0.95f * r,
                        stroke);
                break;
            case MESSAGE:
                oval.set(cx - r, cy - 0.8f * r, cx + r, cy + 0.45f * r);
                canvas.drawRoundRect(oval, 0.3f * r, 0.3f * r, stroke);
                path.moveTo(cx - 0.5f * r, cy + 0.45f * r);
                path.lineTo(cx - 0.65f * r, cy + r);
                path.lineTo(cx - 0.05f * r, cy + 0.45f * r);
                canvas.drawPath(path, stroke);
                break;
            case PHONE:
                // A thick curve with an ear and a mouth piece, tilted like a handset.
                canvas.save();
                canvas.rotate(-40, cx, cy);
                thick.setStrokeWidth(0.42f * r);
                oval.set(cx - 0.8f * r, cy - 0.8f * r, cx + 0.8f * r, cy + 0.8f * r);
                canvas.drawArc(oval, 110, 140, false, thick);
                canvas.drawCircle(cx - 0.28f * r, cy + 0.82f * r, 0.32f * r, fill);
                canvas.drawCircle(cx - 0.28f * r, cy - 0.82f * r, 0.32f * r, fill);
                canvas.restore();
                break;
            case NAV:
                path.moveTo(cx, cy - r);
                path.lineTo(cx + 0.78f * r, cy + r);
                path.lineTo(cx, cy + 0.55f * r);
                path.lineTo(cx - 0.78f * r, cy + r);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case APPS:
                float s = 0.2f * r;
                for (int i = -1; i <= 1; i++) {
                    for (int j = -1; j <= 1; j++) {
                        float x = cx + i * 0.75f * r;
                        float y = cy + j * 0.75f * r;
                        canvas.drawRect(x - s, y - s, x + s, y + s, fill);
                    }
                }
                break;
            case MENU:
                canvas.drawCircle(cx, cy - 0.75f * r, 0.18f * r, fill);
                canvas.drawCircle(cx, cy, 0.18f * r, fill);
                canvas.drawCircle(cx, cy + 0.75f * r, 0.18f * r, fill);
                break;
            case PLAY:
                path.moveTo(cx - 0.6f * r, cy - r);
                path.lineTo(cx + 0.9f * r, cy);
                path.lineTo(cx - 0.6f * r, cy + r);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case PAUSE:
                canvas.drawRect(cx - 0.7f * r, cy - r, cx - 0.2f * r, cy + r, fill);
                canvas.drawRect(cx + 0.2f * r, cy - r, cx + 0.7f * r, cy + r, fill);
                break;
            case NEXT:
                path.moveTo(cx - 0.8f * r, cy - 0.8f * r);
                path.lineTo(cx + 0.4f * r, cy);
                path.lineTo(cx - 0.8f * r, cy + 0.8f * r);
                path.close();
                canvas.drawPath(path, fill);
                canvas.drawRect(cx + 0.45f * r, cy - 0.8f * r, cx + 0.75f * r, cy + 0.8f * r,
                        fill);
                break;
            case PREV:
                path.moveTo(cx + 0.8f * r, cy - 0.8f * r);
                path.lineTo(cx - 0.4f * r, cy);
                path.lineTo(cx + 0.8f * r, cy + 0.8f * r);
                path.close();
                canvas.drawPath(path, fill);
                canvas.drawRect(cx - 0.75f * r, cy - 0.8f * r, cx - 0.45f * r, cy + 0.8f * r,
                        fill);
                break;
            case SPEAK:
                path.moveTo(cx - r, cy - 0.35f * r);
                path.lineTo(cx - 0.55f * r, cy - 0.35f * r);
                path.lineTo(cx - 0.1f * r, cy - 0.85f * r);
                path.lineTo(cx - 0.1f * r, cy + 0.85f * r);
                path.lineTo(cx - 0.55f * r, cy + 0.35f * r);
                path.lineTo(cx - r, cy + 0.35f * r);
                path.close();
                canvas.drawPath(path, fill);
                oval.set(cx - 0.55f * r, cy - 0.5f * r, cx + 0.45f * r, cy + 0.5f * r);
                canvas.drawArc(oval, -45, 90, false, stroke);
                oval.set(cx - 0.95f * r, cy - 0.95f * r, cx + 0.95f * r, cy + 0.95f * r);
                canvas.drawArc(oval, -45, 90, false, stroke);
                break;
            case BACKSPACE:
                path.moveTo(cx - r, cy);
                path.lineTo(cx - 0.45f * r, cy - 0.7f * r);
                path.lineTo(cx + r, cy - 0.7f * r);
                path.lineTo(cx + r, cy + 0.7f * r);
                path.lineTo(cx - 0.45f * r, cy + 0.7f * r);
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawLine(cx - 0.1f * r, cy - 0.3f * r, cx + 0.5f * r, cy + 0.3f * r, stroke);
                canvas.drawLine(cx - 0.1f * r, cy + 0.3f * r, cx + 0.5f * r, cy - 0.3f * r, stroke);
                break;
            default:
                canvas.drawLine(cx - 0.7f * r, cy - 0.7f * r, cx + 0.7f * r, cy + 0.7f * r, stroke);
                canvas.drawLine(cx - 0.7f * r, cy + 0.7f * r, cx + 0.7f * r, cy - 0.7f * r, stroke);
                break;
        }
    }
}
