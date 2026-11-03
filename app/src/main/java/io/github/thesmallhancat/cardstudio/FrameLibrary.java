package io.github.thesmallhancat.cardstudio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** User-local artwork import. Legacy artwork is never embedded in CardStudio's APK. */
public final class FrameLibrary {
    public static final class Preset {
        public final String id, title;
        private final Rect primary, union;
        private final float unionY;
        private Preset(String id, String title, Rect primary, Rect union, float unionY) {
            this.id = id; this.title = title; this.primary = primary; this.union = union; this.unionY = unionY;
        }
    }
    public static final class Logos implements AutoCloseable {
        public final Bitmap primary, union;
        Logos(Bitmap primary, Bitmap union) { this.primary = primary; this.union = union; }
        @Override public void close() {
            if (primary != null && !primary.isRecycled()) primary.recycle();
            if (union != null && union != primary && !union.isRecycled()) union.recycle();
        }
    }
    private static final Preset[] LEGACY = {
        new Preset("jingjinji", "市政交通一卡通", new Rect(40,35,615,210), new Rect(690,30,940,205), .085f),
        new Preset("shanghai", "上海交通卡", new Rect(35,25,465,175), new Rect(680,400,940,580), .71f),
        new Preset("qvanchengtong", "泉城通", new Rect(40,25,510,180), new Rect(675,385,940,585), .71f),
        new Preset("yinchuan", "银川一卡通", new Rect(40,25,520,180), new Rect(675,385,940,590), .71f)
    };
    private final Context context;
    private final File directory;

    public FrameLibrary(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "templates");
    }
    public List<Preset> availablePresets() {
        List<Preset> result = new ArrayList<>();
        for (Preset preset : LEGACY) if (file(preset.id, "primary").isFile() && file(preset.id, "union").isFile()) result.add(preset);
        return result;
    }
    public int importLegacyTemplates() throws IOException, PackageManager.NameNotFoundException {
        Context legacy = context.createPackageContext("com.zhizi42.diymiuicard", 0);
        ensureDirectory();
        int count = 0;
        for (Preset preset : LEGACY) {
            if (file(preset.id, "primary").isFile() && file(preset.id, "union").isFile()) { count++; continue; }
            Bitmap source;
            try (InputStream input = legacy.getAssets().open("card_logo/" + preset.id + ".png")) {
                byte[] data = readLimited(input, 4 * 1024 * 1024);
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth != 960 || bounds.outHeight != 606) throw new IOException("旧模板尺寸不匹配");
                source = BitmapFactory.decodeByteArray(data, 0, data.length);
            }
            if (source == null) throw new IOException("旧模板读取失败");
            try {
                writeRegion(source, preset.primary, file(preset.id, "primary"));
                writeRegion(source, preset.union, file(preset.id, "union"));
                count++;
            } finally { source.recycle(); }
        }
        return count;
    }
    public void applyPreset(ArtworkDesign design, String id) {
        Preset selected = null;
        for (Preset preset : LEGACY) if (preset.id.equals(id)) selected = preset;
        design.templateId = selected == null ? "none" : selected.id;
        design.customLogoId = "";
        design.primary.enabled = selected != null;
        design.union.enabled = selected != null;
        if (selected == null) return;
        design.primary.x = .06f; design.primary.y = .08f;
        design.primary.width = selected.id.equals("jingjinji") ? .54f : .38f;
        design.primary.opacity = 1f;
        design.union.x = .77f; design.union.y = selected.unionY; design.union.width = .19f; design.union.opacity = 1f;
    }
    public Logos loadLogos(ArtworkDesign design) {
        Preset selected = null;
        for (Preset preset : LEGACY) if (preset.id.equals(design.templateId)) selected = preset;
        Bitmap primary = null, union = null;
        if (selected != null) {
            primary = BitmapFactory.decodeFile(file(selected.id, "primary").getPath());
            union = BitmapFactory.decodeFile(file(selected.id, "union").getPath());
        }
        if (isLogoId(design.customLogoId)) {
            Bitmap custom = BitmapFactory.decodeFile(new File(directory, design.customLogoId).getPath());
            if (custom != null) { if (primary != null) primary.recycle(); primary = custom; }
        }
        if ((design.primary.enabled && primary == null && (selected != null || isLogoId(design.customLogoId)))
                || (design.union.enabled && union == null && selected != null)) {
            if (primary != null) primary.recycle();
            if (union != null) union.recycle();
            throw new IllegalStateException("模板素材缺失，请重新导入或选择无模板");
        }
        return new Logos(primary, union);
    }
    public String saveCustomLogo(Bitmap bitmap) throws IOException {
        return saveCustomLogo(bitmap, "");
    }
    public String saveCustomLogo(Bitmap bitmap, String protectedDraftLogoId) throws IOException {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() > 2048 || bitmap.getHeight() > 2048)
            throw new IOException("请选择不超过 2048 像素的标志图片");
        ensureDirectory();
        File[] files = directory.listFiles((dir, name) -> isLogoId(name));
        Set<String> referenced = new HashSet<>();
        if (isLogoId(protectedDraftLogoId)) referenced.add(protectedDraftLogoId);
        for (CardEntry card : new SettingsStore(context).cards())
            referenced.add(ArtworkDesignJson.decode(card.designJson).customLogoId);
        if (files != null) for (File candidate : files)
            if (!referenced.contains(candidate.getName())) candidate.delete();
        files = directory.listFiles((dir, name) -> isLogoId(name));
        if (files != null && files.length >= 32) throw new IOException("标志素材已达 32 张上限");
        String id = UUID.randomUUID() + ".png";
        writePng(bitmap, new File(directory, id));
        return id;
    }
    private void writeRegion(Bitmap source, Rect region, File target) throws IOException {
        Bitmap cropped = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height());
        Bitmap trimmed = null;
        try {
            int w = cropped.getWidth(), h = cropped.getHeight(); int[] pixels = new int[w * h];
            cropped.getPixels(pixels, 0, w, 0, 0, w, h);
            int left = w, top = h, right = -1, bottom = -1;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((pixels[y * w + x] >>> 24) > 40) {
                left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y);
            }
            if (right < left || bottom < top) throw new IOException("旧模板缺少透明标志区域");
            trimmed = Bitmap.createBitmap(cropped, left, top, right - left + 1, bottom - top + 1);
            writePng(trimmed, target);
        } finally {
            if (trimmed != null && trimmed != cropped) trimmed.recycle();
            if (cropped != source) cropped.recycle();
        }
    }
    private static void writePng(Bitmap bitmap, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("标志保存失败");
            }
            if (!temporary.renameTo(target)) throw new IOException("标志写入失败");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private File file(String id, String layer) { return new File(directory, id + "-" + layer + ".png"); }
    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建模板目录");
    }
    private static boolean isLogoId(String id) {
        return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png");
    }
    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) throw new IOException("模板素材过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
