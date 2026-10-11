/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins project contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.persistence;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Three-way merge of XML documents saved concurrently by two controllers, such as {@code config.xml} with one controller
 * changing the quiet period and another the system message.
 *
 * <p>The unit of merging is a direct child of the root element, by name (all children of one name together, so lists
 * merge as wholes): a unit only one side changed takes that side's version; a unit both changed takes mine and counts as a
 * conflict. The root's attributes are one more unit.
 */
@Restricted(NoExternalUse.class)
public final class XmlMerge {

    private XmlMerge() {}

    /** The outcome of a merge. */
    public record Result(@NonNull byte[] merged, int conflicts) {}

    /**
     * @return the merged document, or null if the documents cannot be merged this way (not XML, different roots, text
     *     directly in the root)
     */
    @CheckForNull
    public static Result merge(@NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
        try {
            Element b = parse(base);
            Element m = parse(mine);
            Element t = parse(theirs);
            if (b == null || m == null || t == null || !b.getTagName().equals(m.getTagName()) || !m.getTagName().equals(t.getTagName())) {
                return null;
            }
            int[] conflicts = {0};
            String inner = mergeChildren(b, m, t, "  ", conflicts);
            if (inner == null) {
                return null;
            }
            String root = m.getTagName();
            String attrs = mergeAttributes(b, m, t, conflicts);
            String doc = declaration(mine) + "\n<" + root + attrs + ">\n" + inner + "</" + root + ">";
            return new Result(doc.getBytes(StandardCharsets.UTF_8), conflicts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merges the children of three versions of an element, by name; one line per child at {@code indent}.
     *
     * @return null if an element has text of its own among its children (mixed content)
     */
    @CheckForNull
    private static String mergeChildren(Element b, Element m, Element t, String indent, int[] conflicts) throws Exception {
        Map<String, List<Element>> bu = children(b);
        Map<String, List<Element>> mu = children(m);
        Map<String, List<Element>> tu = children(t);
        if (bu == null || mu == null || tu == null) {
            return null;
        }
        List<String> names = new ArrayList<>(mu.keySet());
        for (String name : tu.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : bu.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            List<Element> be = bu.get(name);
            List<Element> me = mu.get(name);
            List<Element> te = tu.get(name);
            String bv = serialise(be);
            String mv = serialise(me);
            String tv = serialise(te);
            List<String> chosen;
            if (Objects.equals(mv, bv)) {
                chosen = verbatim(te);
            } else if (Objects.equals(tv, bv) || Objects.equals(mv, tv)) {
                chosen = verbatim(me);
            } else if (single(be) && single(me) && single(te) && sameAttributes(be.get(0), me.get(0), te.get(0))
                    && structured(be.get(0)) && structured(me.get(0)) && structured(te.get(0))) {
                // changed on both sides, but made of distinctly named parts (e.g. <properties>): merge those
                Element me0 = me.get(0);
                String inner = mergeChildren(be.get(0), me0, te.get(0), indent + "  ", conflicts);
                String open = "<" + me0.getTagName() + attributes(me0);
                chosen = List.of(inner == null || inner.isEmpty() ? open + "/>" : open + ">\n" + inner + indent + "</" + me0.getTagName() + ">");
            } else {
                chosen = verbatim(me);
                conflicts[0]++;
            }
            for (String element : chosen) {
                out.append(indent).append(element).append('\n'); // verbatim: text content keeps its whitespace
            }
        }
        return out.toString();
    }

    /** Child elements by name, in order; null if there is non-blank text among them. */
    @CheckForNull
    private static Map<String, List<Element>> children(Element e) {
        Map<String, List<Element>> result = new LinkedHashMap<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE -> result.computeIfAbsent(n.getNodeName(), k -> new ArrayList<>()).add((Element) n);
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    if (!n.getNodeValue().isBlank()) {
                        return null;
                    }
                }
                case Node.COMMENT_NODE -> { }
                default -> {
                    return null;
                }
            }
        }
        return result;
    }

    private static boolean single(@CheckForNull List<Element> elements) {
        return elements != null && elements.size() == 1;
    }

    /** Whether an element is made only of child elements with distinct names (so they can be merged one by one). */
    private static boolean structured(Element e) {
        Map<String, List<Element>> c = children(e);
        return c != null && !c.isEmpty() && c.values().stream().allMatch(l -> l.size() == 1);
    }

    private static boolean sameAttributes(Element b, Element m, Element t) {
        return attributes(b).equals(attributes(m)) && attributes(m).equals(attributes(t));
    }

    private static String mergeAttributes(Element b, Element m, Element t, int[] conflicts) {
        String ba = attributes(b);
        String ma = attributes(m);
        String ta = attributes(t);
        if (ma.equals(ba)) {
            return ta;
        }
        if (!ta.equals(ba) && !ta.equals(ma)) {
            conflicts[0]++;
        }
        return ma;
    }

    private static String attributes(Element e) {
        StringBuilder attrs = new StringBuilder();
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            attrs.append(' ').append(n.getNodeName()).append("=\"").append(escapeAttribute(n.getNodeValue())).append('"');
        }
        return attrs.toString();
    }

    @CheckForNull
    private static String serialise(@CheckForNull List<Element> elements) throws Exception {
        if (elements == null) {
            return null;
        }
        return String.join(SEPARATOR, verbatim(elements));
    }

    private static List<String> verbatim(@CheckForNull List<Element> elements) throws Exception {
        List<String> result = new ArrayList<>();
        if (elements != null) {
            for (Element e : elements) {
                result.add(serialise(e));
            }
        }
        return result;
    }

    /** Between the serialised elements of one unit (cannot occur in XML). */
    private static final String SEPARATOR = "\u0000";

    @CheckForNull
    private static Element parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder db = f.newDocumentBuilder();
        db.setErrorHandler(null);
        Document d = db.parse(new ByteArrayInputStream(xml));
        return d.getDocumentElement();
    }

    private static String serialise(Node n) throws Exception {
        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        tr.transform(new DOMSource(n), new StreamResult(w));
        return w.toString();
    }

    private static String escapeAttribute(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private static String declaration(byte[] xml) {
        String s = new String(xml, 0, Math.min(xml.length, 200), StandardCharsets.UTF_8);
        if (s.startsWith("<?xml")) {
            int end = s.indexOf("?>");
            if (end > 0) {
                return s.substring(0, end + 2);
            }
        }
        return "<?xml version='1.1' encoding='UTF-8'?>";
    }

}
