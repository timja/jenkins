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
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Items;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Reloads an item changed elsewhere by copying only the fields that changed (Target 15), instead of unmarshalling
 * the new version into the live object.
 *
 * <p>Setters assign a field and then save, without the object's lock. Unmarshalling in place under the lock took long
 * enough for such an assignment to land meanwhile and be overwritten, and its save then wrote the reloaded value: the
 * local change was lost. Now the new version is read into a scratch instance first, outside the lock. Under the lock
 * only the fields whose serialisation changed since the object was last in sync are copied over, and of those not
 * any that changed here meanwhile (its pending save then merges with the store). So a local change is lost only if
 * the same field changed in both places at once, which is a genuine conflict.
 *
 * <p>Falls back to the full reload whenever this cannot be done safely: no snapshot of the last sync, parts that do
 * not map onto fields (implicit collections, shadowed fields), or a write here since the scratch copy was read.
 */
final class ItemReload {

    private static final Logger LOGGER = Logger.getLogger(ItemReload.class.getName());

    /** The new version, read but not yet applied. */
    record Prepared(Object scratch, Map<String, String> parts, byte[] snapshot, String key, PersistenceStore.Entry entry) {}

    private ItemReload() {}

    /** Reads the stored version into a new instance; call without the item's lock. Null if a full reload is needed. */
    @CheckForNull
    static Prepared prepare(@NonNull AbstractItem item) {
        XmlFile file = item.getConfigFile();
        byte[] snapshot = PersistenceStores.snapshotOf(file);
        PersistenceStores.Route route = PersistenceStores.route(file.getPath());
        if (route == null || snapshot == null) {
            return null;
        }
        try {
            // peek, not read: this JVM must not take the new version as the one its object holds before it does (a save
            // meanwhile would then overwrite the change made elsewhere instead of merging with it)
            PersistenceStore.Entry entry = route.peek();
            if (entry == null) {
                return null;
            }
            Object scratch = file.getXStream().fromXML(new java.io.ByteArrayInputStream(entry.content()));
            if (scratch == null || scratch.getClass() != item.getClass()) {
                return null;
            }
            Map<String, String> parts = XmlMerge.parts(serialise(file.getXStream(), scratch));
            return parts == null ? null : new Prepared(scratch, parts, snapshot, route.key(), entry);
        } catch (Exception | LinkageError e) {
            LOGGER.log(Level.FINE, "Cannot reload " + item.getFullName() + " field by field", e);
            return null;
        }
    }

    /**
     * Copies the changed fields into the live item and runs its {@code onLoad}; call under the item's lock.
     *
     * @return false if nothing was done and a full reload is needed
     */
    static boolean apply(@NonNull AbstractItem item, @NonNull Prepared prepared) throws Exception {
        XmlFile file = item.getConfigFile();
        byte[] snapshot = PersistenceStores.snapshotOf(file);
        if (snapshot != prepared.snapshot()) {
            // saved here since the scratch copy was read (and perhaps merged with the store): the scratch copy is not
            // the newest content any more; the full reload reads what the store has now
            return false;
        }
        XStream xs = file.getXStream();
        Map<String, String> before = XmlMerge.parts(snapshot);
        Map<String, String> live = XmlMerge.parts(serialise(xs, item));
        if (before == null || live == null) {
            return false;
        }
        Set<String> changed = new HashSet<>(before.keySet());
        changed.addAll(prepared.parts().keySet());
        changed.removeIf(name -> Objects.equals(before.get(name), prepared.parts().get(name)));
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String name : changed) {
            Field f = field(xs, item.getClass(), name);
            if (f == null) {
                return false; // e.g. an implicit collection: cannot tell which field it is
            }
            fields.put(name, f);
        }
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            if (!Objects.equals(live.get(e.getKey()), before.get(e.getKey()))) {
                // changed here too since the last sync: keep it; its save is pending and merges with the store
                LOGGER.fine(() -> "Keeping the local change of " + e.getKey() + " in " + item.getFullName());
                continue;
            }
            e.getValue().set(item, e.getValue().get(prepared.scratch()));
        }
        PersistenceStores.loaded(prepared.key(), prepared.entry());
        // as AbstractItem.load does after unmarshalling
        Items.runWhileUpdatingByXml(() -> item.onLoad(item.getParent(), item.getParent().getItemName(item.getRootDir(), item)));
        Jenkins.get().rebuildDependencyGraphAsync();
        return true;
    }

    private static byte[] serialise(XStream xs, Object o) {
        return xs.toXML(o).getBytes(StandardCharsets.UTF_8);
    }

    /** The serialised field an element stands for, or null if it is not one plain field. */
    @CheckForNull
    static Field field(XStream xs, Class<?> type, String element) {
        String name = xs.getMapper().realMember(type, element);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || !xs.getMapper().shouldSerializeMember(c, name)) {
                    return null;
                }
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // a superclass then
            } catch (RuntimeException e) {
                return null; // inaccessible
            }
        }
        return null;
    }
}
