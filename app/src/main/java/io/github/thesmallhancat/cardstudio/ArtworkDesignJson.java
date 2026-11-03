package io.github.thesmallhancat.cardstudio;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Versioned, bounded local edit metadata; never sent to the wallet process. */
public final class ArtworkDesignJson {
    private static final Set<String> TEMPLATES = new HashSet<>(Arrays.asList(
            "none", "jingjinji", "shanghai", "qvanchengtong", "yinchuan"));
    private ArtworkDesignJson() { }

    public static String encode(ArtworkDesign value) {
        ArtworkDesign design = value == null ? new ArtworkDesign() : value.copy();
        design.clampAll();
        try {
            return new JSONObject().put("version", 1)
                    .put("template", TEMPLATES.contains(design.templateId) ? design.templateId : "none")
                    .put("customLogo", validLogo(design.customLogoId) ? design.customLogoId : "")
                    .put("primary", layer(design.primary)).put("union", layer(design.union))
                    .put("text", layer(design.text).put("title", design.text.title)
                            .put("subtitle", design.text.subtitle).put("color", design.text.color)).toString();
        } catch (JSONException ex) { throw new IllegalArgumentException("图层参数无法保存", ex); }
    }

    public static ArtworkDesign decode(String value) {
        ArtworkDesign design = new ArtworkDesign();
        if (value == null || value.isEmpty() || value.length() > 8192) return design;
        try {
            JSONObject json = new JSONObject(value);
            if (json.optInt("version") != 1) return design;
            String template = json.optString("template", "none");
            design.templateId = TEMPLATES.contains(template) ? template : "none";
            String custom = json.optString("customLogo", "");
            design.customLogoId = validLogo(custom) ? custom : "";
            readLayer(json.optJSONObject("primary"), design.primary);
            readLayer(json.optJSONObject("union"), design.union);
            JSONObject text = json.optJSONObject("text");
            readLayer(text, design.text);
            if (text != null) {
                design.text.title = text.optString("title", "");
                design.text.subtitle = text.optString("subtitle", "");
                design.text.color = text.optInt("color", 0xff202124) | 0xff000000;
            }
            design.clampAll();
        } catch (JSONException ignored) { return new ArtworkDesign(); }
        return design;
    }
    private static JSONObject layer(ArtworkDesign.Layer value) throws JSONException {
        return new JSONObject().put("enabled", value.enabled).put("x", value.x).put("y", value.y)
                .put("width", value.width).put("opacity", value.opacity);
    }
    private static void readLayer(JSONObject source, ArtworkDesign.Layer target) {
        if (source == null) return;
        target.enabled = source.optBoolean("enabled", false);
        target.x = (float) source.optDouble("x", target.x);
        target.y = (float) source.optDouble("y", target.y);
        target.width = (float) source.optDouble("width", target.width);
        target.opacity = (float) source.optDouble("opacity", target.opacity);
    }
    private static boolean validLogo(String id) {
        return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png");
    }
}
