package io.github.thesmallhancat.cardstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** A small, dependency-free 16:10 artwork cropper. All mutations happen on the UI thread. */
public final class ArtworkEditorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private Bitmap bitmap;
    private float zoom = 1f;
    private float offsetX;
    private float offsetY;
    private float downX;
    private float downY;
    private float startOffsetX;
    private float startOffsetY;
    private boolean dragging;

    public ArtworkEditorView(Context context) { super(context); init(); }
    public ArtworkEditorView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ArtworkEditorView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(232, 231, 226));
        setFocusable(true);
        setContentDescription("卡面裁剪预览");
    }

    public void setImage(Bitmap value) {
        bitmap = value;
        zoom = 1f;
        offsetX = 0f;
        offsetY = 0f;
        invalidate();
    }

    public Bitmap getImage() { return bitmap; }

    public void setZoom(float value) {
        zoom = Math.max(1f, Math.min(3f, value));
        clampOffset(getWidth(), getHeight());
        invalidate();
    }

    public float getZoom() { return zoom; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        drawArtwork(canvas, getWidth(), getHeight(), offsetX, offsetY, zoom);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // The wallet artwork contract is 16:10; keep the crop viewport exact at every width.
        setMeasuredDimension(width, Math.max(1, Math.round(width * 0.625f)));
    }

    private void drawArtwork(Canvas canvas, int width, int height, float panX, float panY, float factor) {
        if (bitmap == null || bitmap.isRecycled() || width <= 0 || height <= 0) return;
        float base = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float scale = base * factor;
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        float left = (width - scaledWidth) / 2f + panX;
        float top = (height - scaledHeight) / 2f + panY;
        destination.set(left, top, left + scaledWidth, top + scaledHeight);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, paint);
        canvas.restore();
    }

    /** Returns a fixed-size JPEG-ready snapshot with the exact requested aspect ratio. */
    public Bitmap exportBitmap() {
        if (bitmap == null || bitmap.isRecycled()) return null;
        Bitmap output = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        float normalizedX = getWidth() == 0 ? 0f : offsetX / getWidth();
        float normalizedY = getHeight() == 0 ? 0f : offsetY / getHeight();
        drawArtwork(canvas, 1600, 1000, normalizedX * 1600f, normalizedY * 1000f, zoom);
        return output;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startOffsetX = offsetX;
                startOffsetY = offsetY;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                offsetX = startOffsetX + event.getX() - downX;
                offsetY = startOffsetY + event.getY() - downY;
                clampOffset(getWidth(), getHeight());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void clampOffset(int width, int height) {
        if (bitmap == null || width <= 0 || height <= 0) return;
        float base = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float scaledWidth = bitmap.getWidth() * base * zoom;
        float scaledHeight = bitmap.getHeight() * base * zoom;
        float maxX = Math.max(0f, (scaledWidth - width) / 2f);
        float maxY = Math.max(0f, (scaledHeight - height) / 2f);
        offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
        offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
    }
}
