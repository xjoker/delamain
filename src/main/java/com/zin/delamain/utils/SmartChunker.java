package com.zin.delamain.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart content chunker for large responses.
 */
public class SmartChunker {

    public static final int DEFAULT_CHUNK_SIZE = 8000;
    public static final int MIN_CHUNK_THRESHOLD = DEFAULT_CHUNK_SIZE;

    public static Map<String, Object> chunkResponse(String content, int requestedChunk, String responseKey) {
        return chunkResponse(content, requestedChunk, responseKey, DEFAULT_CHUNK_SIZE);
    }

    public static Map<String, Object> chunkResponse(String content, int requestedChunk, String responseKey, int chunkSize) {
        Map<String, Object> result = new HashMap<>();

        if (content == null || content.isEmpty()) {
            result.put(responseKey, content != null ? content : "");
            return result;
        }

        if (chunkSize <= 0) {
            result.put("error", "Invalid chunk size. Expected a positive byte count but got: " + chunkSize);
            return result;
        }

        int contentLength = getUtf8Length(content);

        if (contentLength <= chunkSize) {
            result.put(responseKey, content);
            return result;
        }

        List<Integer> chunkBoundaries = calculateChunkBoundaries(content, chunkSize);

        int totalChunks = chunkBoundaries.size() - 1;
        int chunk = requestedChunk <= 0 ? 1 : requestedChunk;

        if (chunk > totalChunks) {
            result.put("error", "Invalid chunk number. Requested: " + chunk + ", Total: " + totalChunks);
            return result;
        }

        int start = chunkBoundaries.get(chunk - 1);
        int end = chunkBoundaries.get(chunk);

        result.put(responseKey, content.substring(start, end));

        Map<String, Object> chunking = new HashMap<>();
        chunking.put("enabled", true);
        chunking.put("total_size", contentLength);
        chunking.put("total_chunks", totalChunks);
        chunking.put("current_chunk", chunk);
        chunking.put("chunk_size", chunkSize);
        chunking.put("has_more", chunk < totalChunks);

        if (chunk < totalChunks) {
            chunking.put("next_chunk", chunk + 1);
        }

        chunking.put("warning",
            "Response chunked due to size (" + contentLength + " bytes). " +
            "Use chunk=" + (chunk + 1) + " parameter to get next chunk. " +
            "Total chunks: " + totalChunks);

        result.put("_chunking", chunking);

        return result;
    }

    public static boolean wouldChunk(String content) {
        return content != null && getUtf8Length(content) > MIN_CHUNK_THRESHOLD;
    }

    public static int getChunkCount(String content) {
        if (content == null) {
            return 1;
        }
        int contentLength = getUtf8Length(content);
        if (contentLength <= DEFAULT_CHUNK_SIZE) {
            return 1;
        }
        return calculateChunkBoundaries(content, DEFAULT_CHUNK_SIZE).size() - 1;
    }

    private static int getUtf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<Integer> calculateChunkBoundaries(String content, int chunkSize) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);

        int length = content.length();
        int index = 0;
        while (index < length) {
            int chunkStart = index;
            int bytesInChunk = 0;

            while (index < length) {
                int codePoint = content.codePointAt(index);
                int nextIndex = index + Character.charCount(codePoint);
                int codePointBytes = getUtf8Length(content.substring(index, nextIndex));

                if (bytesInChunk > 0 && bytesInChunk + codePointBytes > chunkSize) {
                    break;
                }

                bytesInChunk += codePointBytes;
                index = nextIndex;
            }

            if (index == chunkStart) {
                int codePoint = content.codePointAt(index);
                index += Character.charCount(codePoint);
            }

            boundaries.add(index);
        }
        return boundaries;
    }
}
