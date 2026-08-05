package com.zin.delamain.utils;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2: {@link SafeXml} was extracted from three previously-duplicated (and one drifted) XXE
 * hardening call sites, but never had a test pinning its own contract independent of those
 * callers ({@code ResourceRoutesXxeHardeningTest} etc. only exercise it transitively). This class
 * is security-critical (it is the sole XXE defense for every XML this codebase parses), so its
 * behavior must stay pinned even if a future refactor changes or removes one of the callers that
 * currently cover it incidentally.
 *
 * <p>Assertions below are all on observable parse behavior (does it throw / what does the DOM
 * look like), never on the factory's internal feature flags — {@code
 * disallow-doctype-decl=false} is exactly the mutation the destructive-verification step in the
 * G2 task report flips to confirm these tests actually have teeth.
 */
class SafeXmlTest {

    private static DocumentBuilder builder() throws Exception {
        return SafeXml.newSecureDocumentBuilder();
    }

    private static Document parse(String xml) throws Exception {
        return builder().parse(new InputSource(new StringReader(xml)));
    }

    @Test
    void rejectsBareDoctypeDeclarationWithNoEntitiesAtAll() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE root><root/>";

        assertThrows(SAXException.class, () -> parse(xml),
            "any DOCTYPE at all must be rejected outright by disallow-doctype-decl, even one "
                + "declaring no entities");
    }

    @Test
    void rejectsDoctypeCarryingAnInternalEntityInsteadOfExpandingIt() {
        String xml = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE root [<!ENTITY xxe \"leaked\">]>"
            + "<root>&xxe;</root>";

        assertThrows(SAXException.class, () -> parse(xml),
            "a DOCTYPE declaring an internal entity must be rejected, not parsed with the entity "
                + "silently expanded into the document");
    }

    @Test
    void rejectsDoctypeReferencingAnExternalSystemEntity() {
        String xml = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
            + "<root>&xxe;</root>";

        assertThrows(SAXException.class, () -> parse(xml),
            "a DOCTYPE referencing an external SYSTEM entity (the classic XXE file-read shape) "
                + "must be rejected, never resolved");
    }

    @Test
    void stillParsesOrdinaryXmlThatCarriesNoDoctype() throws Exception {
        String xml = "<?xml version=\"1.0\"?><root><child>hello</child></root>";

        Document doc = parse(xml);

        assertEquals("hello", doc.getDocumentElement()
            .getElementsByTagName("child").item(0).getTextContent(),
            "hardening the parser must not break parsing of ordinary DOCTYPE-free XML");
    }

    @Test
    void builderIsConfiguredNamespaceAware() throws Exception {
        assertTrue(builder().isNamespaceAware(),
            "callers rely on namespace-aware parsing (setNamespaceAware(true))");
    }

    @Test
    void doesNotProcessXIncludeElements() throws Exception {
        // xi:include is plain namespaced markup, not gated by disallow-doctype-decl, so this
        // exercises setXIncludeAware(false) specifically: if XInclude processing were enabled,
        // the parser would attempt to resolve href against the (nonexistent) file and this parse
        // would behave differently (fail resolving it, or splice in foreign content) instead of
        // leaving the element exactly as written.
        String xml = "<?xml version=\"1.0\"?>"
            + "<root xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
            + "<xi:include href=\"file:///nonexistent-should-never-be-touched.xml\"/>"
            + "</root>";

        Document doc = parse(xml);

        Element include = (Element) doc.getDocumentElement().getFirstChild();
        assertEquals("xi:include", include.getTagName(),
            "with XInclude processing disabled the element must survive untouched, proving no "
                + "attempt was made to resolve/splice its href");
        assertEquals("file:///nonexistent-should-never-be-touched.xml", include.getAttribute("href"),
            "the href attribute must be left exactly as written, not consumed by XInclude resolution");
    }

    @Test
    void returnsAFreshDocumentBuilderInstanceEachCall() throws Exception {
        DocumentBuilder first = builder();
        DocumentBuilder second = builder();

        assertNotSame(first, second,
            "DocumentBuilder is stateful and not thread-safe — callers must each get their own "
                + "instance rather than sharing one, so concurrent parses cannot corrupt each other");
    }
}
