package io.github.thesmallhancat.cardstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkDesignJsonTest {
    @Test public void savesIndependentLayersForReediting() {
        ArtworkDesign original = new ArtworkDesign();
        original.templateId = "jingjinji";
        original.primary.enabled = true;
        original.primary.x = .21f;
        original.union.enabled = false;
        original.text.enabled = true;
        original.text.title = "自定义交通卡";
        original.text.subtitle = "CITY PASS";
        original.text.opacity = .7f;
        ArtworkDesign restored = ArtworkDesignJson.decode(ArtworkDesignJson.encode(original));
        assertEquals("jingjinji", restored.templateId);
        assertTrue(restored.primary.enabled);
        assertEquals(.21f, restored.primary.x, .0001f);
        assertFalse(restored.union.enabled);
        assertEquals("自定义交通卡", restored.text.title);
        assertEquals("CITY PASS", restored.text.subtitle);
        assertEquals(.7f, restored.text.opacity, .0001f);
    }
    @Test public void untrustedMetadataCannotSelectArbitraryFilesOrInvalidGeometry() {
        ArtworkDesign design = ArtworkDesignJson.decode("{\"version\":1,\"template\":\"../../private\","
                + "\"customLogo\":\"../image.png\",\"primary\":{\"enabled\":true,\"x\":12,\"width\":5,\"opacity\":-9}} ");
        assertEquals("none", design.templateId);
        assertEquals("", design.customLogoId);
        assertEquals(.8f, design.primary.width, .0001f);
        assertEquals(.2f, design.primary.x, .0001f);
        assertEquals(0f, design.primary.opacity, 0f);
    }
    @Test public void malformedOrFutureMetadataLeavesPlainArtwork() {
        assertFalse(ArtworkDesignJson.decode("broken").primary.enabled);
        assertFalse(ArtworkDesignJson.decode("{\"version\":99,\"primary\":{\"enabled\":true}}").primary.enabled);
        assertEquals("", ArtworkDesignJson.decode(null).text.title);
    }
}
