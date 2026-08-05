package com.zin.delamain.server.routes;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5 (4): {@code ResourceRoutes.parseStringsXml} previously only set
 * {@code load-external-dtd=false}, which blocks an externally-referenced DTD subset but not a
 * strings.xml that declares its OWN internal DTD subset with an entity like
 * {@code <!ENTITY xxe SYSTEM "file:///...">} — that is governed by
 * {@code external-general-entities}, a separate feature this parser never set (unlike this
 * codebase's other two XML parsers, {@code ManifestInfoService.parseXml} and {@code
 * AnalysisRoutes.parseXml}, both already hardened). Actual reachability from a crafted
 * resources.arsc strings.xml is not proven here (would need constructing a raw ARSC/AXML
 * payload); the fix is independent of reachability, so this is a regression test on the parser
 * configuration directly rather than an end-to-end exploit reproduction.
 */
class ResourceRoutesXxeHardeningTest {

    private final ResourceRoutes routes = new ResourceRoutes(null, null);

    @Test
    void rejectsDoctypeWithInternalEntityInsteadOfExpandingIt() {
        String xxePayload = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE resources [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
            + "<resources><string name=\"leak\">&xxe;</string></resources>";

        Map<String, String> result = routes.parseStringsXml(xxePayload);

        assertTrue(result.isEmpty(),
            "a strings.xml carrying a DOCTYPE (with or without an internal entity) must be "
                + "rejected outright by disallow-doctype-decl, not partially parsed with the "
                + "entity expanded: " + result);
    }

    @Test
    void stillParsesOrdinaryStringsWithoutADoctype() {
        String xml = "<?xml version=\"1.0\"?><resources><string name=\"app_name\">Hello</string></resources>";

        Map<String, String> result = routes.parseStringsXml(xml);

        assertEquals("Hello", result.get("app_name"),
            "hardening the parser must not break parsing of a normal, doctype-free strings.xml");
    }
}
