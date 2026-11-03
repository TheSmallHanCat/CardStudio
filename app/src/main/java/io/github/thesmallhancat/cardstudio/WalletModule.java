package io.github.thesmallhancat.cardstudio;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Independent API 101 module. Its entire scope is wallet artwork presentation. */
public final class WalletModule extends XposedModule {
    private String process = "";
    private boolean installed;
    private WalletBridge bridge;

    @Override public void onModuleLoaded(ModuleLoadedParam param) { process = param.getProcessName(); }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!"com.finshell.wallet".equals(param.getPackageName()) || installed
                || !(process.equals("com.finshell.wallet") || process.equals("com.finshell.wallet:nfchost"))) return;
        installed = true;
        bridge = new WalletBridge((message, ex) -> log(Log.WARN, "CardStudio", message, ex));
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                bridge.connect((Context) chain.getArg(0));
                return result;
            });
        } catch (Throwable ex) { log(Log.WARN, "CardStudio", "Application context hook unavailable", ex); }

        try {
            Class<?> widget = Class.forName(HookSelector.WIDGET, false, param.getClassLoader());
            List<Method> methods = new ArrayList<>();
            for (Method method : widget.getDeclaredMethods()) if (HookSelector.accepts(method)) methods.add(method);
            methods.sort(Comparator.comparing(Method::toString));
            if (methods.isEmpty() || methods.size() > 64) throw new IllegalStateException("没有可靠的图片加载入口");
            List<String> hooked = new ArrayList<>();
            for (Method method : methods) {
                try {
                    hook(method).intercept(chain -> {
                        String target = null;
                        try {
                            boolean cardSurface = false;
                            View image = null;
                            if (chain.getThisObject() instanceof View view) {
                                image = view;
                                bridge.connect(view.getContext());
                                if (view.getId() != View.NO_ID && view.getId() != 0) {
                                    try {
                                        String name = view.getResources().getResourceEntryName(view.getId()).toLowerCase(Locale.ROOT);
                                        cardSurface = name.contains("nfc") || name.contains("card_face") || name.contains("entrance");
                                    } catch (android.content.res.Resources.NotFoundException ignored) { }
                                }
                            }
                            if (chain.getArg(0) instanceof String source) target = bridge.replacement(source, cardSurface, image);
                        } catch (Throwable ex) { log(Log.WARN, "CardStudio", "Image rewrite skipped", ex); }
                        if (target == null) return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        args[0] = target;
                        return chain.proceed(args);
                    });
                    hooked.add(method.getName() + " / " + method.getParameterCount() + " 参数");
                } catch (Throwable ex) { log(Log.WARN, "CardStudio", "Image entry skipped: " + method.getName(), ex); }
            }
            String error = hooked.isEmpty() ? "当前钱包尚未适配：图片入口均未加载" : "";
            bridge.profile(hooked.size(), String.join("\n", hooked), error);
            log(Log.INFO, "CardStudio", "Image hooks installed: " + hooked.size() + "; process=" + process);
        } catch (Throwable ex) {
            bridge.profile(0, "", "当前钱包图片组件不兼容，已保留原卡面");
            log(Log.ERROR, "CardStudio", "Wallet artwork hook unavailable", ex);
        }
    }
}
