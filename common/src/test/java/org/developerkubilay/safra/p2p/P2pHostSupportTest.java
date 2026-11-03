package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2pHostSupportTest {

    @Test
    void formatsWorldCodeWithNullName() {
        long seed = 123456789L;
        String expectedId = String.format(Locale.ROOT, "%06x", Long.hashCode(seed) & 0xFFFFFF);
        assertEquals("World [" + expectedId + "]", P2pHostSupport.formatWorldCode(null, seed));
    }

    @Test
    void formatsWorldCodeWithEmptyAndWhitespaceName() {
        long seed = -987654321L;
        String expectedId = String.format(Locale.ROOT, "%06x", Long.hashCode(seed) & 0xFFFFFF);
        assertEquals("World [" + expectedId + "]", P2pHostSupport.formatWorldCode("", seed));
        assertEquals("World [" + expectedId + "]", P2pHostSupport.formatWorldCode("   ", seed));
    }

    @Test
    void stripsForbiddenCharactersFromWorldName() {
        long seed = 42L;
        String expectedId = String.format(Locale.ROOT, "%06x", Long.hashCode(seed) & 0xFFFFFF);
        assertEquals("My World [" + expectedId + "]", P2pHostSupport.formatWorldCode("My/\\:*?\"<>| World", seed));
        assertEquals("World [" + expectedId + "]", P2pHostSupport.formatWorldCode("/\\:*?\"<>|", seed));
    }

    @Test
    void truncatesWorldNamesExceedingThirtyCharacters() {
        long seed = 100L;
        String longName = "ThisIsAVeryLongWorldNameThatDefinitelyExceedsThirtyCharacters";
        String expectedPrefix = longName.substring(0, 30).trim();
        String expectedId = String.format(Locale.ROOT, "%06x", Long.hashCode(seed) & 0xFFFFFF);
        assertEquals(expectedPrefix + " [" + expectedId + "]", P2pHostSupport.formatWorldCode(longName, seed));
    }

    @Test
    void formatsNormalWorldNameDeterministically() {
        long seed = 0xABCDEF123456L;
        String expectedId = String.format(Locale.ROOT, "%06x", Long.hashCode(seed) & 0xFFFFFF);
        assertEquals("Survival 1.16.5 [" + expectedId + "]", P2pHostSupport.formatWorldCode("Survival 1.16.5", seed));
    }

    @Test
    void resolvesPreferredRendezvousCode() {
        assertEquals("ABCDEFGH2345", P2pHostSupport.resolvePreferredRendezvousCode("abcdefgh-2345"));
        String generated = P2pHostSupport.resolvePreferredRendezvousCode(null);
        assertNotNull(generated);
        assertEquals(P2pShareCode.DEFAULT_RENDEZVOUS_CODE_LENGTH, generated.length());
        assertTrue(generated.matches("[A-HJ-NP-Z2-9]+"));
    }
}
