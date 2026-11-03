package io.github.thesmallhancat.cardstudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ArtworkDesignTest {
    @Test public void clampsInvalidGeometryAndOpacity() {
        ArtworkDesign design = new ArtworkDesign();
        design.primary.x = Float.NaN;
        design.primary.y = Float.POSITIVE_INFINITY;
        design.primary.width = Float.NEGATIVE_INFINITY;
        design.primary.opacity = Float.NaN;
        design.union.width = 2f;
        design.union.opacity = -1f;
        design.text.x = -1f;
        design.text.width = 0f;
        design.text.opacity = Float.POSITIVE_INFINITY;
        design.clampAll();

        assertEquals(0f, design.primary.x, 0f);
        assertEquals(0f, design.primary.y, 0f);
        assertEquals(ArtworkDesign.MIN_LOGO_WIDTH, design.primary.width, 0f);
        assertEquals(0f, design.primary.opacity, 0f);
        assertEquals(ArtworkDesign.MAX_LOGO_WIDTH, design.union.width, 0f);
        assertEquals(0f, design.union.opacity, 0f);
        assertEquals(0f, design.text.x, 0f);
        assertEquals(ArtworkDesign.MIN_TEXT_WIDTH, design.text.width, 0f);
        assertEquals(0f, design.text.opacity, 0f);
    }

    @Test public void sanitizesControlCharactersAndTruncatesCodePoints() {
        String input = "A\n\t\u0000😀😀😀😀😀";
        String result = ArtworkDesign.sanitizeLine(input, 3);

        assertEquals("A😀😀", result);
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\t"));
        assertEquals(3, result.codePointCount(0, result.length()));
        assertTrue(Character.isHighSurrogate(result.charAt(1)));
        assertTrue(Character.isLowSurrogate(result.charAt(2)));
    }

    @Test public void copyIsIndependent() {
        ArtworkDesign original = new ArtworkDesign();
        original.primary.enabled = true;
        original.text.title = "原始";
        original.templateId = "city";

        ArtworkDesign copy = original.copy();
        assertNotSame(original, copy);
        assertNotSame(original.primary, copy.primary);
        assertNotSame(original.text, copy.text);

        copy.primary.x = 0.9f;
        copy.text.title = "副本";
        copy.templateId = "other";

        assertEquals(0.055f, original.primary.x, 0f);
        assertEquals("原始", original.text.title);
        assertEquals("city", original.templateId);
    }
}
