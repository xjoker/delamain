package com.zin.delamain.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corruption coverage for {@link CodeStore}'s per-class CRC manifest, mirroring
 * {@code ContentShardRobustnessTest}'s style: a bit-flipped class file must fail closed (return
 * {@code null}, never wrong/garbage source) without touching other classes, and pre-existing
 * stores that predate the CRC manifest (no sidecar file) must keep serving reads unchanged
 * (grandfather).
 */
class CodeStoreRobustnessTest {

    private static final String HASH = "aabbccddeeff0011";

    /** Mirrors {@code CodeStore}'s private {@code pathFor} so tests can locate a class's on-disk file. */
    private static Path pathFor(Path indexDir, String className) {
        String h = sha256Hex(className);
        return indexDir.resolve("code").resolve(HASH.substring(0, 8))
                .resolve(h.substring(0, 2)).resolve(h + ".gz");
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void flipByte(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] = (byte) ~bytes[offset];
        Files.write(file, bytes);
    }

    /**
     * Overwrites a class's on-disk file with a well-formed gzip stream of unrelated content. This
     * decodes cleanly (so gzip's own built-in trailer CRC can't catch it) but no longer matches
     * the per-class CRC recorded in the manifest at {@code markComplete()} time -- the exact
     * corruption case the manifest exists to catch (e.g. a mixed-up/substituted file), as opposed
     * to a bit-flip that gzip's own decode-time integrity check already rejects.
     */
    private static void writeUnrelatedButValidGzip(Path file, String content) throws IOException {
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void freshStoreRoundTripsSourceExactly(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        store.put("a.B", "class B { void x() {} }");

        assertEquals("class B { void x() {} }", store.get("a.B"));
    }

    @Test
    void tamperedButValidGzipFailsClosedOthersUnaffected(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        store.put("a.B", "class B { void x() {} }");
        store.put("a.C", "class C { void y() {} }");
        store.markComplete(2);

        // Simulate a restart: reload the store so the CRC manifest is read back from disk.
        CodeStore reloaded = new CodeStore(dir, HASH);

        Path corrupted = pathFor(dir, "a.C");
        assertTrue(Files.exists(corrupted), "expected on-disk file for a.C");
        writeUnrelatedButValidGzip(corrupted, "tampered content, not class C's real source");

        assertNull(reloaded.get("a.C"), "CRC-mismatched class source must fail closed, not return wrong content");
        assertEquals("class B { void x() {} }", reloaded.get("a.B"),
                "an unrelated class's source must be unaffected by another class's corruption");
    }

    @Test
    void byteFlipBreakingGzipDecodeReturnsNullNotException(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        store.put("a.B", "class B { void x() {} }");
        store.markComplete(1);

        CodeStore reloaded = new CodeStore(dir, HASH);
        Path p = pathFor(dir, "a.B");
        byte[] bytes = Files.readAllBytes(p);
        flipByte(p, bytes.length - 3); // deep in the deflate stream / gzip trailer

        assertNull(reloaded.get("a.B"), "a corrupted gzip stream must fail closed, not throw");
    }

    @Test
    void legacyStoreWithoutCrcManifestStillServesReads(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        store.put("a.B", "class B { void x() {} }");
        // No markComplete() call: simulates a pre-CRC store where the manifest sidecar never existed.

        CodeStore reloaded = new CodeStore(dir, HASH);
        assertEquals("class B { void x() {} }", reloaded.get("a.B"),
                "reads against a store with no CRC manifest (grandfathered) must still succeed");
    }

    @Test
    void completeMarkerSemanticsUnchanged(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        assertFalse(store.isComplete());

        store.put("a.B", "class B { void x() {} }");
        store.markComplete(1);

        assertTrue(store.isComplete());
        // Reload must still see the marker.
        CodeStore reloaded = new CodeStore(dir, HASH);
        assertTrue(reloaded.isComplete());
    }
}
