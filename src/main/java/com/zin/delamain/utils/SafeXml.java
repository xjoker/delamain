package com.zin.delamain.utils;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * F9: shared factory for the hardened {@link DocumentBuilder} configuration that used to be
 * copy-pasted identically across {@code ManifestInfoService.parseXml}, {@code
 * AnalysisRoutes.parseXml} and {@code ResourceRoutes.parseStringsXml}. That duplication is what
 * let one of the three copies drift and ship without the full feature set (see the XXE finding
 * this class was extracted to fix the recurrence of) — a single shared factory means there is
 * only one place left to get this wrong.
 *
 * <p>{@code disallow-doctype-decl=true} alone would already reject any DOCTYPE (including a
 * crafted internal-subset entity attack), but the full belt-and-suspenders set below — also
 * disabling external general/parameter entities and external DTD loading, plus secure processing,
 * no XInclude and no entity expansion — is kept exactly as all three original call sites had it.
 */
public final class SafeXml {

    private SafeXml() {
    }

    /** Returns a new {@link DocumentBuilder} hardened against XXE / entity-expansion attacks. */
    public static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
