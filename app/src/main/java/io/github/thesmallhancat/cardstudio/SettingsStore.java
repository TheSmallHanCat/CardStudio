package io.github.thesmallhancat.cardstudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class SettingsStore {
    public static final String AUTHORITY = "io.github.thesmallhancat.cardstudio.bridge";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final Object LOCK = new Object();
    private static final AtomicLong LAST_CLEANUP = new AtomicLong();
    private static final long RETAIN_MILLIS = 24 * 60 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("studio", Context.MODE_PRIVATE);
    }

    public List<CardEntry> cards() {
        synchronized (LOCK) {
            List<CardEntry> result = new ArrayList<>();
            JSONObject all = load();
            var keys = all.keys();
            while (keys.hasNext()) {
                String url = keys.next();
                JSONObject item = all.optJSONObject(url);
                if (item == null || !SourcePolicy.isCardArtwork(url)) continue;
                result.add(new CardEntry(url, item.optString("title", "钱包卡面"),
                        item.optString("image", ""), item.optBoolean("enabled", true), item.optLong("seen")));
            }
            result.sort(Comparator.comparingLong((CardEntry c) -> c.lastSeen).reversed());
            return result;
        }
    }

    public void observe(String url) {
        observe(url, "");
    }

    public void observe(String url, String title) {
        if (!SourcePolicy.isCardArtwork(url)) return;
        synchronized (LOCK) {
            JSONObject all = load();
            if (!all.has(url) && all.length() >= 100) return;
            try {
                JSONObject item = all.optJSONObject(url);
                if (item == null) item = new JSONObject().put("title", "钱包卡面 " + (all.length() + 1));
                if (title != null && !title.trim().isEmpty() && !item.optBoolean("namedByUser", false))
                    item.put("title", cleanTitle(title));
                item.put("seen", System.currentTimeMillis());
                all.put(url, item);
                prefs.edit().putString("cards", all.toString()).commit();
            } catch (JSONException ignored) { }
        }
    }

    public void setImage(String url, String imageId, String title) {
        if (!SourcePolicy.isCardArtwork(url) || !SourcePolicy.isImageId(imageId))
            throw new IllegalArgumentException("无效的卡面配置");
        synchronized (LOCK) {
            JSONObject all = load();
            try {
                JSONObject item = all.optJSONObject(url);
                if (item == null) item = new JSONObject();
                String old = item.optString("image");
                item.put("title", cleanTitle(title));
                item.put("namedByUser", true);
                item.put("image", imageId).put("enabled", true).put("seen", System.currentTimeMillis());
                all.put(url, item);
                if (!prefs.edit().putString("cards", all.toString()).commit()) throw new IllegalStateException("配置保存失败");
                if (SourcePolicy.isImageId(old) && !old.equals(imageId)) imageFile(old).setLastModified(System.currentTimeMillis());
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        notifyConfig();
        cleanupArtwork();
    }

    public void rename(String url, String title) {
        synchronized (LOCK) {
            JSONObject all = load();
            JSONObject item = all.optJSONObject(url);
            if (item == null) return;
            try { item.put("title", cleanTitle(title)).put("namedByUser", true); }
            catch (JSONException e) { throw new IllegalStateException(e); }
            if (!prefs.edit().putString("cards", all.toString()).commit()) throw new IllegalStateException("名称保存失败");
        }
    }

    private static String cleanTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "我的卡面";
        return title.substring(0, title.offsetByCodePoints(0, Math.min(24, title.codePointCount(0, title.length()))));
    }

    public void restore(String url) {
        synchronized (LOCK) {
            JSONObject all = load();
            JSONObject item = all.optJSONObject(url);
            if (item == null) return;
            String old = item.optString("image");
            item.remove("image");
            if (!prefs.edit().putString("cards", all.toString()).commit()) throw new IllegalStateException("配置保存失败");
            if (SourcePolicy.isImageId(old)) imageFile(old).setLastModified(System.currentTimeMillis());
        }
        notifyConfig();
    }

    public boolean isEnabled() { return prefs.getBoolean("enabled", true); }
    public void setEnabled(boolean value) { prefs.edit().putBoolean("enabled", value).apply(); notifyConfig(); }

    public Bundle config() {
        JSONArray rules = new JSONArray();
        for (CardEntry c : cards()) {
            if (!c.enabled || !c.hasCustomImage() || !imageFile(c.imageId).isFile()) continue;
            try {
                rules.put(new JSONObject().put("source", c.url).put("replacement", imageUri(c.imageId).toString()));
            } catch (JSONException ignored) { }
        }
        Bundle result = new Bundle();
        result.putBoolean("enabled", isEnabled());
        result.putString("rules", rules.toString());
        return result;
    }

    public void recordStatus(Bundle status) {
        prefs.edit().putLong("last_connected", System.currentTimeMillis())
                .putString("wallet_version", status.getString("version", "未知"))
                .putString("hook_details", status.getString("details", ""))
                .putInt("hook_count", Math.max(0, status.getInt("hooks")))
                .putString("hook_error", status.getString("error", "")).apply();
    }
    public void recordReplacement() {
        synchronized (LOCK) { prefs.edit().putLong("last_replaced", System.currentTimeMillis())
                .putInt("replacements", prefs.getInt("replacements", 0) + 1).apply(); }
    }
    public long lastConnected() { return prefs.getLong("last_connected", 0); }
    public long lastReplaced() { return prefs.getLong("last_replaced", 0); }
    public int hookCount() { return prefs.getInt("hook_count", 0); }
    public int replacements() { return prefs.getInt("replacements", 0); }
    public String hookError() { return prefs.getString("hook_error", ""); }
    public String walletVersion() { return prefs.getString("wallet_version", "尚未连接"); }
    public String hookDetails() { return prefs.getString("hook_details", ""); }

    public File imageFile(String id) {
        if (!SourcePolicy.isImageId(id)) throw new IllegalArgumentException("无效图片标识");
        File directory = new File(context.getFilesDir(), "artwork");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建图片目录");
        return new File(directory, id);
    }
    public static Uri imageUri(String id) { return Uri.parse("content://" + AUTHORITY + "/images/" + id); }
    private JSONObject load() {
        try { return new JSONObject(prefs.getString("cards", "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }
    private void notifyConfig() { context.getContentResolver().notifyChange(CONFIG_URI, null); }

    // Config writers retire revisions; a bounded background pass reclaims them after in-flight loads finish.
    private void cleanupArtwork() {
        long now = System.currentTimeMillis(), previous = LAST_CLEANUP.get();
        if (now - previous < 60_000 || !LAST_CLEANUP.compareAndSet(previous, now)) return;
        File[] files = new File(context.getFilesDir(), "artwork").listFiles();
        if (files == null) return;
        Set<String> active = new HashSet<>();
        for (CardEntry card : cards()) if (card.hasCustomImage()) active.add(card.imageId);
        int inspected = 0;
        for (File file : files) {
            if (++inspected > 128) break;
            if (SourcePolicy.isImageId(file.getName()) && !active.contains(file.getName())
                    && now - file.lastModified() > RETAIN_MILLIS) file.delete();
        }
    }
}
