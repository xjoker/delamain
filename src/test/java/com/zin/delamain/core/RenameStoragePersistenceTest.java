package com.zin.delamain.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence / recovery coverage for {@link RenameStorage}. This is the only place a
 * delamain user's own hand-typed data lives (class / method / field aliases from rename
 * operations) -- unlike CodeStore / UsageGraphStore / ContentShard, which are all
 * regenerable caches, a lost {@code renames.json} cannot be rebuilt from the APK.
 *
 * <p>Uses the string-key overloads ({@link RenameStorage#renameClass(String, String)} etc.)
 * so no live JADX {@code JavaClass}/{@code JavaMethod}/{@code JavaField} nodes are needed --
 * those overloads exist specifically for session restore and exercise the same
 * save/load path as the live-node overloads.</p>
 *
 * <p>F8 (2): a corrupt {@code renames.json} used to be silently discarded (a WARN log line)
 * and then permanently destroyed by the very next save. These tests now pin the fixed,
 * safe behavior: the corrupt file is quarantined (moved aside, never overwritten), the
 * storage surfaces a degraded flag instead of failing silently, and a failed save rolls the
 * in-memory map back instead of leaving memory/disk diverging.</p>
 */
class RenameStoragePersistenceTest {

    @Test
    void allThreeRenameKindsRoundTripAcrossNewInstance(@TempDir Path dir) throws IOException {
        RenameStorage storage = new RenameStorage(dir);
        storage.renameClass("com.example.a", "com.example.LoginActivity");
        storage.renameMethod("com.example.a#a()V", "doLogin");
        storage.renameField("com.example.a#b:Ljava/lang/String;", "userId");

        // Fresh instance over the same directory -- simulates process restart / session reload.
        RenameStorage reloaded = new RenameStorage(dir);
        assertEquals("com.example.LoginActivity", reloaded.getClassAlias("com.example.a"));
        assertEquals("doLogin", reloaded.getMethodAlias("com.example.a#a()V"));
        assertEquals("userId", reloaded.getFieldAlias("com.example.a#b:Ljava/lang/String;"));
        assertEquals(3, reloaded.size());
        assertFalse(reloaded.isDegraded());
    }

    @Test
    void truncatedJsonFile_isQuarantinedAndFlaggedDegraded_notSilentlyDiscarded(@TempDir Path dir) throws IOException {
        Path renamesFile = dir.resolve("renames.json");
        String corruptContent = "{\"classes\":{\"com.example.a\":\"Log";
        Files.writeString(renamesFile, corruptContent, StandardCharsets.UTF_8);

        RenameStorage storage = new RenameStorage(dir);

        // Fixed behavior: the session still starts empty (an unparseable file can't be recovered
        // in-process), but this is no longer silent -- isDegraded()/getDegradedReason() surface it.
        assertNull(storage.getClassAlias("com.example.a"));
        assertEquals(0, storage.size());
        assertTrue(storage.isDegraded(), "a corrupt renames.json must flip the degraded flag");
        assertNotNull(storage.getDegradedReason());

        // The original file must have been preserved (moved aside), not left where the next save
        // would destroy it -- find the quarantine copy and check its content is untouched.
        Path quarantined = findQuarantineFile(dir);
        assertEquals(corruptContent, Files.readString(quarantined, StandardCharsets.UTF_8),
                "the original corrupt content must be preserved byte-for-byte in the quarantine copy");
        assertFalse(Files.exists(renamesFile), "the original path must no longer hold the corrupt content");
    }

    @Test
    void invalidJsonFile_isQuarantinedAndFlaggedDegraded_notSilentlyDiscarded(@TempDir Path dir) throws IOException {
        Path renamesFile = dir.resolve("renames.json");
        Files.writeString(renamesFile, "not json at all { [ }", StandardCharsets.UTF_8);

        RenameStorage storage = new RenameStorage(dir);

        assertEquals(0, storage.size());
        assertTrue(storage.isDegraded());
        assertNotNull(storage.getDegradedReason());
        assertNotNull(findQuarantineFile(dir), "invalid JSON must also be quarantined, not just truncated JSON");
    }

    @Test
    void corruptedFileIsPreserved_notOverwrittenByNextSave(@TempDir Path dir) throws IOException {
        Path renamesFile = dir.resolve("renames.json");
        // Simulate a file that was corrupted *after* the user had already accumulated renames
        // (e.g. disk full / crash mid-write on a previous run).
        String corruptContent = "{corrupt";
        Files.writeString(renamesFile, corruptContent, StandardCharsets.UTF_8);

        RenameStorage storage = new RenameStorage(dir);
        assertTrue(storage.isDegraded());

        // Fixed behavior: loadFromDisk() already moved the corrupt file aside before this rename
        // is even made, so the very next atomic save creates a brand-new renames.json and the
        // quarantined copy is untouched -- the pre-existing (corrupt) content is recoverable by
        // hand, not silently destroyed.
        storage.renameClass("com.example.b", "com.example.PaymentActivity");

        Path quarantined = findQuarantineFile(dir);
        assertEquals(corruptContent, Files.readString(quarantined, StandardCharsets.UTF_8),
                "quarantined content must survive the subsequent save untouched");

        String onDiskAfterSave = Files.readString(renamesFile, StandardCharsets.UTF_8);
        assertTrue(onDiskAfterSave.contains("PaymentActivity"));
        assertEquals(1, new RenameStorage(dir).size(),
                "the rename made after quarantining persists normally; the quarantined file is a "
                        + "separate, untouched artifact for manual recovery, not merged back in");
    }

    @Test
    void saveFailureRollsBackInMemoryMapEntry(@TempDir Path dir) throws IOException {
        RenameStorage storage = new RenameStorage(dir);
        storage.renameClass("com.example.a", "com.example.Alpha");
        assertEquals("com.example.Alpha", storage.getClassAlias("com.example.a"));

        // Make the directory read-only so the next saveToDisk()'s tmp-file write fails -- this is
        // the scenario where, before the fix, the in-memory map already held the new value while
        // disk still held the old one (or nothing), a three-way divergence with the live JADX tree.
        assertTrue(dir.toFile().setWritable(false), "test setup: must be able to make dir read-only");
        try {
            assertThrows(IOException.class, () -> storage.renameClass("com.example.b", "com.example.Beta"),
                    "a save failure must still surface as an IOException to the caller");
        } finally {
            dir.toFile().setWritable(true); // restore so @TempDir cleanup can delete the directory
        }

        assertNull(storage.getClassAlias("com.example.b"),
                "the map entry for the failed rename must be rolled back, not left dangling with no "
                        + "matching disk state");
        assertEquals("com.example.Alpha", storage.getClassAlias("com.example.a"),
                "pre-existing entries must be untouched by a later failed save");
    }

    private static Path findQuarantineFile(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("renames.json.corrupt-"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
