package com.zin.delamain.server.routes;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2: {@code POST /load-file} returns 202 immediately while the reload — and the auto-warmup it
 * triggers — run in the background. The old response said only "poll /decompile-status", which
 * tells the caller when the class tree is loaded but nothing about which capabilities are usable
 * when. Observed consequence on production: the agent starts issuing {@code search_in=code} and
 * xref calls the moment load completes, i.e. exactly while warmup is saturating the CPU and the
 * content index does not exist yet — the slowest possible moment.
 *
 * <p>So the dispatch response must carry the warmup contract: that warmup auto-starts, where the
 * live phase/eta/capabilities live, and an {@code _ai_instruction} telling the agent to gate code
 * search on {@code capabilities.code_search == "ready"} instead of polling blind. (The embedded
 * {@code _ai_instruction} convention is already used by the class/search tools and is confirmed by
 * session archives to actually be followed.)</p>
 */
class FileManagementRoutesLoadResponseGuidanceTest {

    private final FileManagementRoutes routes = new FileManagementRoutes(null, null);

    @Test
    void dispatchResponseKeepsItsExistingContract() {
        Map<String, Object> resp = routes.buildLoadDispatchResponse("replace", Paths.get("/apks/x.apk"));

        assertEquals(true, resp.get("dispatched"));
        assertEquals("replace", resp.get("mode"));
        assertEquals("/apks/x.apk", resp.get("path"));
        assertEquals(false, resp.get("ready"));
    }

    @Test
    void dispatchResponseAnnouncesAutoWarmupAndWhereToWatchIt() {
        Map<String, Object> resp = routes.buildLoadDispatchResponse("replace", Paths.get("/apks/x.apk"));

        assertEquals(true, resp.get("auto_warmup"),
            "the caller cannot see the background warmup trigger unless we say it happens: " + resp);
        assertTrue(String.valueOf(resp.get("warmup_status_endpoint")).contains("warmup-status"),
            "phase/eta/capabilities live on the warmup-status endpoint: " + resp);
    }

    @Test
    void dispatchResponseCarriesTheWarmupPhaseAndCapabilitiesSnapshot() {
        Map<String, Object> resp = routes.buildLoadDispatchResponse("replace", Paths.get("/apks/x.apk"));

        @SuppressWarnings("unchecked")
        Map<String, Object> warmup = (Map<String, Object>) resp.get("warmup");
        assertNotNull(warmup, "a phase/eta/capabilities snapshot must ship with the 202: " + resp);
        assertNotNull(warmup.get("phase"), "phase must be present: " + warmup);
        assertTrue(warmup.containsKey("eta_seconds"), "eta must be present (may be null): " + warmup);
        assertNotNull(warmup.get("capabilities"), "per-capability readiness must be present: " + warmup);

        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) warmup.get("capabilities");
        assertTrue(caps.containsKey("code_search"),
            "code_search is the capability the agent must gate on: " + caps);
    }

    @Test
    void dispatchResponseTellsTheAgentWhatToDoWhileItWarms() {
        Map<String, Object> resp = routes.buildLoadDispatchResponse("replace", Paths.get("/apks/x.apk"));

        String instruction = (String) resp.get("_ai_instruction");
        assertNotNull(instruction, "_ai_instruction is the convention the agent actually reads: " + resp);
        assertTrue(instruction.contains("code_search"),
            "must name the capability gate for code search: " + instruction);
        assertTrue(instruction.contains("warmup-status") || instruction.contains("get_warmup_status"),
            "must name where to poll: " + instruction);
    }
}
