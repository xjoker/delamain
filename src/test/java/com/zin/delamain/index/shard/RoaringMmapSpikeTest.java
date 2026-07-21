package com.zin.delamain.index.shard;

import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 0 gate spike — validates the three RoaringBitmap assumptions the whole shard
 * subsystem rests on. If any of these fail, the mmap + zero-copy design does not hold
 * and the shard classes must not be built on top of it.
 *
 * <ol>
 *   <li>{@code new ImmutableRoaringBitmap(mappedByteBuffer)} is genuinely zero-copy —
 *       constructing it over an mmap'd file does NOT pull the whole serialized payload
 *       into the JVM heap (verified by heap accounting against a full heap-resident read).</li>
 *   <li>Serialization round-trips: bytes written by the build-side {@code RoaringBitmap}
 *       (non-buffer package) deserialize identically through both the query-side
 *       {@code ImmutableRoaringBitmap} (buffer package) and {@code RoaringBitmap.deserialize}.</li>
 *   <li>{@code serializedSizeInBytes()} exactly predicts the bytes written, and
 *       {@code getSizeInBytes()} is a usable same-order-of-magnitude budget proxy.</li>
 * </ol>
 */
class RoaringMmapSpikeTest {

    /** Reads used heap after several GC hints (coarse but sufficient with generous thresholds). */
    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        long used = Long.MAX_VALUE;
        // A few GC passes to settle; take the minimum observed as the steady-state estimate.
        for (int i = 0; i < 6; i++) {
            System.gc();
            System.runFinalization();
            used = Math.min(used, rt.totalMemory() - rt.freeMemory());
        }
        return used;
    }

    @Test
    void immutableRoaringBitmapOverMmapDoesNotCopyPayloadIntoHeap() throws IOException {
        // Build a poorly-compressible bitmap so the serialized payload is several MB —
        // large enough that a full heap-resident copy dwarfs GC measurement noise.
        RoaringBitmap rb = new RoaringBitmap();
        Random rnd = new Random(1234567L);
        for (int i = 0; i < 4_000_000; i++) {
            rb.add(rnd.nextInt(200_000_000));
        }
        rb.runOptimize();
        int serializedSize = rb.serializedSizeInBytes();
        assertTrue(serializedSize > 4 * 1024 * 1024,
                "spike needs a multi-MB payload to measure zero-copy; got " + serializedSize + " bytes");

        Path file = Files.createTempFile("roaring-spike", ".rb");
        try {
            ByteBuffer out = ByteBuffer.allocate(serializedSize);
            rb.serialize(out);
            out.flip();
            Files.write(file, out.array());

            // Path A — full heap-resident read: pull the whole file into a heap byte[] and
            // deserialize. Heap must grow by at least ~serializedSize (the byte[] alone).
            long baselineA = usedHeapAfterGc();
            byte[] whole = Files.readAllBytes(file);
            RoaringBitmap heapCopy = new RoaringBitmap();
            heapCopy.deserialize(ByteBuffer.wrap(whole));
            long heapGrowthA = usedHeapAfterGc() - baselineA;
            // Keep references alive across the measurement.
            assertEquals(rb.getCardinality(), heapCopy.getCardinality());
            assertTrue(whole.length == serializedSize);

            // Path B — mmap + ImmutableRoaringBitmap. Heap must grow far less than the payload,
            // because the container data stays in the mapped (off-heap) buffer.
            long baselineB = usedHeapAfterGc();
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                ImmutableRoaringBitmap irb = new ImmutableRoaringBitmap(mbb);
                long heapGrowthB = usedHeapAfterGc() - baselineB;

                assertEquals(rb.getCardinality(), irb.getCardinality(),
                        "mmap-backed bitmap must expose the same cardinality");
                assertTrue(heapGrowthB < serializedSize / 2L,
                        "mmap construction should not copy the payload into heap: growthB="
                                + heapGrowthB + " vs serializedSize=" + serializedSize);
                assertTrue(heapGrowthB * 4L < heapGrowthA,
                        "mmap heap growth (" + heapGrowthB + ") must be far below the full heap-read growth ("
                                + heapGrowthA + ")");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void serializationRoundTripsAcrossBufferAndNonBufferPackages() throws IOException {
        TreeSet<Integer> expected = new TreeSet<>();
        RoaringBitmap rb = new RoaringBitmap();
        int[] bits = {0, 1, 2, 5, 63, 64, 65, 1000, 65_535, 65_536, 70_000, 1_000_000, Integer.MAX_VALUE - 1};
        for (int b : bits) {
            rb.add(b);
            expected.add(b);
        }
        rb.runOptimize();

        ByteBuffer buf = ByteBuffer.allocate(rb.serializedSizeInBytes());
        rb.serialize(buf);
        buf.flip();

        // Query-side buffer-package view (this is what shards mmap at runtime).
        ImmutableRoaringBitmap irb = new ImmutableRoaringBitmap(buf.duplicate());
        TreeSet<Integer> fromImmutable = new TreeSet<>();
        irb.forEach((org.roaringbitmap.IntConsumer) fromImmutable::add);
        assertEquals(expected, fromImmutable, "ImmutableRoaringBitmap must reproduce the exact bit set");

        // Non-buffer deserialize of the same bytes.
        RoaringBitmap back = new RoaringBitmap();
        back.deserialize(buf.duplicate());
        TreeSet<Integer> fromNonBuffer = new TreeSet<>();
        back.forEach((org.roaringbitmap.IntConsumer) fromNonBuffer::add);
        assertEquals(expected, fromNonBuffer, "RoaringBitmap.deserialize must reproduce the exact bit set");
    }

    @Test
    void serializedSizePredictsBytesAndGetSizeIsUsableBudgetProxy() {
        RoaringBitmap rb = new RoaringBitmap();
        Random rnd = new Random(42L);
        for (int i = 0; i < 50_000; i++) {
            rb.add(rnd.nextInt(1_000_000));
        }
        rb.runOptimize();

        int predicted = rb.serializedSizeInBytes();
        ByteBuffer buf = ByteBuffer.allocate(predicted);
        rb.serialize(buf);
        assertEquals(predicted, buf.position(),
                "serializedSizeInBytes() must exactly match the number of bytes serialize() writes");

        int inMemory = rb.getSizeInBytes();
        // getSizeInBytes() (in-memory footprint) is the budget proxy used by ContentShardBuilder.
        // It need not equal serialized size, but must track it within a small constant factor so
        // budget-triggered flushes fire at the right scale.
        assertTrue(inMemory >= predicted / 2 && inMemory <= predicted * 3L,
                "getSizeInBytes()=" + inMemory + " must be same-order-of-magnitude as serialized="
                        + predicted);
    }
}
