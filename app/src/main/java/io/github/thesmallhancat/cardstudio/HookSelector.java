package io.github.thesmallhancat.cardstudio;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Match the widget's image entry points by shape, not an obfuscated method name. */
public final class HookSelector {
    public static final String WIDGET = "com.finshell.finui.widget.imageview.CircleNetworkImageView";
    private HookSelector() {}

    public static boolean accepts(Method method) {
        if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) return false;
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length == 0 || parameters[0] != String.class) return false;
        Class<?> result = method.getReturnType();
        return result == void.class || result.getName().startsWith("com.bumptech.glide.");
    }
}
