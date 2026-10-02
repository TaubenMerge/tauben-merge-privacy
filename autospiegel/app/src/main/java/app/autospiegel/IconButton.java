package app.autospiegel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/**
 * Big button with a drawn icon. Drawn by hand because old radios lack the fonts and vector
 * support for symbol characters or icons.
 */
final class IconButton extends View {
    static final int BACK = 0;
    static final int HOME = 1;
    static final int RECENTS = 2;
    static final int MENU = 3;
    /** Small tab at the screen edge that opens the button panel. */
    static final int HANDLE = 4;

    private final int icon;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    IconButton(Context context, int icon, CharSequence description, int idleColor) {
        super(context);
        this.icon = icon;
        setClickable(true);
        setFocusable(true);
        setContentDescription(description);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed},
                new ColorDrawable(0xFF3D3D3D));
        background.addState(new int[0], new ColorDrawable(idleColor));
        setBackground(background);

        float density = context.getResources().getDisplayMetrics().density;
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3 * density);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.2f;
        switch (icon) {
            case BACK:
                path.reset();
                path.moveTo(cx + r * 0.8f, cy - r);
                path.lineTo(cx - r, cy);
                path.lineTo(cx + r * 0.8f, cy + r);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case HOME:
                canvas.drawCircle(cx, cy, r, stroke);
                break;
            case RECENTS:
                canvas.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f,
                        stroke);
                break;
            case HANDLE:
                float h = getWidth() * 0.25f;
                path.reset();
                path.moveTo(cx - h * 0.5f, cy - h);
                path.lineTo(cx + h * 0.5f, cy);
                path.lineTo(cx - h * 0.5f, cy + h);
                canvas.drawPath(path, stroke);
                break;
            default:
                float dot = r * 0.22f;
                canvas.drawCircle(cx, cy - r * 0.8f, dot, fill);
                canvas.drawCircle(cx, cy, dot, fill);
                canvas.drawCircle(cx, cy + r * 0.8f, dot, fill);
                break;
        }
    }
}
