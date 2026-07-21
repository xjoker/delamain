package com.zin.delamain.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Single source of truth for the application version at runtime.
 *
 * <p>The repository root {@code VERSION} file (format {@code YYYYMMDD.N}) is copied onto the
 * classpath as {@code /VERSION} by the maven-resources-plugin during {@code process-resources}
 * (see pom.xml), so it ends up in the shaded jar and is readable here without duplicating the
 * value in source code.
 */
public final class AppVersion {

    private static final String FALLBACK = "unknown";
    private static final String VALUE = resolve();

    private AppVersion() {}

    public static String get() {
        return VALUE;
    }

    private static String resolve() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/VERSION")) {
            if (in == null) {
                return FALLBACK;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line != null ? line.trim() : FALLBACK;
            }
        } catch (IOException e) {
            return FALLBACK;
        }
    }
}
