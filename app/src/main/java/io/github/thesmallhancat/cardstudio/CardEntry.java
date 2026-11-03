package io.github.thesmallhancat.cardstudio;

public final class CardEntry {
    public final String url;
    public final String title;
    public final String imageId;
    public final boolean enabled;
    public final long lastSeen;

    public CardEntry(String url, String title, String imageId, boolean enabled, long lastSeen) {
        this.url = url;
        this.title = title;
        this.imageId = imageId;
        this.enabled = enabled;
        this.lastSeen = lastSeen;
    }
    public boolean hasCustomImage() { return SourcePolicy.isImageId(imageId); }
}
