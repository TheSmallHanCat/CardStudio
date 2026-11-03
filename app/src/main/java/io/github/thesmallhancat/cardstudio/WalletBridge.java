package io.github.thesmallhancat.cardstudio;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded worker per supported wallet process; image hooks never perform IPC or disk I/O. */
final class WalletBridge {
    interface Reporter { void report(String message, Throwable error); }
    private final Reporter reporter;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "CardStudio-bridge");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean configDirty = new AtomicBoolean();
    private final AtomicBoolean reportedError = new AtomicBoolean();
    private final Set<String> observed = ConcurrentHashMap.newKeySet();
    private final Set<String> replaced = ConcurrentHashMap.newKeySet();
    private volatile Map<String, String> replacements = Collections.emptyMap();
    private volatile Context context;
    private volatile int hooks;
    private volatile String details = "";
    private volatile String error = "";
    // Retain the observer for the lifetime of this wallet process.
    private ContentObserver observer;

    WalletBridge(Reporter reporter) { this.reporter = reporter; worker.allowCoreThreadTimeOut(true); }

    void profile(int count, String details, String error) {
        this.hooks = count;
        this.details = details;
        this.error = error;
        if (context != null) worker.execute(this::reportStatus);
    }

    void connect(Context candidate) {
        if (candidate == null || !initialized.compareAndSet(false, true)) return;
        Context app = candidate.getApplicationContext();
        context = app == null ? candidate : app;
        worker.execute(() -> {
            try {
                observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { refresh(); }
                };
                context.getContentResolver().registerContentObserver(SettingsStore.CONFIG_URI, false, observer);
                loadConfig();
                reportStatus();
            } catch (Throwable ex) { fail("无法连接卡面工坊，请打开模块应用后重新进入钱包", ex); }
        });
    }

    String replacement(String source, boolean cardSurface, View image) {
        if (!SourcePolicy.isCardArtwork(source)) return null;
        if (context != null && (!SourcePolicy.isLocalArtwork(source) || cardSurface)
                && observed.size() < 100 && observed.add(source)) {
            Runnable report = () -> {
                Bundle label = new Bundle();
                label.putString("title", findLabel(image));
                worker.execute(() -> call("observe", source, label));
            };
            if (image == null) report.run(); else image.post(report);
        }
        String replacement = replacements.get(source);
        if (replacement != null && replaced.size() < 100 && replaced.add(replacement))
            worker.execute(() -> call("replaced", null, null));
        return replacement;
    }

    private static String findLabel(View image) {
        if (image == null) return "";
        ViewParent parent = image.getParent();
        for (int level = 0; level < 2 && parent instanceof View root; level++, parent = root.getParent()) {
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            for (int inspected = 0; inspected < 40 && !queue.isEmpty(); inspected++) {
                View current = queue.removeFirst();
                if (current instanceof TextView text && text.getId() != View.NO_ID && text.getId() != 0) {
                    try {
                        String name = text.getResources().getResourceEntryName(text.getId());
                        if ((name.equals("tv_title") || name.equals("card_name") || name.equals("tv_card_name"))
                                && text.getText() != null && text.getText().length() > 0 && text.getText().length() <= 48)
                            return text.getText().toString();
                    } catch (android.content.res.Resources.NotFoundException ignored) { }
                }
                if (current instanceof ViewGroup group)
                    for (int i = 0; i < Math.min(group.getChildCount(), 40 - queue.size()); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return "";
    }

    private void refresh() {
        configDirty.set(true);
        if (!refreshQueued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                // At most four IPC reads per task. Changes racing a read trigger another pass.
                for (int pass = 0; pass < 4; pass++) {
                    configDirty.set(false);
                    loadConfig();
                    if (!configDirty.get()) break;
                }
            } finally {
                refreshQueued.set(false);
                if (configDirty.get()) refresh();
            }
        });
    }

    private void loadConfig() {
        try {
            Bundle bundle = call("config", null, null);
            if (bundle == null || !bundle.getBoolean("enabled", false)) {
                replacements = Collections.emptyMap(); return;
            }
            JSONArray rules = new JSONArray(bundle.getString("rules", "[]"));
            Map<String, String> next = new HashMap<>();
            for (int i = 0; i < Math.min(100, rules.length()); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null) continue;
                String source = rule.optString("source");
                String target = rule.optString("replacement");
                android.net.Uri uri = android.net.Uri.parse(target);
                if (SourcePolicy.isCardArtwork(source) && "content".equals(uri.getScheme())
                        && SettingsStore.AUTHORITY.equals(uri.getAuthority())
                        && uri.getPathSegments().size() == 2
                        && "images".equals(uri.getPathSegments().get(0))
                        && SourcePolicy.isImageId(uri.getLastPathSegment())) next.put(source, target);
            }
            replacements = Collections.unmodifiableMap(next);
        } catch (Throwable ex) {
            replacements = Collections.emptyMap();
            fail("读取卡面配置失败，已保留钱包原图", ex);
        }
    }

    private void reportStatus() {
        try {
            Bundle status = new Bundle();
            status.putInt("hooks", hooks);
            status.putString("details", details);
            status.putString("error", error);
            status.putString("version", context.getPackageManager()
                    .getPackageInfo("com.finshell.wallet", 0).versionName);
            call("status", null, status);
        } catch (Throwable ex) { fail("无法记录适配状态", ex); }
    }

    private Bundle call(String method, String arg, Bundle extras) {
        try { return context.getContentResolver().call(SettingsStore.CONFIG_URI, method, arg, extras); }
        catch (Throwable ex) { fail("卡面桥接不可用，继续使用原图", ex); return null; }
    }

    private void fail(String message, Throwable ex) {
        if (reportedError.compareAndSet(false, true)) reporter.report(message, ex);
    }
}
