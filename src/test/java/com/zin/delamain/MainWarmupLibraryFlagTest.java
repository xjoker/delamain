package com.zin.delamain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth table for {@link Main#resolveSkipLibraries(String)}, the helper backing the
 * DELAMAIN_WARMUP_INDEX_LIBRARIES opt-in flag consumed by Main's auto-warmup trigger.
 *
 * Default (unset) must keep today's behavior: skipLibraries=true (libraries excluded from
 * the CodeStore/shard index, same as before this flag existed).
 */
class MainWarmupLibraryFlagTest {

    @Test
    void unsetEnvDefaultsToSkippingLibraries() {
        assertTrue(Main.resolveSkipLibraries(null));
    }

    @Test
    void trueEnvDisablesSkipping() {
        assertFalse(Main.resolveSkipLibraries("true"));
    }

    @Test
    void trueEnvIsCaseInsensitive() {
        assertFalse(Main.resolveSkipLibraries("TRUE"));
    }

    @Test
    void falseEnvKeepsSkipping() {
        assertTrue(Main.resolveSkipLibraries("false"));
    }

    @Test
    void arbitraryEnvValueKeepsSkipping() {
        assertTrue(Main.resolveSkipLibraries("yes"));
    }
}
