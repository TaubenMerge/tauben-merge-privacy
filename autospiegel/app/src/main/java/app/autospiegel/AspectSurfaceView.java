package app.autospiegel;

import android.content.Context;
import android.view.SurfaceView;

/**
 * Sizes the video inside the space it gets: whole picture with black bars, stretched to fill,
 * or zoomed to fill (the view then overflows its parent and the edges are cut off). Touches
 * stay correct in every mode because they are measured relative to this view.
 */
final class AspectSurfaceView extends SurfaceView {
    private int videoWidth;
    private int videoHeight;
    private String mode = Prefs.DISPLAY_STRETCH;

    AspectSurfaceView(Context context) {
        super(context);
    }

    void setVideoSize(int width, int height) {
        if (width == videoWidth && height == videoHeight) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    void setMode(String displayMode) {
        mode = displayMode;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(maxWidth, maxHeight);
            return;
        }
        float scaleX = (float) maxWidth / videoWidth;
        float scaleY = (float) maxHeight / videoHeight;
        // An upright phone picture would be distorted or cut down to a sliver; always show it whole.
        boolean portraitVideo = videoHeight > videoWidth;
        if (Prefs.DISPLAY_STRETCH.equals(mode) && !portraitVideo) {
            setMeasuredDimension(maxWidth, maxHeight);
            return;
        }
        float scale = Prefs.DISPLAY_ZOOM.equals(mode) && !portraitVideo
                ? Math.max(scaleX, scaleY)
                : Math.min(scaleX, scaleY);
        setMeasuredDimension(Math.round(videoWidth * scale), Math.round(videoHeight * scale));
    }
}
