package com.zin.delamain;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped fat jar must carry a WORKING SLF4J binding.
 *
 * <p>Production incident (found 2026-07-22 while debugging 20260722.2 on a live server): the Java
 * backend had emitted <b>no logs at all</b>, in every released version. Container startup showed
 *
 * <pre>
 *   SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
 *   SLF4J: Defaulting to no-operation (NOP) logger implementation
 * </pre>
 *
 * so every {@code logger.info/warn/error} in the server was a silent no-op — search timings,
 * warmup progress, the OOM handler, all of it. Every production investigation so far has been run
 * blind off HTTP response bodies.
 *
 * <p>Cause: {@code jadx-all} pulls in {@code slf4j-api 1.7.30} transitively. The jadx artifact
 * itself is excluded from the shade (it is supplied via {@code -cp} at runtime) but its transitive
 * slf4j-api was not, so the 1.7 API got shaded into the jar while the declared binding is
 * {@code slf4j-simple 2.0.13}. The 1.7 API looks up {@code org.slf4j.impl.StaticLoggerBinder},
 * which a 2.x provider does not supply → NOP.
 *
 * <p>Unit tests never caught it because surefire runs against the Maven dependency classpath
 * (clean 2.x api + provider), not the shaded artifact. This test therefore inspects the artifact
 * itself — the only place the defect exists.
 */
class PackagedLoggingBindingTest {

    /** SLF4J 2.x discovers bindings through this ServiceLoader entry; 1.x had no such file. */
    private static final String PROVIDER_SERVICE = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";

    private static JarFile openShadedJar() throws Exception {
        Path jar = Path.of("target", "delamain.jar");
        // Only meaningful after `mvn package`. Skipped (not failed) on a bare `mvn test`.
        assumeTrue(Files.isRegularFile(jar),
            "target/delamain.jar not built yet — run `mvn package` for this check to apply");
        return new JarFile(new File(jar.toString()));
    }

    @Test
    void shadedJarDeclaresAnSlf4j2ServiceProvider() throws Exception {
        try (JarFile jar = openShadedJar()) {
            JarEntry entry = jar.getJarEntry(PROVIDER_SERVICE);
            assertNotNull(entry,
                "the fat jar must declare an SLF4J 2.x service provider, otherwise the runtime "
                    + "silently falls back to the NOP logger and the server logs nothing");
            try (InputStream in = jar.getInputStream(entry)) {
                String declared = new String(in.readAllBytes()).trim();
                assertTrue(declared.contains("org.slf4j.simple.SimpleServiceProvider"),
                    "expected the slf4j-simple provider to be declared, got: " + declared);
            }
        }
    }

    /**
     * The api and the provider must be the same generation. A 1.7 {@code LoggerFactory} shipped
     * alongside a 2.x provider is exactly the production failure — and it is invisible unless the
     * class itself is inspected, because both jars are "present".
     */
    @Test
    void shadedApiIsTheSameGenerationAsTheProvider() throws Exception {
        try (JarFile jar = openShadedJar()) {
            JarEntry api = jar.getJarEntry("org/slf4j/LoggerFactory.class");
            assertNotNull(api, "slf4j-api must be shaded into the fat jar");

            // SLF4J 2.x ships org.slf4j.spi.SLF4JServiceProvider; 1.7.x does not. Its presence
            // next to LoggerFactory is what proves the shaded api is 2.x.
            assertNotNull(jar.getJarEntry("org/slf4j/spi/SLF4JServiceProvider.class"),
                "shaded slf4j-api is pre-2.x (no SLF4JServiceProvider) while the binding is 2.x — "
                    + "this is the NOP-logger packaging bug; pin slf4j-api to the provider's major");

            // And the 1.x lookup class must NOT be what the api expects to find.
            assertEquals(null, jar.getJarEntry("org/slf4j/impl/StaticLoggerBinder.class"),
                "a 1.x StaticLoggerBinder in the fat jar means a 1.x api leaked back in");
        }
    }
}
