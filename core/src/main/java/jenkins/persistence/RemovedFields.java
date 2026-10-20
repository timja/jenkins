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

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.XmlFile;
import hudson.util.PersistedList;
import hudson.util.XStream2;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Clears the fields of an object loaded over that the stored content it is loaded from lacks
 * ({@link #clear(XmlFile, Object, byte[])}).
 */
@Restricted(NoExternalUse.class)
public final class RemovedFields {

    private static final Logger LOGGER = Logger.getLogger(RemovedFields.class.getName());

    private RemovedFields() {}

    /**
     * Prepares an object that is loaded over (not replaced, nor nulled out) for loading stored content another
     * controller wrote: clears its top-level fields which it holds a value for (as its serialisation has them) and the
     * stored content lacks, as XStream writes no element for a null field; such as a value cleared elsewhere, or one a
     * refused save left in memory. A collection or map is emptied rather than nulled, and final fields are left alone.
     */
    @Restricted(NoExternalUse.class)
    public static void clear(@NonNull XmlFile file, @NonNull Object o, @NonNull byte[] stored) {
        Set<String> had;
        Set<String> kept;
        try {
            had = topLevelElements(file.getXStream().toXML(o).getBytes(StandardCharsets.UTF_8));
            kept = topLevelElements(stored);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Not XML: no fields cleared", e);
            return;
        }
        had.removeAll(kept);
        XStream xs = file.getXStream();
        for (String element : had) {
            Field f = fieldFor(xs, o.getClass(), element);
            int m = f == null ? 0 : f.getModifiers();
            if (f == null || f.getType().isPrimitive() || Modifier.isStatic(m) || Modifier.isFinal(m) || Modifier.isTransient(m)) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(o);
                // a PersistedList would save its owner, half reloaded, when emptied: nulled, as loading a file without it does
                if (value instanceof Collection<?> c && !(value instanceof PersistedList<?>)) {
                    clearValue(f, o, c);
                } else if (value instanceof Map<?, ?> map) {
                    clearValue(f, o, map);
                } else {
                    f.set(o, null);
                }
                LOGGER.fine(() -> "Cleared " + f + ", removed elsewhere from " + file);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not clear " + f, e);
            }
        }
    }

    /**
     * Empties a collection or map held in a field; one that cannot be changed ({@code List.of()}, say) is replaced by an
     * empty one of the field's type, or else null, rather than left with what was removed elsewhere.
     */
    private static void clearValue(Field f, Object o, Object collectionOrMap) throws IllegalAccessException {
        try {
            if (collectionOrMap instanceof Collection<?> c) {
                c.clear();
            } else {
                ((Map<?, ?>) collectionOrMap).clear();
            }
        } catch (UnsupportedOperationException e) {
            Object empty = null;
            for (Object candidate : List.of(List.of(), Set.of(), Map.of())) {
                if (f.getType().isInstance(candidate)) {
                    empty = candidate;
                    break;
                }
            }
            f.set(o, empty);
        }
    }

    private static Set<String> topLevelElements(byte[] xml) {
        Set<String> names = new TreeSet<>();
        HierarchicalStreamReader r = XStream2.getDefaultDriver().createReader(new ByteArrayInputStream(xml));
        try {
            while (r.hasMoreChildren()) {
                r.moveDown();
                if (r.getAttribute("defined-in") == null) { // a field hidden by one of a subclass: left alone
                    names.add(r.getNodeName());
                }
                r.moveUp();
            }
        } finally {
            r.close();
        }
        return names;
    }

    @CheckForNull
    private static Field fieldFor(XStream xs, Class<?> type, String element) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(xs.getMapper().realMember(c, element));
            } catch (NoSuchFieldException e) {
                // declared further up, or not a field
            }
        }
        return null;
    }
}
