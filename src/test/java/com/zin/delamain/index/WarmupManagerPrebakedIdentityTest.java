package com.zin.delamain.index;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * req4 (FAST_RESTORE identity cross-check): the pure decision gate
 * {@link WarmupManager#prebakedIdentityAllowsRestore} must reject a restore only when the on-disk
 * prebaked manifest carries identity metadata that CONFLICTS with the loaded APK, and must stay
 * backward compatible with existing production index volumes whose manifests predate identity
 * metadata (no apk_package/version_* fields) — those must never be forced into a cold rebuild.
 *
 * <p>These assertions define the behaviour of the gate before it exists (test-first). The gate is
 * pure (Map + current identity in, boolean out) so it needs no real jadx/APK.</p>
 */
class WarmupManagerPrebakedIdentityTest {

    private static Map<String, Object> manifest(Object pkg, Object vName, Object vCode) {
        Map<String, Object> m = new HashMap<>();
        if (pkg != null) m.put("apk_package", pkg);
        if (vName != null) m.put("version_name", vName);
        if (vCode != null) m.put("version_code", vCode);
        return m;
    }

    @Test
    void allowsRestoreWhenStoredIdentityMatchesLoadedApk() {
        Map<String, Object> m = manifest("owasp.mstg.uncrackable1", "1.0", 1);
        assertTrue(WarmupManager.prebakedIdentityAllowsRestore(m, "owasp.mstg.uncrackable1", "1.0", 1),
                "matching package/version must allow FAST_RESTORE");
    }

    @Test
    void rejectsRestoreWhenStoredPackageConflictsWithLoadedApk() {
        // Same input-hash (64 KB-prefix collision) but the stored manifest is a different APK.
        Map<String, Object> m = manifest("owasp.mstg.uncrackable2", "1.0", 1);
        assertFalse(WarmupManager.prebakedIdentityAllowsRestore(m, "owasp.mstg.uncrackable1", "1.0", 1),
                "a stored package that differs from the loaded APK must reject FAST_RESTORE");
    }

    @Test
    void rejectsRestoreWhenStoredVersionCodeConflicts() {
        Map<String, Object> m = manifest("owasp.mstg.uncrackable1", "1.0", 2);
        assertFalse(WarmupManager.prebakedIdentityAllowsRestore(m, "owasp.mstg.uncrackable1", "1.0", 1),
                "a stored version_code that differs must reject FAST_RESTORE");
    }

    @Test
    void versionCodeComparesNumericallyAcrossIntegerAndDouble() {
        // Gson parses JSON numbers back as Double; the manually-built current value is an Integer.
        // 1.0 (Double) and 1 (Integer) must be treated as equal, not a spurious conflict.
        Map<String, Object> m = manifest("owasp.mstg.uncrackable1", "1.0", Double.valueOf(1.0));
        assertTrue(WarmupManager.prebakedIdentityAllowsRestore(m, "owasp.mstg.uncrackable1", "1.0", 1),
                "Double(1.0) from a parsed manifest must equal Integer(1) from the live identity");
    }

    @Test
    void allowsRestoreForOldFormatManifestWithNoIdentityMetadata() {
        // Existing production volumes (e.g. 91dde845 / f0acdb35) have no apk_package/version fields.
        Map<String, Object> old = manifest(null, null, null);
        assertTrue(WarmupManager.prebakedIdentityAllowsRestore(old, "owasp.mstg.uncrackable1", "1.0", 1),
                "an old-format manifest without identity metadata must still allow FAST_RESTORE");
    }

    @Test
    void allowsRestoreWhenManifestIsNull() {
        assertTrue(WarmupManager.prebakedIdentityAllowsRestore(null, "owasp.mstg.uncrackable1", "1.0", 1),
                "an absent manifest must allow FAST_RESTORE by input-hash match");
    }

    @Test
    void doesNotRejectWhenCurrentIdentityIsUnavailable() {
        // A flaky current-side manifest parse (null package) must never trigger a false cold rebuild:
        // we cannot prove a conflict, so allow the restore.
        Map<String, Object> m = manifest("owasp.mstg.uncrackable1", "1.0", 1);
        assertTrue(WarmupManager.prebakedIdentityAllowsRestore(m, null, null, null),
                "null current identity cannot prove a conflict and must not reject FAST_RESTORE");
    }
}
