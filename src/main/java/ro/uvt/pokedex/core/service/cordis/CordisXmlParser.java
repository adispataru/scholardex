package ro.uvt.pokedex.core.service.cordis;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * H64 slice 4b.3 — parse the CORDIS per-project XML export into a {@link CordisProject}. Namespace-agnostic DOM
 * (the export uses a default namespace {@code http://cordis.europa.eu}; parsing namespace-unaware keeps tag names
 * literal). XXE-hardened (no DTDs / external entities). Project-level fields are the first descendant in document
 * order (which is the project's, before the organization list); org fields are scoped to each {@code <organization>}.
 */
public final class CordisXmlParser {

    private CordisXmlParser() {
    }

    public static CordisProject parse(String grantId, String xml) {
        Document doc = parseDocument(xml);
        Element root = doc.getDocumentElement();

        List<CordisProject.CordisOrg> orgs = new ArrayList<>();
        NodeList orgNodes = doc.getElementsByTagName("organization");
        for (int i = 0; i < orgNodes.getLength(); i++) {
            Element o = (Element) orgNodes.item(i);
            orgs.add(new CordisProject.CordisOrg(
                    emptyToNull(o.getAttribute("type")),
                    childText(o, "legalName"),
                    childText(o, "shortName"),
                    childText(o, "country"),
                    parseLong(o.getAttribute("ecContribution"))));
        }

        return new CordisProject(
                grantId,
                childText(root, "rcn"),
                childText(root, "acronym"),
                childText(root, "title"),
                childText(root, "startDate"),
                childText(root, "endDate"),
                childText(root, "frameworkProgramme"),
                parseLong(childText(root, "totalCost")),
                parseLong(childText(root, "ecMaxContribution")),
                orgs);
    }

    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CORDIS XML: " + e.getMessage(), e);
        }
    }

    private static void setFeatureQuietly(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // feature not supported by this parser — best effort
        }
    }

    /** First descendant element named {@code tag} under {@code parent}, trimmed text, or null. */
    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node n = nodes.item(0);
        String text = n.getTextContent();
        return emptyToNull(text == null ? null : text.trim());
    }

    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
