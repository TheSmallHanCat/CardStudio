package io.github.thesmallhancat.cardstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** A 16:10 artwork editor with bounded background, logo and text layers. */
public final class ArtworkEditorView extends View {
    private static final int OUTPUT_WIDTH = 1600;
    private static final int OUTPUT_HEIGHT = 1000;
    private static final float TITLE_SIZE = 0.038f;
    private static final float SUBTITLE_SIZE = 0.026f;
    private static final float TEXT_GAP = 0.010f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bboxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private final RectF layerBounds = new RectF();
    private final RectF textBounds = new RectF();
    private Bitmap bitmap;
    private Bitmap primaryLogo;
    private Bitmap unionLogo;
    private ArtworkDesign design = new ArtworkDesign();
    private float zoom = 1f;
    private float offsetX;
    private float offsetY;
    private float downX;
    private float downY;
    private float startOffsetX;
    private float startOffsetY;
    private float startLayerX;
    private float startLayerY;
    private int editTarget = ArtworkDesign.BACKGROUND;
    private boolean dragging;

    public ArtworkEditorView(Context context) { super(context); init(); }
    public ArtworkEditorView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ArtworkEditorView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(232, 231, 226));
        setFocusable(true);
        setContentDescription("卡面裁剪预览");
        bboxPaint.setStyle(Paint.Style.STROKE);
        bboxPaint.setStrokeWidth(1f);
        bboxPaint.setColor(0xff2d6cdf);
        bboxPaint.setPathEffect(new DashPathEffect(new float[]{5f, 4f}, 0f));
    }

    public void setImage(Bitmap value) {
        bitmap = value;
        zoom = 1f;
        offsetX = 0f;
        offsetY = 0f;
        invalidate();
    }

    public Bitmap getImage() { return bitmap; }

    public void setDesign(ArtworkDesign value) {
        design = value == null ? new ArtworkDesign() : value.copy();
        design.clampAll();
        invalidate();
    }

    public ArtworkDesign getDesign() { return design.copy(); }

    /** Keeps references only; callers own both bitmap lifetimes. */
    public void setLogos(Bitmap primary, Bitmap union) {
        primaryLogo = primary;
        unionLogo = union;
        invalidate();
    }

    public void setEditTarget(int target) {
        editTarget = target < ArtworkDesign.BACKGROUND || target > ArtworkDesign.TEXT
                ? ArtworkDesign.BACKGROUND : target;
        invalidate();
    }

    public void setLayerEnabled(int target, boolean enabled) {
        ArtworkDesign.Layer layer = layerFor(target);
        if (layer == null) return;
        layer.enabled = enabled;
        design.clampAll();
        invalidate();
    }

    public void setLayerScale(float normalizedWidth) {
        ArtworkDesign.Layer layer = layerFor(editTarget);
        if (layer == null || editTarget == ArtworkDesign.BACKGROUND) return;
        float min = editTarget == ArtworkDesign.TEXT ? ArtworkDesign.MIN_TEXT_WIDTH : ArtworkDesign.MIN_LOGO_WIDTH;
        float max = editTarget == ArtworkDesign.TEXT ? ArtworkDesign.MAX_TEXT_WIDTH : ArtworkDesign.MAX_LOGO_WIDTH;
        layer.width = clamp(normalizedWidth, min, max);
        design.clampAll();
        invalidate();
    }

    public void setLayerOpacity(float opacity) {
        ArtworkDesign.Layer layer = layerFor(editTarget);
        if (layer == null || editTarget == ArtworkDesign.BACKGROUND) return;
        layer.opacity = clamp(opacity, 0f, 1f);
        invalidate();
    }

    public void setText(String title, String subtitle, int color) {
        design.text.title = ArtworkDesign.sanitizeLine(title, 24);
        design.text.subtitle = ArtworkDesign.sanitizeLine(subtitle, 40);
        design.text.color = color;
        design.text.enabled = true;
        design.clampAll();
        invalidate();
    }

    public float getLayerScale() {
        ArtworkDesign.Layer layer = layerFor(editTarget);
        return layer == null ? 1f : layer.width;
    }

    public float getLayerOpacity() {
        ArtworkDesign.Layer layer = layerFor(editTarget);
        return layer == null ? 1f : layer.opacity;
    }

    public void setZoom(float value) {
        zoom = clamp(value, 1f, 3f);
        clampOffset(getWidth(), getHeight());
        invalidate();
    }

    public float getZoom() { return zoom; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        render(canvas, getWidth(), getHeight(), true, true);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        setMeasuredDimension(width, Math.max(1, Math.round(width * 0.625f)));
    }

    public Bitmap exportBitmap() { return createExport(true); }
    public Bitmap exportBackgroundBitmap() { return createExport(false); }

    private Bitmap createExport(boolean withLayers) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
            render(new Canvas(output), OUTPUT_WIDTH, OUTPUT_HEIGHT, false, withLayers);
            return output;
        } catch (OutOfMemoryError error) {
            if (output != null) output.recycle();
            return null;
        }
    }

    private void render(Canvas canvas, int width, int height, boolean preview, boolean withLayers) {
        canvas.drawColor(Color.WHITE);
        if (bitmap == null || bitmap.isRecycled() || width <= 0 || height <= 0) return;
        float viewWidth = Math.max(1f, getWidth());
        float viewHeight = Math.max(1f, getHeight());
        float panX = offsetX / viewWidth * width;
        float panY = offsetY / viewHeight * height;
        drawBackground(canvas, width, height, panX, panY, zoom);
        if (withLayers) drawLayers(canvas, width, height, preview);
    }

    private void drawBackground(Canvas canvas, int width, int height, float panX, float panY, float factor) {
        float base = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float scale = base * factor;
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        destination.set((width - scaledWidth) / 2f + panX, (height - scaledHeight) / 2f + panY,
                (width + scaledWidth) / 2f + panX, (height + scaledHeight) / 2f + panY);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, paint);
        canvas.restore();
    }

    private void drawLayers(Canvas canvas, int width, int height, boolean preview) {
        drawLogo(canvas, primaryLogo, design.primary, width, height, ArtworkDesign.PRIMARY_LOGO, preview);
        drawLogo(canvas, unionLogo, design.union, width, height, ArtworkDesign.UNION_LOGO, preview);
        drawTextLayer(canvas, width, height, preview);
    }

    private void drawLogo(Canvas canvas, Bitmap logo, ArtworkDesign.Layer layer, int width, int height,
                           int target, boolean preview) {
        if (logo == null || logo.isRecycled() || !layer.enabled || logo.getWidth() <= 0 || logo.getHeight() <= 0) return;
        float logoWidth = width * clamp(layer.width, ArtworkDesign.MIN_LOGO_WIDTH, ArtworkDesign.MAX_LOGO_WIDTH);
        float logoHeight = logoWidth * logo.getHeight() / (float) logo.getWidth();
        if (logoHeight > height * .95f) {
            logoWidth *= height * .95f / logoHeight;
            logoHeight = height * .95f;
        }
        float x = clamp(layer.x, 0f, Math.max(0f, 1f - logoWidth / width)) * width;
        float y = clamp(layer.y, 0f, Math.max(0f, 1f - logoHeight / height)) * height;
        layerBounds.set(x, y, x + logoWidth, y + logoHeight);
        drawWithAlpha(canvas, logo, layerBounds, layer.opacity);
        if (preview && editTarget == target) drawSelection(canvas, layerBounds);
    }

    private void drawTextLayer(Canvas canvas, int width, int height, boolean preview) {
        ArtworkDesign.TextLayer layer = design.text;
        if (!layer.enabled || (layer.title.length() == 0 && layer.subtitle.length() == 0)) return;
        float boxWidth = width * clamp(layer.width, ArtworkDesign.MIN_TEXT_WIDTH, ArtworkDesign.MAX_TEXT_WIDTH);
        float textScale = layer.width / .65f;
        float titleSize = width * TITLE_SIZE * textScale;
        float subtitleSize = width * SUBTITLE_SIZE * textScale;
        float lineGap = width * TEXT_GAP * textScale;
        float totalHeight = (layer.title.length() == 0 ? 0f : titleSize * 1.2f)
                + (layer.subtitle.length() == 0 ? 0f : subtitleSize * 1.25f)
                + (layer.title.length() != 0 && layer.subtitle.length() != 0 ? lineGap : 0f);
        float x = clamp(layer.x, 0f, Math.max(0f, 1f - boxWidth / width)) * width;
        float y = clamp(layer.y, 0f, Math.max(0f, 1f - totalHeight / height)) * height;
        textBounds.set(x, y, x + boxWidth, y + totalHeight);
        int oldAlpha = paint.getAlpha();
        paint.setColor(layer.color);
        paint.setAlpha(Math.round(255f * clamp(layer.opacity, 0f, 1f)));
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(titleSize);
        float baseline = y - paint.ascent();
        if (layer.title.length() != 0) {
            canvas.drawText(fitText(layer.title, boxWidth), x, baseline, paint);
            baseline += titleSize * 1.2f + lineGap;
        }
        if (layer.subtitle.length() != 0) {
            paint.setTextSize(subtitleSize);
            canvas.drawText(fitText(layer.subtitle, boxWidth), x, baseline, paint);
        }
        paint.setAlpha(oldAlpha);
        if (preview && editTarget == ArtworkDesign.TEXT) drawSelection(canvas, textBounds);
    }

    private String fitText(String value, float maxWidth) {
        if (paint.measureText(value) <= maxWidth) return value;
        String ellipsis = "...";
        int end = value.length();
        while (end > 0 && paint.measureText(value, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return end <= 0 ? "" : value.substring(0, end) + ellipsis;
    }

    private void drawWithAlpha(Canvas canvas, Bitmap value, RectF bounds, float opacity) {
        int oldAlpha = paint.getAlpha();
        paint.setAlpha(Math.round(255f * clamp(opacity, 0f, 1f)));
        canvas.drawBitmap(value, null, bounds, paint);
        paint.setAlpha(oldAlpha);
    }

    private void drawSelection(Canvas canvas, RectF bounds) {
        canvas.drawRoundRect(bounds, 3f, 3f, bboxPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startOffsetX = offsetX;
                startOffsetY = offsetY;
                ArtworkDesign.Layer layer = layerFor(editTarget);
                startLayerX = layer == null ? 0f : layer.x;
                startLayerY = layer == null ? 0f : layer.y;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (editTarget == ArtworkDesign.BACKGROUND) {
                    offsetX = startOffsetX + dx;
                    offsetY = startOffsetY + dy;
                    clampOffset(getWidth(), getHeight());
                } else {
                    ArtworkDesign.Layer target = layerFor(editTarget);
                    if (target != null) {
                        target.x = startLayerX + dx / Math.max(1f, getWidth());
                        target.y = startLayerY + dy / Math.max(1f, getHeight());
                        clampLayerPosition(target, editTarget, getWidth(), getHeight());
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private ArtworkDesign.Layer layerFor(int target) {
        if (target == ArtworkDesign.PRIMARY_LOGO) return design.primary;
        if (target == ArtworkDesign.UNION_LOGO) return design.union;
        if (target == ArtworkDesign.TEXT) return design.text;
        return null;
    }

    private void clampLayerPosition(ArtworkDesign.Layer layer, int target, int width, int height) {
        float layerWidth = width * layer.width;
        float layerHeight = height * 0.08f;
        Bitmap image = target == ArtworkDesign.PRIMARY_LOGO ? primaryLogo : unionLogo;
        if (target != ArtworkDesign.TEXT && image != null && !image.isRecycled() && image.getWidth() > 0) {
            layerHeight = layerWidth * image.getHeight() / image.getWidth();
            if (layerHeight > height * .95f) {
                layerWidth *= height * .95f / layerHeight;
                layerHeight = height * .95f;
            }
        } else if (target == ArtworkDesign.TEXT) {
            layerHeight = textHeight(width);
        }
        layer.x = clamp(layer.x, 0f, Math.max(0f, 1f - layerWidth / Math.max(1f, width)));
        layer.y = clamp(layer.y, 0f, Math.max(0f, 1f - layerHeight / Math.max(1f, height)));
    }

    private float textHeight(int width) {
        float textScale = design.text.width / .65f;
        float h = 0f;
        if (design.text.title.length() != 0) h += width * TITLE_SIZE * 1.2f * textScale;
        if (design.text.subtitle.length() != 0) h += width * SUBTITLE_SIZE * 1.25f * textScale;
        if (design.text.title.length() != 0 && design.text.subtitle.length() != 0) h += width * TEXT_GAP * textScale;
        return h;
    }

    private void clampOffset(int width, int height) {
        if (bitmap == null || bitmap.isRecycled() || width <= 0 || height <= 0) return;
        float base = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float scaledWidth = bitmap.getWidth() * base * zoom;
        float scaledHeight = bitmap.getHeight() * base * zoom;
        float maxX = Math.max(0f, (scaledWidth - width) / 2f);
        float maxY = Math.max(0f, (scaledHeight - height) / 2f);
        offsetX = clamp(offsetX, -maxX, maxX);
        offsetY = clamp(offsetY, -maxY, maxY);
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
