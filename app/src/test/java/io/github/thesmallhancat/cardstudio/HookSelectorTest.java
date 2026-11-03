package io.github.thesmallhancat.cardstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class HookSelectorTest {
    private static final class ImageWidget {
        public void renamed(String url, int placeholder) {}
        public boolean validate(String url) { return true; }
        public static void helper(String url) {}
        public void unrelated(int code, String value) {}
    }
    @Test public void acceptsRenamedImageSetterWithoutPinningObfuscatedName() throws Exception {
        assertTrue(HookSelector.accepts(ImageWidget.class.getDeclaredMethod("renamed", String.class, int.class)));
    }
    @Test public void excludesValidatorsStaticHelpersAndOtherParameters() throws Exception {
        assertFalse(HookSelector.accepts(ImageWidget.class.getDeclaredMethod("validate", String.class)));
        assertFalse(HookSelector.accepts(ImageWidget.class.getDeclaredMethod("helper", String.class)));
        assertFalse(HookSelector.accepts(ImageWidget.class.getDeclaredMethod("unrelated", int.class, String.class)));
    }
}
