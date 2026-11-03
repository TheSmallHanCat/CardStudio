package io.github.thesmallhancat.cardstudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.FileNotFoundException;
import java.util.Arrays;

/** Read-only artwork bridge. Only this app and the selected wallet package can access it. */
public final class StudioProvider extends ContentProvider {
    private SettingsStore store;
    @Override public boolean onCreate() { store = new SettingsStore(providerContext()); return true; }
    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider 尚未初始化");
        return context;
    }
    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = providerContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null && Arrays.asList(packages).contains("com.finshell.wallet")) return;
        throw new SecurityException("仅向钱包提供卡面图片");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        switch (method) {
            case "config": return store.config();
            case "observe": store.observe(arg, extras == null ? "" : extras.getString("title", "")); break;
            case "status": if (extras != null) store.recordStatus(extras); break;
            case "replaced": store.recordReplacement(); break;
            default: throw new IllegalArgumentException("未知操作");
        }
        return Bundle.EMPTY;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2
                || !"images".equals(uri.getPathSegments().get(0))
                || !SourcePolicy.isImageId(uri.getLastPathSegment())) throw new FileNotFoundException("无效图片请求");
        return ParcelFileDescriptor.open(store.imageFile(uri.getLastPathSegment()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { checkCaller(); return "image/jpeg"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { checkCaller(); return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("只读接口"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("只读接口"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("只读接口"); }
}
