package io.github.thesmallhancat.cardstudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourcePolicyTest {
    private static final String ART = "https://fast-wallet.finz.oppomobile.com/file/nfc/20251014/card.jpg.-o.webp";
    @Test public void permitsActualArtworkDomains() {
        assertTrue(SourcePolicy.isCardArtwork(ART));
        assertTrue(SourcePolicy.isCardArtwork(ART.replace("finz.oppomobile.com", "finzfin.com")));
    }
    @Test public void doesNotCaptureAdsAccountsOrOtherImages() {
        assertFalse(SourcePolicy.isCardArtwork(ART.replace("/nfc/", "/wallet/")));
        assertFalse(SourcePolicy.isCardArtwork(ART.replace("https:", "http:")));
        assertFalse(SourcePolicy.isCardArtwork(ART + "?token=private"));
        assertFalse(SourcePolicy.isCardArtwork(ART.replace("card.jpg", "../account")));
    }
    @Test public void rejectsHostSpoofingAndLocalUris() {
        assertFalse(SourcePolicy.isCardArtwork(ART.replace("oppomobile.com/", "oppomobile.com.attacker.test/")));
        assertFalse(SourcePolicy.isCardArtwork("https://attacker@fast-wallet.finzfin.com/file/nfc/a.jpg"));
        assertFalse(SourcePolicy.isCardArtwork("content://io.github.thesmallhancat.cardstudio.bridge/images/a.jpg"));
        assertFalse(SourcePolicy.isCardArtwork(null));
        assertFalse(SourcePolicy.isCardArtwork("not a url"));
    }
    @Test public void imageIdsCannotEscapeArtworkDirectory() {
        assertTrue(SourcePolicy.isImageId("c9b10a8a-b6bf-4610-b7d3-ff3ba6510f0d.jpg"));
        assertFalse(SourcePolicy.isImageId("../studio.xml"));
        assertFalse(SourcePolicy.isImageId("/data/user/0/secrets.jpg"));
        assertFalse(SourcePolicy.isImageId("------------------------------------.jpg"));
    }
    @Test public void permitsOnlyWalletLocalArtworkPaths() {
        assertTrue(SourcePolicy.isCardArtwork("/data/user/0/com.finshell.wallet/files/images/custom.png"));
        assertTrue(SourcePolicy.isCardArtwork("file:///data/data/com.finshell.wallet/files/card.webp"));
        assertFalse(SourcePolicy.isCardArtwork("/data/user/0/com.finshell.wallet/shared_prefs/account.xml"));
        assertFalse(SourcePolicy.isCardArtwork("/data/user/0/com.finshell.wallet/files/../shared_prefs/a.png"));
        assertFalse(SourcePolicy.isCardArtwork("/data/user/0/com.other.app/files/photo.png"));
        assertFalse(SourcePolicy.isCardArtwork("file://attacker/data/data/com.finshell.wallet/files/a.png"));
    }
}
