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
            Map<String, String> bu = units(b);
            Map<String, String> mu = units(m);
            Map<String, String> tu = units(t);
            if (bu == null || mu == null || tu == null) {
                return null;
            }
            int conflicts = 0;
            Map<String, String> result = new LinkedHashMap<>();
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
            for (String name : names) {
                String bv = bu.get(name);
                String mv = mu.get(name);
                String tv = tu.get(name);
                String v;
                if (Objects.equals(mv, bv)) {
                    v = tv;
                } else if (Objects.equals(tv, bv) || Objects.equals(mv, tv)) {
                    v = mv;
                } else {
                    v = mv;
                    conflicts++;
                }
                if (v != null) {
                    result.put(name, v);
                }
            }
            return new Result(write(m.getTagName(), result, declaration(mine)), conflicts);
        } catch (Exception e) {
            return null;
        }
    }

    private static final String ATTRIBUTES = "@";
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

    /** name → serialised children of that name, in order; {@link #ATTRIBUTES} → the root's attributes; null if mixed text */
    @CheckForNull
    private static Map<String, String> units(Element root) throws Exception {
        Map<String, StringBuilder> units = new LinkedHashMap<>();
        StringBuilder attrs = new StringBuilder();
        NamedNodeMap a = root.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            attrs.append(' ').append(n.getNodeName()).append("=\"").append(escapeAttribute(n.getNodeValue())).append('"');
        }
        if (!attrs.isEmpty()) {
            units.put(ATTRIBUTES, attrs);
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE -> units.computeIfAbsent(n.getNodeName(), k -> new StringBuilder()).append(serialise(n)).append(SEPARATOR);
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
        Map<String, String> result = new LinkedHashMap<>();
        units.forEach((k, v) -> result.put(k, v.toString()));
        return result;
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

    private static byte[] write(String root, Map<String, String> units, String declaration) {
        StringBuilder out = new StringBuilder(declaration).append('\n').append('<').append(root);
        String attrs = units.get(ATTRIBUTES);
        if (attrs != null) {
            out.append(attrs);
        }
        out.append(">\n");
        for (Map.Entry<String, String> u : units.entrySet()) {
            if (u.getKey().equals(ATTRIBUTES)) {
                continue;
            }
            for (String element : u.getValue().split(SEPARATOR)) {
                if (!element.isEmpty()) {
                    out.append("  ").append(element).append('\n'); // verbatim: text content keeps its whitespace
                }
            }
        }
        out.append("</").append(root).append('>');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
