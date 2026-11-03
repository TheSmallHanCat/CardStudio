package io.github.thesmallhancat.cardstudio;

import java.net.URI;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Only known wallet artwork sources are accepted. NFC credentials are never read. */
public final class SourcePolicy {
    private static final Set<String> HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "fast-wallet.finz.oppomobile.com", "fast-wallet.finzfin.com")));
    private SourcePolicy() {}

    public static boolean isCardArtwork(String value) {
        if (value == null || value.length() > 2048) return false;
        if (isLocalArtwork(value)) return true;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getFragment() == null && uri.getPath() != null
                    && uri.getPath().startsWith("/file/nfc/")
                    && !uri.getPath().contains("..") && uri.getQuery() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static boolean isLocalArtwork(String value) {
        if (value == null || value.length() > 2048 || value.indexOf('\0') >= 0) return false;
        String path = value;
        if (value.startsWith("file://")) {
            try {
                URI uri = URI.create(value);
                if (uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null) return false;
                path = uri.getPath();
            } catch (IllegalArgumentException ex) { return false; }
        }
        if (path == null || path.contains("..") || path.contains("?") || path.contains("#")) return false;
        return path.matches("^/data/(?:user/[0-9]+|data)/com\\.finshell\\.wallet/(?:files|cache)/.+(?i:\\.(?:png|jpe?g|webp))$");
    }

    public static boolean isImageId(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg");
    }
}
