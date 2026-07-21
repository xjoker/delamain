package com.zin.delamain.server.routes;

import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.FilePathSandbox;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Security boundary from the upload-transfer contract: the {@code .transfer} staging directory
 * used by {@link TransferRoutes} for in-flight uploads must never be surfaced by
 * list-available-files, regardless of glob/recursive — it holds partially-written files keyed by
 * a bearer-equivalent token and is an implementation detail, not a browsable sandbox file.
 */
class FileManagementRoutesHiddenEntryTest {

    private Javalin app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void dotPrefixedEntries_areExcludedFromRecursiveListing(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.toRealPath();
        Files.createDirectory(root.resolve(".transfer"));
        Files.writeString(root.resolve(".transfer").resolve("abc123.part"), "partial");
        Files.writeString(root.resolve("visible.apk"), "real apk");

        FilePathSandbox sandbox = new FilePathSandbox(root);
        app = Javalin.create();
        new FileManagementRoutes(null, sandbox).register(app, new AuthConfig(null, false));
        app.start(0);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/list-available-files?recursive=true"))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), resp.body());
        assertFalse(resp.body().contains(".part"), "staging .part file must not be listed: " + resp.body());
        assertFalse(resp.body().contains(".transfer"), ".transfer dir must not be listed: " + resp.body());
        assertEquals(1, countOccurrences(resp.body(), "\"path\""), "only the visible.apk entry must be listed");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
