package app.autospiegel;

import android.content.Context;
import android.view.SurfaceView;

/** A SurfaceView that keeps the video's aspect ratio inside whatever space it gets. */
final class AspectSurfaceView extends SurfaceView {
    private int videoWidth;
    private int videoHeight;

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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(maxWidth, maxHeight);
            return;
        }
        float scale = Math.min((float) maxWidth / videoWidth, (float) maxHeight / videoHeight);
        setMeasuredDimension(Math.round(videoWidth * scale), Math.round(videoHeight * scale));
    }
}
