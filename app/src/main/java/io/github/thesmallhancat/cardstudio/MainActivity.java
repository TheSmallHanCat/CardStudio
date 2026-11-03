package io.github.thesmallhancat.cardstudio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final AtomicBoolean PROCESS_SAVE_BUSY = new AtomicBoolean(false);
    private static volatile long SAVE_VERSION;
    private static volatile boolean LAST_SAVE_SUCCESS;
    private static final Object ACTIVE_ACTIVITY_LOCK = new Object();
    private static WeakReference<MainActivity> activeActivity = new WeakReference<>(null);
    private static final int REQUEST_IMAGE = 4201;
    private static final int REQUEST_LOGO = 4202;
    private static final int INK = Color.rgb(32, 37, 39);
    private static final int MUTED = Color.rgb(100, 108, 113);
    private static final int INDIGO = Color.rgb(64, 92, 228);
    private static final int CREAM = Color.rgb(247, 246, 243);
    private static final int DIVIDER = Color.rgb(222, 222, 218);
    private static final int SOFT_GREEN = Color.rgb(70, 128, 96);

    private SettingsStore store;
    private FrameLibrary frameLibrary;
    private ExecutorService imageExecutor;
    private Handler mainHandler;
    private LinearLayout root;
    private FrameLayout content;
    private LinearLayout bottomActionBar;
    private ScrollView pageScroll;
    private int page;
    private String selectedUrl;
    private List<CardEntry> cards = new ArrayList<>();
    private ArtworkEditorView editorView;
    private Bitmap selectedBitmap;
    private ArtworkDesign editorDesign;
    private FrameLibrary.Logos editorLogos;
    private LinearLayout editorControls;
    private LinearLayout templateOptions;
    private LinearLayout editorTabs;
    private int editorTarget = ArtworkDesign.BACKGROUND;
    private boolean editorMigratedLegacy;
    private boolean logosLoading;
    private boolean logosLoadFailed;
    private long logosGeneration;
    private long editorGeneration;
    private boolean saving;
    private Bitmap savingSnapshot;
    private Bitmap savingBaseSnapshot;
    private Button activeSaveButton;
    private Button activeTitleButton;
    private Button activeRestoreButton;
    private Button activeChooseButton;
    private Switch activeToggle;
    private OnBackInvokedCallback backInvokedCallback;
    private boolean adjustingSwitch;
    private boolean editorMode;
    private String editorTargetUrl;
    private long editorSaveVersion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SettingsStore(this);
        frameLibrary = new FrameLibrary(this);
        imageExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        if (state != null) {
            page = state.getInt("page", 0);
            selectedUrl = state.getString("selected_url");
        }
        configureWindow();
        buildShell();
        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
        refreshState();
        if (isSaveBusy()) {
            saving = true;
            setOperationControlsEnabled(false);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(CREAM);
        window.setNavigationBarColor(CREAM);
        if (Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomActionBar = column();
        bottomActionBar.setBackgroundColor(CREAM);
        root.addView(bottomActionBar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        registerVisibleActivity(this);
        if (store != null && !isSaveBusy()) {
            saving = false;
            if (editorMode && SAVE_VERSION != editorSaveVersion) {
                finishSaveUi(LAST_SAVE_SUCCESS);
                return;
            }
            if (editorMode && selectedBitmap != null && editorView != null) return;
            refreshState();
        } else if (isSaveBusy()) {
            saving = true;
            setOperationControlsEnabled(false);
        }
    }

    @Override protected void onPause() {
        unregisterVisibleActivity(this);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page);
        out.putString("selected_url", selectedUrl);
        super.onSaveInstanceState(out);
    }

    private void refreshState() {
        cards = store.cards();
        if (selectedUrl != null && findCard(selectedUrl) == null) selectedUrl = null;
        if (selectedUrl == null && !cards.isEmpty()) selectedUrl = cards.get(0).url;
        renderPage(page);
    }

    private void renderPage(int index) {
        content.removeAllViews();
        if (!editorMode) closeEditorLogos();
        editorView = null;
        editorControls = null;
        editorTabs = null;
        activeSaveButton = null;
        activeTitleButton = null;
        activeRestoreButton = null;
        activeChooseButton = null;
        activeToggle = null;
        if (!editorMode && selectedBitmap != null && !selectedBitmap.isRecycled()) selectedBitmap.recycle();
        if (!editorMode) selectedBitmap = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        pageScroll = scroll;
        LinearLayout body = column();
        body.setPadding(dp(20), dp(10), dp(20), dp(22));
        if (editorMode && selectedBitmap != null) renderEditor(body);
        else if (index == 0) renderArtwork(body);
        else renderSettings(body);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        renderBottomActions();
    }

    private void closeEditorLogos() {
        logosGeneration++;
        logosLoading = false;
        logosLoadFailed = false;
        FrameLibrary.Logos old = editorLogos;
        editorLogos = null;
        if (old != null) old.close();
    }

    private void renderArtwork(LinearLayout body) {
        body.addView(appBar("卡面工坊", false), wrap());
        body.addView(statusLine(), margin(0, 2, 0, 12));
        if (cards.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(new CardMarkView(this), new LinearLayout.LayoutParams(dp(56), dp(42)));
            empty.addView(text("还没有发现卡面", 17, INK), margin(0, 12, 0, 0));
            empty.addView(text("请在 LSPosed 勾选模块和钱包作用域，再打开钱包查看卡片。", 14, MUTED), margin(0, 6, 0, 0));
            body.addView(empty, margin(0, 34, 0, 0));
            return;
        }

        LinearLayout section = row();
        section.addView(text("我的卡面", 16, INK), new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(text(String.valueOf(cards.size()), 13, MUTED), contentWrap());
        body.addView(section, margin(0, 2, 0, 8));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout choices = row();
        for (CardEntry card : cards) {
            String title = card.title == null || card.title.trim().isEmpty() ? "钱包卡面" : card.title;
            TextView choice = text(title, 14, card.url.equals(selectedUrl) ? INDIGO : INK);
            choice.setGravity(Gravity.CENTER);
            choice.setSingleLine(true);
            choice.setEllipsize(android.text.TextUtils.TruncateAt.END);
            choice.setPadding(dp(10), 0, dp(10), 0);
            choice.setBackground(outlineRound(card.url.equals(selectedUrl) ? 0x163F5CE4 : Color.TRANSPARENT,
                    card.url.equals(selectedUrl) ? 0x443F5CE4 : DIVIDER, 10));
            choice.setOnClickListener(v -> selectCard(card.url));
            int width = Math.min(dp(128), Math.max(dp(72), dp(28 + Math.min(12, title.length()) * 8)));
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(width, dp(46));
            choiceParams.rightMargin = dp(6);
            choices.addView(choice, choiceParams);
        }
        horizontal.addView(choices, new HorizontalScrollView.LayoutParams(-2, dp(48)));
        body.addView(horizontal, new LinearLayout.LayoutParams(-1, dp(48)));
        renderSelectedCard(body);
    }

    private void renderSelectedCard(LinearLayout body) {
        CardEntry card = findCard(selectedUrl);
        if (card == null) return;
        AspectFrameLayout frame = new AspectFrameLayout(this);
        frame.setBackground(round(0xFFE8E8E3, 16));
        if (card.hasCustomImage()) {
            ImageView saved = new ImageView(this);
            saved.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(saved, new FrameLayout.LayoutParams(-1, -1));
            loadStoredImage(card, saved, frame);
        } else {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.addView(new CardMarkView(this), new LinearLayout.LayoutParams(dp(54), dp(40)));
            empty.addView(text("选择图片", 14, MUTED), margin(0, 8, 0, 0));
            empty.setOnClickListener(v -> chooseImage(card.url));
            frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, -2);
        frameParams.topMargin = dp(14);
        body.addView(frame, frameParams);

        LinearLayout nameRow = row();
        String title = card.title == null || card.title.trim().isEmpty() ? "钱包卡面" : card.title;
        TextView name = text(title, 17, INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button rename = textAction("重命名");
        activeTitleButton = rename;
        rename.setOnClickListener(v -> renameCard(card));
        nameRow.addView(rename, contentWrap());
        body.addView(nameRow, margin(0, 10, 0, 0));
        if (card.hasCustomImage()) {
            Button restore = textAction("恢复默认卡面");
            activeRestoreButton = restore;
            restore.setOnClickListener(v -> confirmRestore(card));
            body.addView(restore, margin(-12, 2, 0, 0));
            Button edit = textAction("编辑模板");
            edit.setOnClickListener(v -> openExistingEditor(card));
            body.addView(edit, margin(-12, 0, 0, 0));
        }
    }

    private void selectCard(String url) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url == null || url.equals(selectedUrl)) return;
        selectedUrl = url;
        editorGeneration++;
        renderPage(0);
    }

    private void chooseImage(String targetUrl) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedUrl = targetUrl;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_IMAGE && requestCode != REQUEST_LOGO)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (requestCode == REQUEST_LOGO) {
            handleLogoResult(data.getData());
            return;
        }
        final String targetUrl = selectedUrl;
        if (targetUrl == null || findCard(targetUrl) == null) return;
        final Uri uri = data.getData();
        final long generation = ++editorGeneration;
        imageExecutor.execute(() -> {
            Bitmap decoded = decodeUri(uri);
            mainHandler.post(() -> {
                if (isFinishingOrDestroyed() || isSaveBusy() || generation != editorGeneration
                        || !targetUrl.equals(selectedUrl)) {
                    if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                    return;
                }
                if (decoded == null) {
                    Toast.makeText(this, "图片读取失败，请选择另一张图片", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (editorMode && targetUrl.equals(editorTargetUrl)) {
                    replaceEditorBackground(decoded);
                } else {
                    showEditor(targetUrl, decoded);
                }
            });
        });
        return;
    }

    private void showEditor(String targetUrl, Bitmap bitmap) {
        CardEntry card = findCard(targetUrl);
        if (card == null) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        ArtworkDesign design = new ArtworkDesign();
        if (card.hasEditableDesign()) {
            try { design = ArtworkDesignJson.decode(card.designJson); }
            catch (Exception ignored) { }
        }
        startEditor(targetUrl, bitmap, design);
    }

    private void renderEditor(LinearLayout body) {
        body.addView(appBar("调整卡面", true), wrap());
        TextView hint = text("拖动调整位置 · 16:10", 14, MUTED);
        hint.setSingleLine(true);
        body.addView(hint, margin(0, 4, 0, 12));
        editorView = new ArtworkEditorView(this);
        editorView.setImage(selectedBitmap);
        editorView.setDesign(editorDesign == null ? new ArtworkDesign() : editorDesign.copy());
        editorView.setEditTarget(editorTarget);
        AspectFrameLayout editorFrame = new AspectFrameLayout(this);
        editorFrame.setBackground(round(0xFFE8E8E3, 16));
        editorFrame.addView(editorView, new FrameLayout.LayoutParams(-1, -1));
        body.addView(editorFrame, fixedHeightMargin(0, 0, 0, 0, dp(210)));
        if (editorLogos != null) editorView.setLogos(editorLogos.primary, editorLogos.union);

        editorTabs = row();
        editorTabs.setPadding(0, dp(10), 0, dp(2));
        addEditorTab("背景", ArtworkDesign.BACKGROUND);
        addEditorTab("主标志", ArtworkDesign.PRIMARY_LOGO);
        addEditorTab("联合标志", ArtworkDesign.UNION_LOGO);
        addEditorTab("文字", ArtworkDesign.TEXT);
        body.addView(editorTabs, new LinearLayout.LayoutParams(-1, dp(58)));

        editorControls = column();
        body.addView(editorControls, wrap());
        renderEditorControls();
        addTemplateRow(body);
    }

    private void addEditorTab(String label, int target) {
        TextView tab = text(label, 13, target == editorTarget ? INK : MUTED);
        tab.setGravity(Gravity.CENTER);
        tab.setSingleLine(true);
        tab.setBackground(target == editorTarget ? outlineRound(0x143F5CE4, 0x55405CE4, 10) : round(Color.TRANSPARENT, 10));
        tab.setOnClickListener(v -> {
            if (editorTarget == ArtworkDesign.TEXT) syncTextLayer();
            editorTarget = target;
            if (editorView != null) editorView.setEditTarget(target);
            for (int i = 0; i < editorTabs.getChildCount(); i++) {
                TextView item = (TextView) editorTabs.getChildAt(i);
                int itemTarget = i;
                item.setTextColor(itemTarget == editorTarget ? INK : MUTED);
                item.setBackground(itemTarget == editorTarget ? outlineRound(0x143F5CE4, 0x55405CE4, 10) : round(Color.TRANSPARENT, 10));
            }
            renderEditorControls();
        });
        editorTabs.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1));
    }

    private void renderEditorControls() {
        if (editorControls == null) return;
        editorControls.removeAllViews();
        if (editorView == null) return;
        if (editorTarget == ArtworkDesign.BACKGROUND) {
            Button choose = subtleButton("更换背景图片");
            choose.setOnClickListener(v -> chooseEditorBackground());
            editorControls.addView(choose, wrap());
            editorControls.addView(text("背景图可拖动和缩放，其他图层会保留。", 13, MUTED), margin(0, 7, 0, 0));
            editorControls.addView(backgroundZoomRow(), margin(0, 2, 0, 0));
            return;
        }
        if (editorTarget == ArtworkDesign.TEXT) {
            EditText title = compactEdit("标题", editorView.getDesign().text.title);
            EditText subtitle = compactEdit("副标题", editorView.getDesign().text.subtitle);
            editorControls.addView(title, wrap());
            editorControls.addView(subtitle, margin(0, 8, 0, 0));
            LinearLayout colors = row();
            Button white = subtleButton("白色文字");
            white.setOnClickListener(v -> editorView.setText(title.getText().toString(), subtitle.getText().toString(), Color.WHITE));
            colors.addView(white, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button dark = subtleButton("深色文字");
            dark.setOnClickListener(v -> editorView.setText(title.getText().toString(), subtitle.getText().toString(), INK));
            colors.addView(dark, contentMargin(8, 0, 0, 0));
            editorControls.addView(colors, margin(0, 8, 0, 0));
            addLayerSliders(editorControls, ArtworkDesign.TEXT, editorView.getDesign().text.enabled,
                    layerScale(ArtworkDesign.TEXT), layerOpacity(ArtworkDesign.TEXT));
            return;
        }
        ArtworkDesign.Layer layer = editorTarget == ArtworkDesign.PRIMARY_LOGO
                ? editorView.getDesign().primary : editorView.getDesign().union;
        addLayerSliders(editorControls, editorTarget, layer.enabled, layerScale(editorTarget), layerOpacity(editorTarget));
        if (editorTarget == ArtworkDesign.PRIMARY_LOGO) {
            Button importLogo = subtleButton("导入透明 PNG 标志");
            importLogo.setOnClickListener(v -> chooseLogo());
            editorControls.addView(importLogo, margin(0, 10, 0, 0));
        } else {
            editorControls.addView(text("联合标志跟随所选交通模板。", 13, MUTED), margin(0, 8, 0, 0));
        }
    }

    private EditText compactEdit(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setMinimumHeight(dp(48));
        input.setTextColor(INK);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(round(0xFFF0EFEC, 10));
        input.setOnFocusChangeListener((v, focused) -> { if (!focused) syncTextLayer(); });
        return input;
    }

    private void syncTextLayer() {
        if (editorControls == null || editorControls.getChildCount() < 2 || editorView == null
                || !(editorControls.getChildAt(0) instanceof EditText)
                || !(editorControls.getChildAt(1) instanceof EditText)) return;
        EditText title = (EditText) editorControls.getChildAt(0);
        EditText subtitle = (EditText) editorControls.getChildAt(1);
        boolean enabled = editorView.getDesign().text.enabled;
        editorView.setText(title.getText().toString(), subtitle.getText().toString(), editorView.getDesign().text.color);
        editorView.setLayerEnabled(ArtworkDesign.TEXT, enabled);
    }

    private float layerScale(int target) {
        editorView.setEditTarget(target);
        ArtworkDesign.Layer layer = target == ArtworkDesign.TEXT ? editorView.getDesign().text
                : target == ArtworkDesign.PRIMARY_LOGO ? editorView.getDesign().primary : editorView.getDesign().union;
        return layer.width;
    }

    private float layerOpacity(int target) {
        editorView.setEditTarget(target);
        ArtworkDesign.Layer layer = target == ArtworkDesign.TEXT ? editorView.getDesign().text
                : target == ArtworkDesign.PRIMARY_LOGO ? editorView.getDesign().primary : editorView.getDesign().union;
        return layer.opacity;
    }

    private void addLayerSliders(LinearLayout parent, int target, boolean enabled, float scale, float opacity) {
        Switch visible = new Switch(this);
        visible.setChecked(enabled);
        LinearLayout visibleRow = row();
        visibleRow.addView(text("显示图层", 14, INK), new LinearLayout.LayoutParams(0, dp(48), 1));
        visible.setOnCheckedChangeListener((button, checked) -> { if (editorView != null) editorView.setLayerEnabled(target, checked); });
        visibleRow.addView(visible, new LinearLayout.LayoutParams(dp(56), dp(48)));
        parent.addView(visibleRow, wrap());
        parent.addView(sliderRow("大小", scale, target, true), margin(0, 2, 0, 0));
        parent.addView(sliderRow("透明度", opacity, target, false), margin(0, 2, 0, 0));
    }

    private LinearLayout sliderRow(String label, float value, int target, boolean scale) {
        LinearLayout line = row();
        line.addView(text(label, 13, MUTED), new LinearLayout.LayoutParams(dp(48), dp(48)));
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        float min = scale && target == ArtworkDesign.TEXT ? ArtworkDesign.MIN_TEXT_WIDTH : ArtworkDesign.MIN_LOGO_WIDTH;
        float max = scale && target == ArtworkDesign.TEXT ? ArtworkDesign.MAX_TEXT_WIDTH : ArtworkDesign.MAX_LOGO_WIDTH;
        bar.setProgress(Math.round((scale ? (value - min) / (max - min) : value) * 100f));
        TextView readout = text(String.format(Locale.US, "%d%%", Math.round((scale ? value / max : value) * 100f)), 12, MUTED);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                float next = scale ? min + (max - min) * progress / 100f : progress / 100f;
                if (editorView != null) {
                    editorView.setEditTarget(target);
                    if (scale) editorView.setLayerScale(next); else editorView.setLayerOpacity(next);
                }
                readout.setText(String.format(Locale.US, "%d%%", Math.round((scale ? next / max : next) * 100f)));
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        line.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
        line.addView(readout, contentMargin(8, 0, 0, 0));
        return line;
    }

    private LinearLayout backgroundZoomRow() {
        LinearLayout line = row();
        line.addView(text("缩放", 13, MUTED), new LinearLayout.LayoutParams(dp(48), dp(48)));
        SeekBar bar = new SeekBar(this);
        bar.setMax(20);
        int progress = Math.round((editorView.getZoom() - 1f) * 10f);
        bar.setProgress(Math.max(0, Math.min(20, progress)));
        TextView readout = text(String.format(Locale.US, "%.1f×", editorView.getZoom()), 12, MUTED);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                float zoom = 1f + value / 10f;
                editorView.setZoom(zoom);
                readout.setText(String.format(Locale.US, "%.1f×", zoom));
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        line.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
        line.addView(readout, contentMargin(8, 0, 0, 0));
        return line;
    }

    private void addTemplateRow(LinearLayout body) {
        LinearLayout templates = row();
        templateOptions = templates;
        templates.setPadding(0, dp(10), 0, dp(2));
        populateTemplateOptions();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(templates, new HorizontalScrollView.LayoutParams(-2, dp(52)));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(54)));
    }

    private void addTemplateButton(LinearLayout row, String title, String id) {
        Button button = textAction(title);
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setTag(id);
        boolean selected = editorView != null && id.equals(editorView.getDesign().templateId);
        button.setTextColor(selected ? INDIGO : MUTED);
        button.setBackground(touchRound(selected ? 0x16405CE4 : Color.TRANSPARENT, 0x18000000, 10));
        button.setOnClickListener(v -> applyTemplate(id));
        row.addView(button, contentWrap());
    }

    private void populateTemplateOptions() {
        if (templateOptions == null) return;
        templateOptions.removeAllViews();
        addTemplateButton(templateOptions, "无", "none");
        List<FrameLibrary.Preset> presets = frameLibrary.availablePresets();
        for (FrameLibrary.Preset preset : presets) addTemplateButton(templateOptions, preset.title, preset.id);
        if (presets.isEmpty()) {
            Button importButton = textAction("导入交通模板");
            importButton.setOnClickListener(v -> importLegacyTemplates());
            templateOptions.addView(importButton, contentWrap());
        }
    }

    private void applyTemplate(String id) {
        if (editorView == null || isSaveBusy()) return;
        if (editorTarget == ArtworkDesign.TEXT) syncTextLayer();
        ArtworkDesign next = editorView.getDesign();
        frameLibrary.applyPreset(next, id);
        editorView.setDesign(next);
        editorDesign = next.copy();
        loadEditorLogos(next);
        renderEditorControls();
        populateTemplateOptions();
    }

    private void chooseEditorBackground() {
        if (isSaveBusy() || editorTargetUrl == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    private void chooseLogo() {
        if (isSaveBusy() || editorTargetUrl == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, REQUEST_LOGO);
    }

    private void openExistingEditor(CardEntry card) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        final long generation = ++editorGeneration;
        final String targetUrl = card.url;
        imageExecutor.execute(() -> {
            ArtworkDesign design = new ArtworkDesign();
            Bitmap background = null;
            boolean migrated = false;
            try {
                if (card.hasEditableDesign()) {
                    design = ArtworkDesignJson.decode(card.designJson);
                    background = decodeFile(store.imageFile(card.baseImageId));
                } else if (card.hasCustomImage()) {
                    background = decodeFile(store.imageFile(card.imageId));
                    migrated = true;
                }
            } catch (Exception ignored) { }
            ArtworkDesign finalDesign = design == null ? new ArtworkDesign() : design;
            Bitmap finalBackground = background;
            boolean finalMigrated = migrated;
            mainHandler.post(() -> {
                if (isFinishingOrDestroyed() || isSaveBusy() || generation != editorGeneration
                        || !targetUrl.equals(selectedUrl)) {
                    if (finalBackground != null && !finalBackground.isRecycled()) finalBackground.recycle();
                    return;
                }
                if (finalBackground == null) {
                    Toast.makeText(this, "原卡面读取失败，请先选择背景图片", Toast.LENGTH_SHORT).show();
                    chooseImage(targetUrl);
                    return;
                }
                editorMigratedLegacy = finalMigrated;
                if (finalMigrated) Toast.makeText(this, "旧版卡面将作为背景重新编辑", Toast.LENGTH_SHORT).show();
                startEditor(targetUrl, finalBackground, finalDesign);
            });
        });
    }

    private void startEditor(String targetUrl, Bitmap background, ArtworkDesign design) {
        selectedBitmap = background;
        editorDesign = design == null ? new ArtworkDesign() : design.copy();
        editorTargetUrl = targetUrl;
        editorSaveVersion = SAVE_VERSION;
        page = 0;
        editorMode = true;
        editorTarget = ArtworkDesign.BACKGROUND;
        loadEditorLogos(editorDesign);
        renderPage(0);
    }

    private void replaceEditorBackground(Bitmap bitmap) {
        if (editorView == null || bitmap == null) return;
        Bitmap old = selectedBitmap;
        editorView.setImage(bitmap);
        selectedBitmap = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    private void loadEditorLogos(ArtworkDesign design) {
        final long logoVersion = ++logosGeneration;
        final ArtworkDesign snapshot = design == null ? new ArtworkDesign() : design.copy();
        logosLoading = true;
        logosLoadFailed = false;
        if (editorView != null) {
            FrameLibrary.Logos old = editorLogos;
            editorLogos = null;
            editorView.setLogos(null, null);
            if (old != null) old.close();
        }
        if (activeSaveButton != null) activeSaveButton.setEnabled(false);
        imageExecutor.execute(() -> {
            FrameLibrary.Logos logos = null;
            String failure = null;
            try { logos = frameLibrary.loadLogos(snapshot); }
            catch (RuntimeException ex) { failure = ex.getMessage(); }
            final FrameLibrary.Logos loaded = logos;
            final String error = failure;
            mainHandler.post(() -> {
                if (isFinishingOrDestroyed() || !editorMode
                        || editorView == null || logoVersion != logosGeneration) {
                    if (loaded != null) loaded.close();
                    return;
                }
                if (loaded == null) {
                    logosLoading = false;
                    logosLoadFailed = true;
                    if (activeSaveButton != null) activeSaveButton.setEnabled(false);
                    editorView.setLogos(null, null);
                    Toast.makeText(this, error == null ? "模板素材读取失败，请重新导入模板" : "模板素材读取失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                editorView.setLogos(loaded.primary, loaded.union);
                FrameLibrary.Logos old = editorLogos;
                editorLogos = loaded;
                logosLoading = false;
                logosLoadFailed = false;
                if (activeSaveButton != null) activeSaveButton.setEnabled(!isSaveBusy());
                if (old != null) old.close();
            });
        });
    }

    private void handleLogoResult(Uri uri) {
        if (!editorMode || editorView == null || editorTargetUrl == null) return;
        final long generation = ++editorGeneration;
        final String protectedLogoId = editorView.getDesign().customLogoId;
        imageExecutor.execute(() -> {
            Bitmap decoded = decodeUri(uri);
            if (decoded == null) {
                mainHandler.post(() -> Toast.makeText(this, "透明 PNG 读取失败，请重试", Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                String logoId = frameLibrary.saveCustomLogo(decoded, protectedLogoId);
                mainHandler.post(() -> {
                    if (isFinishingOrDestroyed() || isSaveBusy() || generation != editorGeneration
                            || !editorMode || editorView == null) return;
                    ArtworkDesign next = editorView.getDesign();
                    next.customLogoId = logoId;
                    next.primary.enabled = true;
                    editorView.setDesign(next);
                    editorDesign = next.copy();
                    loadEditorLogos(next);
                    Toast.makeText(this, "主标志已导入", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ex) {
                mainHandler.post(() -> Toast.makeText(this, "标志保存失败，请重试", Toast.LENGTH_SHORT).show());
            } finally {
                if (!decoded.isRecycled()) decoded.recycle();
            }
        });
    }

    private void cancelEditor() {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        editorMode = false;
        editorTargetUrl = null;
        page = 0;
        editorGeneration++;
        renderPage(0);
    }

    private LinearLayout appBar(String title, boolean back) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (back) {
            HeaderIconView backView = new HeaderIconView(this, HeaderIconView.BACK);
            backView.setContentDescription("返回");
            backView.setBackground(touchRound(Color.TRANSPARENT, 0x18000000, 12));
            backView.setOnClickListener(v -> cancelEditorOrSettings());
            bar.addView(backView, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        TextView heading = text(title, 23, INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1));
        if (!back) {
            HeaderIconView settings = new HeaderIconView(this, HeaderIconView.SLIDERS);
            settings.setContentDescription("设置");
            settings.setBackground(touchRound(Color.TRANSPARENT, 0x18000000, 12));
            settings.setOnClickListener(v -> { editorGeneration++; page = 1; renderPage(1); });
            bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return bar;
    }

    private void cancelEditorOrSettings() {
        if (editorMode) cancelEditor();
        else {
            editorGeneration++;
            page = 0;
            renderPage(0);
        }
    }

    private LinearLayout statusLine() {
        LinearLayout line = row();
        StatusDot dot = new StatusDot(this, statusColor());
        line.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView status = text(mainStatusText(), 13, MUTED);
        line.addView(status, contentMargin(8, 0, 0, 0));
        return line;
    }

    private int statusColor() {
        if (!store.isEnabled() || store.lastConnected() <= 0) return MUTED;
        if (store.hookError() != null && !store.hookError().trim().isEmpty()) return Color.rgb(176, 112, 56);
        return SOFT_GREEN;
    }

    private String mainStatusText() {
        if (!store.isEnabled()) return "已关闭";
        if (store.hookError() != null && !store.hookError().trim().isEmpty()) return "连接有误 · 去设置查看";
        if (store.lastConnected() <= 0) return "等待钱包连接";
        return "最近连接 " + formatClock(store.lastConnected());
    }

    private void renderBottomActions() {
        if (bottomActionBar == null) return;
        bottomActionBar.removeAllViews();
        bottomActionBar.setVisibility(View.VISIBLE);
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        bottomActionBar.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout actions = row();
        actions.setPadding(dp(20), dp(8), dp(20), dp(8));
        if (editorMode) {
            Button cancel = subtleButton("取消");
            cancel.setOnClickListener(v -> cancelEditor());
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button save = primaryButton("保存卡面");
            activeSaveButton = save;
            save.setEnabled(!logosLoading && !logosLoadFailed && !isSaveBusy());
            save.setOnClickListener(v -> saveArtwork(editorTargetUrl));
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            saveParams.leftMargin = dp(10);
            actions.addView(save, saveParams);
        } else if (page == 0 && cards.isEmpty()) {
            Button open = primaryButton("打开钱包");
            open.setOnClickListener(v -> openWallet());
            actions.addView(open, new LinearLayout.LayoutParams(-1, dp(48)));
        } else if (page == 0) {
            CardEntry selected = findCard(selectedUrl);
            Button choose = primaryButton(selected != null && selected.hasCustomImage() ? "更换图片" : "选择图片");
            activeChooseButton = choose;
            choose.setOnClickListener(v -> chooseImage(selectedUrl));
            actions.addView(choose, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button wallet = subtleButton("查看钱包");
            wallet.setOnClickListener(v -> openWallet());
            actions.addView(wallet, margin(10, 0, 0, 0, 1, dp(48)));
        } else {
            bottomActionBar.setVisibility(View.GONE);
            return;
        }
        bottomActionBar.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    }

    private void saveArtwork(String targetUrl) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (logosLoading) {
            Toast.makeText(this, "正在载入模板素材，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (logosLoadFailed) {
            Toast.makeText(this, "模板素材读取失败，请重新导入或选择无模板", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editorView == null || editorView.getImage() == null || !targetUrl.equals(selectedUrl)
                || !editorMode || !targetUrl.equals(editorTargetUrl)) {
            Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PROCESS_SAVE_BUSY.compareAndSet(false, true)) {
            saving = true;
            setOperationControlsEnabled(false);
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        setOperationControlsEnabled(false);
        syncTextLayer();
        Bitmap snapshot = null;
        Bitmap baseSnapshot = null;
        try {
            snapshot = editorView.exportBitmap();
            baseSnapshot = editorView.exportBackgroundBitmap();
        }
        catch (RuntimeException | OutOfMemoryError ex) {
            if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
            if (baseSnapshot != null && !baseSnapshot.isRecycled()) baseSnapshot.recycle();
            PROCESS_SAVE_BUSY.set(false);
            saving = false;
            setOperationControlsEnabled(true);
            Toast.makeText(this, "无法生成图片预览", Toast.LENGTH_SHORT).show();
            return;
        }
        if (snapshot == null || baseSnapshot == null) {
            if (snapshot != null && !snapshot.isRecycled()) snapshot.recycle();
            if (baseSnapshot != null && !baseSnapshot.isRecycled()) baseSnapshot.recycle();
            PROCESS_SAVE_BUSY.set(false);
            saving = false;
            setOperationControlsEnabled(true);
            Toast.makeText(this, "无法生成图片预览", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap finalSnapshot = snapshot;
        final Bitmap finalBaseSnapshot = baseSnapshot;
        final String imageId = UUID.randomUUID().toString().toLowerCase(Locale.US) + ".jpg";
        final String baseImageId = UUID.randomUUID().toString().toLowerCase(Locale.US) + ".jpg";
        CardEntry currentCard = findCard(targetUrl);
        final String title = currentCard == null || currentCard.title == null ? "我的卡面" : currentCard.title.trim();
        final String designJson;
        try { designJson = ArtworkDesignJson.encode(editorView.getDesign()); }
        catch (RuntimeException ex) {
            if (!snapshot.isRecycled()) snapshot.recycle();
            if (!baseSnapshot.isRecycled()) baseSnapshot.recycle();
            PROCESS_SAVE_BUSY.set(false);
            saving = false;
            setOperationControlsEnabled(true);
            Toast.makeText(this, "无法读取图层设置", Toast.LENGTH_SHORT).show();
            return;
        }
        savingSnapshot = finalSnapshot;
        savingBaseSnapshot = finalBaseSnapshot;
        editorSaveVersion = SAVE_VERSION;
        final SettingsStore saveStore = store;
        try {
            imageExecutor.execute(() -> {
                File file = null;
                File baseFile = null;
                boolean success = false;
                try {
                    file = saveStore.imageFile(imageId);
                    baseFile = saveStore.imageFile(baseImageId);
                    writeJpeg(finalSnapshot, file);
                    writeJpeg(finalBaseSnapshot, baseFile);
                    saveStore.setArtwork(targetUrl, imageId, baseImageId, title, designJson);
                    success = file.isFile() && baseFile.isFile();
                } catch (Exception ex) {
                    if (file != null) file.delete();
                    if (baseFile != null) baseFile.delete();
                } finally {
                    if (!finalSnapshot.isRecycled()) finalSnapshot.recycle();
                    if (!finalBaseSnapshot.isRecycled()) finalBaseSnapshot.recycle();
                    LAST_SAVE_SUCCESS = success;
                    SAVE_VERSION++;
                    PROCESS_SAVE_BUSY.set(false);
                    notifySaveFinished(success);
                }
            });
        } catch (RuntimeException ex) {
            PROCESS_SAVE_BUSY.set(false);
            if (!finalSnapshot.isRecycled()) finalSnapshot.recycle();
            if (!finalBaseSnapshot.isRecycled()) finalBaseSnapshot.recycle();
            savingSnapshot = null;
            savingBaseSnapshot = null;
            saving = false;
            setOperationControlsEnabled(true);
            Toast.makeText(this, "保存任务无法启动，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private static void writeJpeg(Bitmap bitmap, File target) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException("编码失败");
            output.flush();
        }
    }

    private void renameCard(CardEntry card) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(card.title);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(4), 0, dp(4), 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("重命名卡面")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (isSaveBusy()) {
                Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                store.rename(card.url, input.getText().toString());
                dialog.dismiss();
                refreshState();
            } catch (Exception ex) {
                Toast.makeText(this, "名称保存失败，请重试", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }

    private void loadStoredImage(CardEntry card, ImageView image, AspectFrameLayout frame) {
        final String targetUrl = card.url;
        imageExecutor.execute(() -> {
            Bitmap decoded = decodeFile(store.imageFile(card.imageId));
            mainHandler.post(() -> {
                if (isFinishingOrDestroyed() || !targetUrl.equals(selectedUrl)) {
                    if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                    return;
                }
                if (decoded == null) {
                    frame.removeView(image);
                    TextView empty = text("图片暂时无法读取", 14, MUTED);
                    empty.setGravity(Gravity.CENTER);
                    frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
                } else image.setImageBitmap(decoded);
            });
        });
    }

    private Bitmap decodeUri(Uri uri) {
        try { return decodeSource(ImageDecoder.createSource(getContentResolver(), uri)); }
        catch (Exception ex) { return null; }
    }

    private Bitmap decodeFile(File file) {
        try { return decodeSource(ImageDecoder.createSource(file)); }
        catch (Exception ex) { return null; }
    }

    private Bitmap decodeSource(ImageDecoder.Source source) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            int max = Math.max(width, height);
            if (max > 2048) {
                decoder.setTargetSize(Math.max(1, width * 2048 / max), Math.max(1, height * 2048 / max));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private void renderSettings(LinearLayout body) {
        body.addView(appBar("设置", true), wrap());
        LinearLayout toggleRow = row();
        LinearLayout toggleCopy = column();
        toggleCopy.addView(text("使用自定义卡面", 16, INK), wrap());
        toggleCopy.addView(text(store.isEnabled() ? "重新进入钱包卡片页后使用替换图" : "重新进入钱包卡片页后恢复原图", 13, MUTED), margin(0, 3, 0, 0));
        toggleRow.addView(toggleCopy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        activeToggle = toggle;
        toggle.setChecked(store.isEnabled());
        toggle.setButtonTintList(android.content.res.ColorStateList.valueOf(INDIGO));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (adjustingSwitch) return;
            if (isSaveBusy()) {
                adjustingSwitch = true;
                button.setChecked(!checked);
                adjustingSwitch = false;
                Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            store.setEnabled(checked);
            ((TextView) toggleCopy.getChildAt(1)).setText(checked ? "重新进入钱包卡片页后使用替换图" : "重新进入钱包卡片页后恢复原图");
        });
        toggleRow.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(48)));
        body.addView(toggleRow, margin(0, 8, 0, 12));
        body.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));

        body.addView(sectionLabel("连接状态"), margin(0, 18, 0, 4));
        settingsRow(body, "兼容状态", compatibilityText());
        settingsRow(body, "钱包版本", store.walletVersion());
        settingsRow(body, "最近连接", formatTime(store.lastConnected()));
        settingsRow(body, "图片入口", String.valueOf(store.hookCount()));
        settingsRow(body, "替换命中（去重）", String.valueOf(store.replacements()));
        if (store.hookError() != null && !store.hookError().trim().isEmpty()) settingsRow(body, "Hook 错误", store.hookError());

        TextView detailsToggle = text("Hook 详情  ›", 14, INK);
        detailsToggle.setPadding(0, dp(8), 0, dp(8));
        TextView details = text(emptyAs(redact(store.hookDetails()), "暂无详情"), 13, MUTED);
        details.setVisibility(View.GONE);
        detailsToggle.setOnClickListener(v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            detailsToggle.setText(open ? "Hook 详情 ⌄" : "Hook 详情  ›");
        });
        body.addView(detailsToggle, margin(0, 6, 0, 0));
        body.addView(details, margin(0, 0, 0, 8));

        LinearLayout actions = row();
        Button copy = primaryButton("复制诊断");
        copy.setOnClickListener(v -> copyDiagnostics());
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button reload = subtleButton("重新载入");
        reload.setOnClickListener(v -> { refreshState(); Toast.makeText(this, "已载入本地状态", Toast.LENGTH_SHORT).show(); });
        actions.addView(reload, margin(10, 0, 0, 0, 0, dp(48)));
        body.addView(actions, margin(0, 12, 0, 0));

        body.addView(sectionLabel("使用帮助"), margin(0, 24, 0, 4));
        TextView helpToggle = text("首次使用说明  ›", 14, INK);
        helpToggle.setPadding(0, dp(8), 0, dp(8));
        TextView help = text("在 LSPosed 启用本模块，作用域选择钱包。打开钱包查看卡片后，返回选图。保存后重进卡片页生效；共用原图的卡片会一起变化。", 13, MUTED);
        help.setVisibility(View.GONE);
        helpToggle.setOnClickListener(v -> {
            boolean open = help.getVisibility() != View.VISIBLE;
            help.setVisibility(open ? View.VISIBLE : View.GONE);
            helpToggle.setText(open ? "首次使用说明 ⌄" : "首次使用说明  ›");
        });
        body.addView(helpToggle, wrap());
        body.addView(help, margin(0, 0, 0, 10));
        Button importLegacy = textAction("导入旧版交通模板");
        importLegacy.setOnClickListener(v -> importLegacyTemplates());
        body.addView(importLegacy, margin(-12, 0, 0, 0));

        body.addView(sectionLabel("关于"), margin(0, 18, 0, 4));
        body.addView(text(BuildConfig.VERSION_NAME + " · 免费开源，仅修改图片。", 14, MUTED), wrap());
        Button licenses = textAction("开源许可");
        licenses.setOnClickListener(v -> showLicenses());
        body.addView(licenses, margin(-12, 6, 0, 0));
    }

    private void importLegacyTemplates() {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLibrary library = frameLibrary;
        imageExecutor.execute(() -> {
            int count = 0;
            String error = null;
            try { count = library.importLegacyTemplates(); }
            catch (Exception ex) { error = ex.getMessage(); }
            final int imported = count;
            final String failure = error;
            mainHandler.post(() -> {
                if (isFinishingOrDestroyed()) return;
                if (failure != null && !failure.trim().isEmpty()) {
                    Toast.makeText(this, "导入失败：" + failure, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, imported > 0 ? "已导入旧版交通模板" : "旧版模板已是最新", Toast.LENGTH_SHORT).show();
                    if (editorMode) populateTemplateOptions();
                }
            });
        });
    }

    private void settingsRow(LinearLayout parent, String label, String value) {
        LinearLayout line = row();
        line.setPadding(0, dp(8), 0, dp(8));
        line.addView(text(label, 14, MUTED), new LinearLayout.LayoutParams(dp(112), -2));
        TextView right = text(value, 14, INK);
        right.setGravity(Gravity.END);
        right.setMaxLines(2);
        right.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(line, wrap());
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 12, MUTED);
        label.setLetterSpacing(.08f);
        return label;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        return line;
    }

    private String compatibilityText() {
        if (store.hookError() != null && !store.hookError().trim().isEmpty()) return "连接有误 · 请查看 Hook 错误";
        if (store.lastConnected() <= 0) return "等待钱包连接";
        return "图片组件已识别";
    }

    private void showLicenses() {
        StringBuilder contentText = new StringBuilder();
        try (InputStream input = getAssets().open("third_party_licenses.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) contentText.append(line).append('\n');
        } catch (Exception ex) {
            contentText.append("开源许可暂时无法读取。");
        }
        TextView contentView = text(contentText.toString(), 13, INK);
        contentView.setTextIsSelectable(true);
        contentView.setPadding(dp(4), dp(4), dp(4), dp(4));
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(20), dp(4), dp(20), 0);
        scroll.addView(contentView, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void copyDiagnostics() {
        String value = "CardStudio " + BuildConfig.VERSION_NAME + "\n"
                + "walletVersion=" + redact(store.walletVersion()) + "\n"
                + "hookCount=" + store.hookCount() + "\n"
                + "lastConnected=" + formatTime(store.lastConnected()) + "\n"
                + "lastReplaced=" + formatTime(store.lastReplaced()) + "\n"
                + "replacementHitsDedup=" + store.replacements() + "\n"
                + "hookError=" + redact(store.hookError()) + "\n"
                + "hookDetails=" + redact(store.hookDetails());
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("CardStudio 诊断", value));
        Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
    }

    private void openWallet() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.finshell.wallet");
        if (intent == null) {
            Toast.makeText(this, "未找到钱包应用，请确认已安装", Toast.LENGTH_SHORT).show();
            return;
        }
        try { startActivity(intent); }
        catch (Exception ex) { Toast.makeText(this, "无法打开钱包", Toast.LENGTH_SHORT).show(); }
    }

    private void confirmRestore(CardEntry card) {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复默认卡面？")
                .setMessage("将移除此卡面的替换设置，重进钱包卡片页生效。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", (dialog, which) -> {
                    if (isSaveBusy()) {
                        Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try { store.restore(card.url); refreshState(); }
                    catch (Exception ex) { Toast.makeText(this, "恢复失败，请重试", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void setOperationControlsEnabled(boolean enabled) {
        if (activeSaveButton != null) activeSaveButton.setEnabled(enabled && !logosLoading && !logosLoadFailed);
        if (activeTitleButton != null) activeTitleButton.setEnabled(enabled);
        if (activeRestoreButton != null) activeRestoreButton.setEnabled(enabled);
        if (activeChooseButton != null) activeChooseButton.setEnabled(enabled);
        if (activeToggle != null) activeToggle.setEnabled(enabled);
    }

    private boolean isSaveBusy() {
        return PROCESS_SAVE_BUSY.get();
    }

    private static void registerVisibleActivity(MainActivity activity) {
        synchronized (ACTIVE_ACTIVITY_LOCK) {
            activeActivity = new WeakReference<>(activity);
        }
    }

    private static void unregisterVisibleActivity(MainActivity activity) {
        synchronized (ACTIVE_ACTIVITY_LOCK) {
            MainActivity current = activeActivity.get();
            if (current == activity) activeActivity = new WeakReference<>(null);
        }
    }

    private static MainActivity visibleActivity() {
        synchronized (ACTIVE_ACTIVITY_LOCK) {
            return activeActivity.get();
        }
    }

    private static void notifySaveFinished(boolean success) {
        MainActivity activity = visibleActivity();
        if (activity == null || activity.mainHandler == null) return;
        activity.mainHandler.post(() -> activity.finishSaveUi(success));
    }

    private void finishSaveUi(boolean success) {
        MainActivity current = visibleActivity();
        if (current != null && current != this) {
            if (current.mainHandler != null) current.mainHandler.post(() -> current.finishSaveUi(success));
            return;
        }
        if (isFinishingOrDestroyed()) return;
        saving = false;
        savingSnapshot = null;
        savingBaseSnapshot = null;
        editorSaveVersion = SAVE_VERSION;
        if (success) {
            editorMode = false;
            editorTargetUrl = null;
        }
        Toast.makeText(this, success ? "卡面已保存，重新进入钱包卡片页生效" : "保存失败，请重试", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private CardEntry findCard(String url) {
        if (url == null) return null;
        for (CardEntry card : cards) if (url.equals(card.url)) return card;
        return null;
    }

    private boolean isFinishingOrDestroyed() {
        return isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed());
    }

    private String formatTime(long value) {
        return value <= 0 ? "尚无记录" : android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString();
    }

    private String formatClock(long value) {
        return value <= 0 ? "--:--" : android.text.format.DateFormat.format("HH:mm", value).toString();
    }

    private String emptyAs(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }

    private String redact(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        return value.replaceAll("https?://[^\\s]+", "[链接已隐藏]")
                .replaceAll("(?i)[a-f0-9]{8}-[a-f0-9-]{27,37}\\.jpg", "[图片标识已隐藏]")
                .replaceAll("(?i)[a-f0-9]{16,}", "[标识已隐藏]")
                .replaceAll("\\b\\d{8,}\\b", "[标识已隐藏]");
    }

    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(true); return view; }
    private Button primaryButton(String label) { return button(label, Color.WHITE, INK); }
    private Button subtleButton(String label) { return button(label, INK, 0xFFF0EFEB); }
    private Button textAction(String label) { Button b = button(label, MUTED, Color.TRANSPARENT); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); b.setPadding(dp(12), 0, dp(12), 0); return b; }
    private Button button(String label, int foreground, int background) { Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setTextColor(foreground); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setGravity(Gravity.CENTER); b.setStateListAnimator(null); b.setTranslationZ(0f); b.setElevation(0f); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(touchRound(background, 0x24000000, 12)); return b; }
    private GradientDrawable round(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable outlineRound(int color, int stroke, float radius) { GradientDrawable d = round(color, radius); d.setStroke(dp(1), stroke); return d; }
    private Drawable touchRound(int color, int ripple, float radius) { return new RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), round(color, radius), null); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams contentWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = wrap(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams contentMargin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = contentWrap(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b, int ignoredWeight, int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, height); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams fixedHeightMargin(int l, int t, int r, int b, int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private void handleBack() {
        if (isSaveBusy()) {
            Toast.makeText(this, "正在保存卡面，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editorMode) {
            cancelEditor();
            return;
        }
        if (page == 1) {
            editorGeneration++;
            page = 0;
            renderPage(0);
            return;
        }
        finish();
    }

    /** API 28-32 legacy path; API 33+ uses the registered gesture callback above. */
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    private static final class AspectFrameLayout extends FrameLayout {
        AspectFrameLayout(Context context) { super(context); setClipToOutline(true); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            int height = Math.max(1, Math.round(width * 0.625f));
            setMeasuredDimension(width, height);
            int childWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int childHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                measureChild(child, MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
            }
        }
    }

    private static final class StatusDot extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int color;
        StatusDot(Context context, int color) { super(context); this.color = color; }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            paint.setColor(color);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, paint);
        }
    }

    private static final class CardMarkView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        CardMarkView(Context context) { super(context); setContentDescription("卡片图示"); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float left = 3 * density;
            float top = 5 * density;
            float right = getWidth() - 3 * density;
            float bottom = getHeight() - 5 * density;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0xFF737B7D);
            canvas.drawRoundRect(left, top, right, bottom, 7 * density, 7 * density, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xFFB5BBB9);
            canvas.drawRect(left + 7 * density, top + 11 * density, right - 7 * density, top + 14 * density, paint);
            canvas.drawRect(left + 7 * density, bottom - 10 * density, left + 22 * density, bottom - 7 * density, paint);
        }
    }

    private static final class HeaderIconView extends View {
        static final int BACK = 1;
        static final int SLIDERS = 2;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        HeaderIconView(Context context, int kind) { super(context); this.kind = kind; }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            paint.setColor(INK);
            paint.setStrokeWidth(2 * d);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            if (kind == BACK) {
                canvas.drawLine(cx + 7 * d, cy, cx - 7 * d, cy, paint);
                canvas.drawLine(cx - 7 * d, cy, cx - 1 * d, cy - 6 * d, paint);
                canvas.drawLine(cx - 7 * d, cy, cx - 1 * d, cy + 6 * d, paint);
            } else {
                canvas.drawLine(cx - 10 * d, cy - 7 * d, cx + 10 * d, cy - 7 * d, paint);
                canvas.drawLine(cx - 10 * d, cy, cx + 10 * d, cy, paint);
                canvas.drawLine(cx - 10 * d, cy + 7 * d, cx + 10 * d, cy + 7 * d, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx - 2 * d, cy - 7 * d, 2.5f * d, paint);
                canvas.drawCircle(cx + 4 * d, cy, 2.5f * d, paint);
                canvas.drawCircle(cx - 5 * d, cy + 7 * d, 2.5f * d, paint);
            }
        }
    }

    @Override protected void onDestroy() {
        editorGeneration++;
        unregisterVisibleActivity(this);
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        // Let the single active save finish so its snapshot reaches its finally block.
        if (imageExecutor != null) imageExecutor.shutdown();
        if (selectedBitmap != null && !selectedBitmap.isRecycled()) selectedBitmap.recycle();
        savingSnapshot = null;
        savingBaseSnapshot = null;
        closeEditorLogos();
        super.onDestroy();
    }
}
