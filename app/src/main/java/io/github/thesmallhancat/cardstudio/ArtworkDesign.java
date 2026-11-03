package io.github.thesmallhancat.cardstudio;

/** Pure data model for bounded artwork layers. */
public final class ArtworkDesign {
    public static final int BACKGROUND = 0;
    public static final int PRIMARY_LOGO = 1;
    public static final int UNION_LOGO = 2;
    public static final int TEXT = 3;

    public static final float MIN_LOGO_WIDTH = 0.05f;
    public static final float MAX_LOGO_WIDTH = 0.80f;
    public static final float MIN_TEXT_WIDTH = 0.15f;
    public static final float MAX_TEXT_WIDTH = 0.90f;

    public final Layer primary = new Layer(0.055f, 0.07f, 0.50f);
    public final Layer union = new Layer(0.78f, 0.07f, 0.16f);
    public final TextLayer text = new TextLayer(0.06f, 0.78f, 0.65f);
    public String templateId = "none";
    public String customLogoId = "";

    public ArtworkDesign() { }

    public ArtworkDesign(ArtworkDesign other) {
        if (other == null) return;
        primary.copyFrom(other.primary);
        union.copyFrom(other.union);
        text.copyFrom(other.text);
        templateId = other.templateId == null ? "none" : other.templateId;
        customLogoId = other.customLogoId == null ? "" : other.customLogoId;
        clampAll();
    }

    public ArtworkDesign copy() { return new ArtworkDesign(this); }

    public void clampAll() {
        primary.clamp(MIN_LOGO_WIDTH, MAX_LOGO_WIDTH);
        union.clamp(MIN_LOGO_WIDTH, MAX_LOGO_WIDTH);
        text.clamp(MIN_TEXT_WIDTH, MAX_TEXT_WIDTH);
        templateId = templateId == null || templateId.length() == 0 ? "none" : templateId;
        customLogoId = customLogoId == null ? "" : customLogoId;
    }

    public static String sanitizeLine(String value, int maxCodePoints) {
        if (value == null || value.length() == 0) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxCodePoints));
        int count = 0;
        for (int i = 0; i < value.length() && count < maxCodePoints;) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) continue;
            out.appendCodePoint(codePoint);
            count++;
        }
        return out.toString();
    }

    public static class Layer {
        public boolean enabled;
        public float x;
        public float y;
        public float width;
        public float opacity = 1f;

        public Layer() { this(0f, 0f, MIN_LOGO_WIDTH); }
        public Layer(float x, float y, float width) {
            this.x = x;
            this.y = y;
            this.width = width;
        }
        public Layer(Layer other) { if (other != null) copyFrom(other); }

        public void copyFrom(Layer other) {
            if (other == null) return;
            enabled = other.enabled;
            x = other.x;
            y = other.y;
            width = other.width;
            opacity = other.opacity;
        }

        public void clamp(float minWidth, float maxWidth) {
            width = ArtworkDesign.clamp(width, minWidth, maxWidth);
            opacity = ArtworkDesign.clamp(opacity, 0f, 1f);
            x = ArtworkDesign.clamp(x, 0f, 1f - width);
            y = ArtworkDesign.clamp(y, 0f, 1f);
        }
    }

    public static final class TextLayer extends Layer {
        public String title = "";
        public String subtitle = "";
        public int color = 0xff202124;

        public TextLayer() { this(0f, 0f, MIN_TEXT_WIDTH); }
        public TextLayer(float x, float y, float width) { super(x, y, width); }
        public TextLayer(TextLayer other) {
            super(other);
            if (other != null) {
                title = other.title;
                subtitle = other.subtitle;
                color = other.color;
            }
        }

        public void copyFrom(TextLayer other) {
            super.copyFrom(other);
            if (other == null) return;
            title = other.title;
            subtitle = other.subtitle;
            color = other.color;
        }

        @Override public void clamp(float minWidth, float maxWidth) {
            super.clamp(minWidth, maxWidth);
            title = sanitizeLine(title, 24);
            subtitle = sanitizeLine(subtitle, 40);
        }
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
