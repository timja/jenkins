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
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.util.XStream2;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import jenkins.util.ThrowingCallable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Locates the active {@link PersistenceStore} and maps files under {@code JENKINS_HOME} onto its keys.
 *
 * <p>Also remembers, per key, the version this JVM last read or wrote, so that writes can detect
 * modifications made by other controllers in the meantime.
 * A save conflicting with such a modification is refused ({@link ConflictException}): it never overwrites it. A save of
 * a key this JVM has not read (it failed to, say) only creates it. A deletion conflicting with one is logged and done,
 * unless {@code -Djenkins.persistence.PersistenceStores.conflicts=fail}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class PersistenceStores {

    private static final Logger LOGGER = Logger.getLogger(PersistenceStores.class.getName());

    /**
     * Conflict policy: {@code refuse} (the default) or {@code fail}, which also refuses conflicting deletions.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ String CONFLICTS = SystemProperties.getString(PersistenceStores.class.getName() + ".conflicts", "refuse");

    private static volatile PersistenceStore testingStore;
    private static volatile Jenkins resolvedFor;
    private static volatile PersistenceStore resolvedStore;

    private static final Map<String, Long> versions = new ConcurrentHashMap<>();
    private static final AtomicLong conflicts = new AtomicLong();

    private PersistenceStores() {}

    /**
     * The active store, or null if none is configured (or extensions are not yet available).
     */
    @CheckForNull
    public static PersistenceStore get() {
        PersistenceStore testing = testingStore;
        if (testing != null) {
            return testing;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        if (resolvedFor == j) {
            return resolvedStore;
        }
        if (j.getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) < 0 || RESOLVING.get() != null) {
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            // Load the extension list before taking our own lock: loading extensions takes a global lock,
            // and threads holding it (instantiating extensions) may themselves read XmlFiles and get here.
            ExtensionList<PersistenceStoreProvider> providers = ExtensionList.lookup(PersistenceStoreProvider.class);
            providers.size();
            synchronized (PersistenceStores.class) {
                if (resolvedFor != j) {
                    PersistenceStore store = null;
                    for (PersistenceStoreProvider provider : providers) {
                        store = provider.create();
                        if (store != null) {
                            LOGGER.info(() -> "Using persistence store " + provider.getClass().getName());
                            break;
                        }
                    }
                    resolvedStore = store;
                    versions.clear();
                    resolvedFor = j;
                }
                return resolvedStore;
            }
        } finally {
            RESOLVING.remove();
        }
    }

    /** Set while this thread applies a change made by another controller. */
    private static final ThreadLocal<Boolean> APPLYING_REMOTE = new ThreadLocal<>();

    /**
     * Runs code which brings this controller up to date with a change already made in the store by another controller.
     * Within it, modifications of owned keys are not written back to the store (they are already there);
     * only local state such as directories and in-memory objects is updated.
     */
    public static <V, T extends Throwable> V applyingRemoteChange(ThrowingCallable<V, T> body) throws T {
        Boolean old = APPLYING_REMOTE.get();
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            return body.call();
        } finally {
            if (old == null) {
                APPLYING_REMOTE.remove();
            }
        }
    }

    /**
     * Whether this thread is {@linkplain #applyingRemoteChange applying a remote change}.
     */
    public static boolean isApplyingRemoteChange() {
        return APPLYING_REMOTE.get() != null;
    }

    /** Set while this thread resolves the store, so that providers reading files do not recurse. */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    /**
     * Finds out whether a file is served by the active store.
     *
     * @return a route to the key in the store, or null if the file lives on the local filesystem
     */
    @CheckForNull
    public static Route route(@NonNull File file) {
        PersistenceStore store = get();
        if (store == null) {
            return null;
        }
        String key = keyFor(file);
        if (key == null || !store.owns(key)) {
            return null;
        }
        return new Route(store, key);
    }

    /**
     * Computes the key for a file under {@link Jenkins#getRootDir}.
     *
     * @return the key, or null if the file is outside {@code JENKINS_HOME}
     */
    @CheckForNull
    public static String keyFor(@NonNull File file) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Path root = j.getRootDir().toPath().toAbsolutePath().normalize();
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            return null;
        }
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /**
     * Whether one segment of a store key can name a file in the directory of the key's parent. Keys come from other
     * controllers, or from anything else writing to the store: a segment such as {@code ..} must never become a path here.
     */
    @Restricted(NoExternalUse.class)
    public static boolean isSafeName(@CheckForNull String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    /** Whether every segment of a store key {@linkplain #isSafeName can name a file}. */
    @Restricted(NoExternalUse.class)
    public static boolean isSafeKey(@NonNull String key) {
        for (String segment : key.split("/", -1)) {
            if (!isSafeName(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Names of the items stored below a directory which holds one subdirectory per item,
     * such as {@code JENKINS_HOME/jobs} or a folder's {@code jobs} directory.
     * Use in addition to listing the directory itself: on a fresh controller, items may exist only in the store.
     *
     * @return names whose {@code config.xml} the store holds; empty if no store is active
     */
    @NonNull
    public static Set<String> storedItemNames(@NonNull File itemsDir) throws IOException {
        return storedItemNames(itemsDir, Set.of());
    }

    /**
     * As {@link #storedItemNames(File)}, but leaving out names the caller has already (listed locally, say): those
     * are not read, which would take a round trip each for nothing.
     */
    @NonNull
    public static Set<String> storedItemNames(@NonNull File itemsDir, @NonNull Set<String> skip) throws IOException {
        PersistenceStore store = get();
        String parent = store == null ? null : keyFor(itemsDir);
        Set<String> names = new TreeSet<>();
        if (parent != null) {
            for (String name : store.children(parent)) {
                if (skip.contains(name)) {
                    continue;
                }
                if (!isSafeName(name)) {
                    // whatever else can write to the store wrote it: never a directory here (".." is the parent's)
                    LOGGER.warning(() -> "Ignoring " + parent + "/" + name + " in the persistence store: not a name");
                    continue;
                }
                String config = parent + "/" + name + "/config.xml";
                // children() also names directories with only deeper keys left (e.g. a deleted item's sub-items): an
                // item is there only if its configuration is
                if (!store.owns(config)) {
                    continue;
                }
                try {
                    if (store.read(config) != null) {
                        names.add(name);
                    }
                } catch (IOException | RuntimeException e) {
                    // one entry which cannot be read does not hide the others: loading it reports its own failure
                    LOGGER.log(Level.WARNING, "Failed to read " + config + "; listing it as an item anyway", e);
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Moves the stored entries below one directory to another, as when an item is renamed or moved.
     * Does nothing if no store is active or the directories are outside {@code JENKINS_HOME}.
     *
     * @throws ConflictException if something is already stored at the destination, or the item's configuration is no
     *     longer stored at the source (moved or deleted elsewhere)
     */
    public static void move(@NonNull File from, @NonNull File to) throws IOException {
        PersistenceStore store = get();
        if (store == null) {
            return;
        }
        String fromKey = keyFor(from);
        String toKey = keyFor(to);
        if (fromKey == null || toKey == null || fromKey.equals(toKey) || isApplyingRemoteChange()) {
            return;
        }
        if (toKey.startsWith(fromKey + "/") || fromKey.startsWith(toKey + "/")) {
            // the entries would be moved below themselves (or over what holds them), and could not be moved back
            throw new IllegalArgumentException("Cannot move " + fromKey + " to " + toKey);
        }
        List<PersistenceStore.Moved> moved = store.move(fromKey, toKey);
        checkItemMoved(store, fromKey, toKey, moved);
        // the moved entries have new versions: take them as seen where this JVM had seen the entry as it was moved, so
        // that the moved item's next save is still checked for conflicts; where it had seen an older version (changed
        // elsewhere since), keep that older one, so that the next save conflicts and merges as it would have unmoved
        for (PersistenceStore.Moved m : moved) {
            Long last = versions.get(m.from());
            // nothing is at the old key now: a write made there from an object loaded before (a save resolving the old
            // name) conflicts with something created there elsewhere since, rather than replacing it
            seen(m.from(), PersistenceStore.ABSENT);
            if (last != null) {
                seen(m.to(), last == m.fromVersion() ? m.version() : last);
            }
        }
    }

    /**
     * Fails a move that did not move the item's configuration: the item is no longer stored there, as it was renamed,
     * moved or deleted on another controller, which this one has not applied yet. Going on would store it again under the
     * new name, next to where it went. Whatever else was moved is moved back.
     */
    private static void checkItemMoved(PersistenceStore store, String fromKey, String toKey, List<PersistenceStore.Moved> moved)
            throws IOException {
        String config = fromKey + "/config.xml";
        if (!store.owns(config) || moved.stream().anyMatch(m -> m.from().equals(config))) {
            return;
        }
        ConflictException e = new ConflictException(config, PersistenceStore.ANY, PersistenceStore.ABSENT);
        if (!moved.isEmpty()) {
            try {
                store.move(toKey, fromKey); // what was left at the old key
            } catch (IOException | RuntimeException x) {
                e.addSuppressed(x);
            }
        }
        throw e;
    }

    /**
     * The local file corresponding to a key.
     */
    @NonNull
    public static File fileFor(@NonNull String key) {
        return new File(Jenkins.get().getRootDir(), key.replace('/', File.separatorChar));
    }

    /**
     * Records the version of a key as seen by this JVM, for example after applying a change made elsewhere.
     * {@link PersistenceStore#ABSENT} is a version too: the next write then expects the key to still be absent, so that
     * another controller creating it meanwhile is a conflict.
     */
    public static void seen(@NonNull String key, long version) {
        if (version >= 0) {
            versions.put(key, version);
        } else {
            versions.remove(key);
        }
    }

    /**
     * The version of a key this JVM last read or wrote, or {@link PersistenceStore#ANY} if unknown.
     */
    public static long lastSeen(@NonNull String key) {
        return versions.getOrDefault(key, PersistenceStore.ANY);
    }

    /**
     * Forgets the versions read or written of every key below a prefix, such as an item's directory and everything in
     * it, so that each is applied again when next changed elsewhere, whatever the version: what was read there no
     * longer stands for what this controller has (an item loaded there and then dropped, say).
     */
    @Restricted(NoExternalUse.class)
    public static void forgetUnder(@NonNull String prefix) {
        versions.keySet().removeIf(k -> k.startsWith(prefix + "/"));
    }

    /**
     * Number of conflicting writes detected (and refused) by this JVM.
     */
    public static long getConflictCount() {
        return conflicts.get();
    }

    /**
     * Prepares an object for reloading from its file, changed by another controller: nulls the fields the new version
     * no longer has. Loading into a live object leaves alone the fields the file has no element for, and XStream writes
     * no element for a null field, so a value cleared elsewhere (a system message, an administrator address) would
     * survive here and be brought back everywhere by the next save here. Only top-level fields this object serialises
     * now (as last saved or reloaded, unless changed here since) and the new version lacks are nulled: a field the file
     * never had keeps its default. Call it just before loading, so that what loading derives sees them cleared.
     */
    @Restricted(NoExternalUse.class)
    public static void clearRemovedFields(@NonNull XmlFile file, @NonNull Object o) throws IOException {
        if (!file.exists()) {
            return;
        }
        XStream xs = file.getXStream();
        Set<String> kept = topLevelElements(file.readRawBytes());
        Set<String> removed = topLevelElements(xs.toXML(o).getBytes(StandardCharsets.UTF_8));
        removed.removeAll(kept);
        for (String element : removed) {
            Field f = fieldFor(xs, o.getClass(), element);
            int m = f == null ? 0 : f.getModifiers();
            if (f == null || f.getType().isPrimitive() || Modifier.isStatic(m) || Modifier.isFinal(m) || Modifier.isTransient(m)) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(o, null);
                LOGGER.fine(() -> "Cleared " + f + ", removed elsewhere from " + file);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not clear " + f, e);
            }
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
                // declared further up, or not a field (an implicit collection's item)
            }
        }
        return null;
    }

    /** Files being created afresh, by key (see {@link #creating}). */
    private static final Map<String, Creation> creations = new ConcurrentHashMap<>();

    /**
     * Starts creating a file afresh, such as the configuration of a new item.
     * Until the creation is closed, the first write of the file expects it to be absent from the store, and throws a
     * {@link ConflictException} if it is not, whatever the conflict policy: something stored under that name by another
     * controller, or not loaded by this one, is not replaced.
     *
     * @return the creation, to close once done; null if the file is not served by the store
     */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static Creation creating(@NonNull File file) {
        Route route = route(file);
        if (route == null) {
            return null;
        }
        Creation creation = new Creation(route);
        creations.put(route.key(), creation);
        return creation;
    }

    /**
     * A file being created afresh (see {@link #creating}).
     */
    @Restricted(NoExternalUse.class)
    public static final class Creation implements AutoCloseable {
        private final Route route;
        /** The version this creation last wrote, or {@link PersistenceStore#ABSENT} while it wrote nothing. */
        private volatile long written = PersistenceStore.ABSENT;

        private Creation(Route route) {
            this.route = route;
        }

        /**
         * Deletes what this creation wrote, if anything, as of the version it wrote: never something it did not write,
         * nor a change made to it elsewhere since.
         */
        public void undo() throws IOException {
            long version = written;
            if (version == PersistenceStore.ABSENT) {
                return;
            }
            try {
                route.store().delete(route.key(), version);
                seen(route.key(), PersistenceStore.ABSENT);
            } catch (ConflictException e) {
                LOGGER.log(Level.WARNING, e.getMessage() + "; not deleting what was changed elsewhere since");
            }
        }

        /** Whether this creation wrote nothing (yet), so that nothing stored under its key is its own. */
        public boolean wroteNothing() {
            return written == PersistenceStore.ABSENT;
        }

        @Override
        public void close() {
            creations.remove(route.key(), this);
        }
    }

    @Restricted(NoExternalUse.class)
    public static void setForTesting(@CheckForNull PersistenceStore store) {
        testingStore = store;
        versions.clear();
        conflicts.set(0);
    }

    /**
     * A file served by the store, with version tracking and the conflict policy applied.
     */
    public record Route(@NonNull PersistenceStore store, @NonNull String key) {

        @CheckForNull
        public PersistenceStore.Entry read() throws IOException {
            PersistenceStore.Entry entry = store.read(key);
            seen(key, entry == null ? PersistenceStore.ABSENT : entry.version());
            return entry;
        }

        public boolean exists() throws IOException {
            return store.read(key) != null;
        }

        public long write(@NonNull byte[] content) throws IOException {
            if (isApplyingRemoteChange()) {
                return lastSeen(key);
            }
            Creation creation = creations.get(key);
            boolean createOnly = creation != null && creation.written == PersistenceStore.ABSENT;
            // a key never read here (its read failed, say: the object holds defaults, not what is stored) only creates
            long expected = createOnly ? PersistenceStore.ABSENT : versions.getOrDefault(key, PersistenceStore.ABSENT);
            long version;
            try {
                version = store.write(key, content, expected);
            } catch (ConflictException e) {
                conflicts.incrementAndGet();
                LOGGER.log(Level.WARNING, e.getMessage() + "; not saved, so as not to overwrite that change");
                throw e;
            }
            if (creation != null) {
                creation.written = version;
            }
            seen(key, version);
            return version;
        }

        public void delete() throws IOException {
            if (isApplyingRemoteChange()) {
                return;
            }
            try {
                store.delete(key, lastSeen(key));
            } catch (ConflictException e) {
                conflicts.incrementAndGet();
                if ("fail".equals(CONFLICTS)) {
                    throw e;
                }
                LOGGER.log(Level.WARNING, e.getMessage() + "; deleting anyway");
                store.delete(key, PersistenceStore.ANY);
            }
            // absent is a version: a write made from an object loaded before (a late save, a new item of that name)
            // then conflicts with the key having been created again elsewhere meanwhile, rather than replacing it
            seen(key, PersistenceStore.ABSENT);
        }
    }
}
