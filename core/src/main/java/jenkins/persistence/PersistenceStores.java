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
import hudson.util.PersistedList;
import hudson.util.XStream2;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import jenkins.util.ThrowingCallable;
import jenkins.util.ThrowingRunnable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Locates the active {@link PersistenceStore} and maps files under {@code JENKINS_HOME} onto its keys.
 *
 * <p>Also remembers, per key, the version this JVM last read or wrote, so that writes can detect
 * modifications made by other controllers in the meantime.
 * What happens on such a conflict depends on {@code -Djenkins.persistence.PersistenceStores.conflicts}:
 * {@code warn} (the default) logs and overwrites, {@code fail} throws {@link ConflictException}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class PersistenceStores {

    private static final Logger LOGGER = Logger.getLogger(PersistenceStores.class.getName());

    /**
     * Conflict policy: {@code warn} or {@code fail}.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ String CONFLICTS = SystemProperties.getString(PersistenceStores.class.getName() + ".conflicts", "warn");

    private static volatile PersistenceStore testingStore;
    private static volatile BlobStore testingBlobStore;
    private static volatile Jenkins resolvedFor;
    private static volatile PersistenceStore resolvedStore;
    private static volatile BlobStore resolvedBlobStore;

    private static final Map<String, Long> versions = new ConcurrentHashMap<>();
    /**
     * The top-level elements of item, user and root configuration files as this JVM last read or wrote them, by key:
     * the fields their objects may hold a value for (see {@link #clearRemovedFields}).
     */
    private static final Map<String, Set<String>> elements = new ConcurrentHashMap<>();
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
            ExtensionList<BlobStoreProvider> blobProviders = ExtensionList.lookup(BlobStoreProvider.class);
            blobProviders.size();
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
                    BlobStore blobs = null;
                    if (store != null) {
                        for (BlobStoreProvider provider : blobProviders) {
                            blobs = provider.create();
                            if (blobs != null) {
                                LOGGER.info(() -> "Using blob store " + provider.getClass().getName());
                                break;
                            }
                        }
                    }
                    resolvedStore = store;
                    resolvedBlobStore = blobs;
                    versions.clear();
                    elements.clear();
                    resolvedFor = j;
                }
                return resolvedStore;
            }
        } finally {
            RESOLVING.remove();
        }
    }

    /**
     * The active blob store, or null if none is configured.
     * Only available while a {@link #get persistence store} is active.
     */
    @CheckForNull
    public static BlobStore getBlobStore() {
        if (testingStore != null) {
            return testingBlobStore;
        }
        return get() == null ? null : resolvedBlobStore;
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
        if (key == null || !store.owns(key) || localOnly.contains(key)) {
            return null;
        }
        return new Route(store, key);
    }

    /** Keys the store owns which this controller nevertheless keeps to itself, such as its own cloud agents. */
    private static final Set<String> localOnly = ConcurrentHashMap.newKeySet();

    /**
     * Keeps a key on this controller's filesystem, or stops doing so: it is then neither read from nor written to the
     * store, although the store owns such keys. For state belonging to this controller alone, such as the agents its
     * clouds launched (another controller must not launch them too).
     */
    @Restricted(NoExternalUse.class)
    public static void keepLocal(@NonNull String key, boolean local) {
        if (local) {
            localOnly.add(key);
        } else {
            localOnly.remove(key);
        }
    }

    /** Whether a key is {@linkplain #keepLocal kept local}. */
    @Restricted(NoExternalUse.class)
    public static boolean isKeptLocal(@NonNull String key) {
        return localOnly.contains(key);
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
     * Moves the stored entries (and blobs) below one directory to another, as when an item is renamed or moved.
     * Does nothing if no store is active or the directories are outside {@code JENKINS_HOME}.
     * The {@code nextBuildNumber} entries deleted items leave behind at the destination are replaced,
     * keeping the higher number.
     *
     * <p>The source and destination are claimed first, which excludes other moves of, to, into or out of them, on any
     * controller. It does not exclude an item created at the destination while the move runs: should that item complete a
     * build before the move fails (on finding it there), its snapshots can be overwritten by the moved ones, or moved back
     * to the source with them.
     *
     * @throws ConflictException if something else is already stored at the destination, the item's configuration is no
     *     longer stored at the source (moved or deleted elsewhere), or another move holds either, or what holds them
     *     (a folder being moved), or something in the source
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
        // the destination is claimed first, so that no other move to it (on any controller) runs between checking it
        // and moving there: blobs, moved before the entries, cannot be atomic with that check. The source too, so that
        // two controllers moving the same item to two places do not split its blobs and entries between them. Moves of
        // a folder and of something in it claim different keys: each looks for the other's claim once it holds its own
        long claim = claim(store, toKey, fromKey);
        try {
            long sourceClaim = claim(store, fromKey, fromKey);
            try {
                checkNoOverlappingMove(store, fromKey, toKey);
                moveClaimed(store, fromKey, toKey);
            } finally {
                release(store, fromKey, sourceClaim);
            }
        } finally {
            release(store, toKey, claim);
        }
    }

    /**
     * Undoes a {@link #move} the caller could not complete (its local directory could not be moved): moves the entries
     * back, and leaves a copy of each {@code nextBuildNumber} moved back at {@code from}, as a deleted item leaves its
     * own. The move may have raised them to numbers a deleted item at {@code from} used, which an item made there later
     * must not use again; elsewhere, a counter left at a vacated name only makes numbers there start higher.
     */
    public static void moveBack(@NonNull File from, @NonNull File to) throws IOException {
        move(from, to);
        PersistenceStore store = get();
        String fromKey = keyFor(from);
        String toKey = keyFor(to);
        if (store == null || fromKey == null || toKey == null || fromKey.equals(toKey) || isApplyingRemoteChange()) {
            return;
        }
        List<String> counters = new ArrayList<>();
        collectBuildNumbers(store, toKey, counters);
        for (String key : counters) {
            PersistenceStore.Entry entry = store.read(key);
            String left = fromKey + key.substring(toKey.length());
            if (entry != null && store.owns(left)) {
                try {
                    store.write(left, entry.content(), PersistenceStore.ABSENT);
                } catch (ConflictException e) {
                    // something is there again, with its own numbers
                }
            }
        }
    }

    /** Collects the {@code nextBuildNumber} keys of the items below a key; build records are not looked into. */
    private static void collectBuildNumbers(PersistenceStore store, String prefix, List<String> keys) throws IOException {
        for (String child : store.children(prefix)) {
            if (child.equals("nextBuildNumber")) {
                keys.add(prefix + "/" + child);
            } else if (!child.equals("builds")) {
                collectBuildNumbers(store, prefix + "/" + child, keys);
            }
        }
    }

    private static void release(PersistenceStore store, String key, long claim) {
        try {
            store.delete(key + MOVING, claim);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not release the claim on " + key + "; it lapses", e);
        } finally {
            claiming.remove(key + MOVING);
        }
    }

    /** Suffix of the entry a move claims its destination with, next to (not below) the destination. */
    static final String MOVING = ".moving";

    /**
     * Where the store does not track {@linkplain PersistenceStore#liveOrigins membership}, a claim this old was left by
     * a move that never finished (its controller died): it may be taken over.
     */
    static final long CLAIM_LAPSE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** The claims moves in this JVM hold now. */
    private static final Set<String> claiming = ConcurrentHashMap.newKeySet();

    /**
     * Fails if another move holds a claim on what holds either key (a folder being moved), or on something below the
     * source (an item in the folder being moved). Each move looks only once it holds its own claims, so of two such
     * moves at least one sees the other.
     */
    private static void checkNoOverlappingMove(PersistenceStore store, String fromKey, String toKey) throws IOException {
        Set<String> ancestors = new TreeSet<>();
        for (String key : List.of(fromKey, toKey)) {
            for (int i = key.indexOf('/'); i > 0; i = key.indexOf('/', i + 1)) {
                ancestors.add(key.substring(0, i));
            }
        }
        for (String ancestor : ancestors) {
            checkNotClaimed(store, ancestor + MOVING);
        }
        checkNothingClaimedBelow(store, fromKey);
    }

    private static void checkNothingClaimedBelow(PersistenceStore store, String prefix) throws IOException {
        Set<String> children = store.children(prefix);
        boolean item = children.contains("config.xml");
        for (String child : children) {
            if (child.endsWith(MOVING)) {
                checkNotClaimed(store, prefix + "/" + child);
            } else if (!(item && child.equals("builds"))) { // build records hold no items
                checkNothingClaimedBelow(store, prefix + "/" + child);
            }
        }
    }

    private static void checkNotClaimed(PersistenceStore store, String claimKey) throws IOException {
        PersistenceStore.Entry held = store.read(claimKey);
        if (held != null && !lapsed(store, claimKey, held)) {
            throw new ConflictException(claimKey, PersistenceStore.ABSENT, held.version()); // another move overlaps this one
        }
    }

    /**
     * Whether a claim was left by a move that never finished (its controller died), and may be taken over: its controller
     * is gone, however long the move takes; or, where the store does not track membership, it is older than
     * {@link #CLAIM_LAPSE_MILLIS}. A claim of this controller's that no move here holds (its release failed) has lapsed too.
     */
    private static boolean lapsed(PersistenceStore store, String claimKey, PersistenceStore.Entry held) throws IOException {
        String[] parts = new String(held.content(), StandardCharsets.UTF_8).split(" ", 3);
        if (parts.length > 1) {
            if (parts[1].equals(store.getOrigin())) {
                return !claiming.contains(claimKey);
            }
            Set<String> live = store.liveOrigins();
            if (live != null) {
                return !live.contains(parts[1]);
            }
        }
        long since;
        try {
            since = Long.parseLong(parts[0]);
        } catch (NumberFormatException x) {
            since = 0;
        }
        return System.currentTimeMillis() - since >= CLAIM_LAPSE_MILLIS;
    }

    /** Claims a destination (or source) for a move, with a create-only write; returns the claim's version. */
    private static long claim(PersistenceStore store, String toKey, String fromKey) throws IOException {
        byte[] claim = (System.currentTimeMillis() + " " + store.getOrigin() + " " + fromKey).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long version = store.write(toKey + MOVING, claim, PersistenceStore.ABSENT);
                claiming.add(toKey + MOVING);
                return version;
            } catch (ConflictException e) {
                PersistenceStore.Entry held = store.read(toKey + MOVING);
                if (held == null) {
                    continue; // released meanwhile
                }
                if (!lapsed(store, toKey + MOVING, held)) {
                    throw new ConflictException(toKey, PersistenceStore.ABSENT, held.version()); // another move goes there now
                }
                LOGGER.warning(() -> "Taking over a lapsed claim on " + toKey + " (" + new String(held.content(), StandardCharsets.UTF_8) + ")");
                try {
                    store.delete(toKey + MOVING, held.version());
                } catch (ConflictException x) {
                    // taken over or released by someone else meanwhile: try again
                }
            }
        }
        throw new ConflictException(toKey, PersistenceStore.ABSENT, PersistenceStore.ANY);
    }

    private static void moveClaimed(PersistenceStore store, String fromKey, String toKey) throws IOException {
        List<String> existing = new ArrayList<>();
        collectKeys(store, toKey, existing);
        Map<String, Leftover> leftovers = leftoverBuildNumbers(store, existing);
        if (!existing.isEmpty() && leftovers.isEmpty()) {
            // checked before touching blobs, which would otherwise overwrite those of whatever is at the destination
            PersistenceStore.Entry there = store.read(existing.get(0));
            throw new ConflictException(toKey, PersistenceStore.ABSENT, there == null ? PersistenceStore.ANY : there.version());
        }
        String config = fromKey + "/config.xml";
        if (store.owns(config) && store.read(config) == null) {
            // moved or deleted elsewhere, which this controller has not applied yet: checked before touching blobs too
            throw new ConflictException(config, PersistenceStore.ANY, PersistenceStore.ABSENT);
        }
        // never go back to numbers an item previously at the destination used: the moved counters carry them, so that
        // the move publishes the final numbers (and nothing needs writing once it has happened)
        for (Map.Entry<String, Leftover> leftover : leftovers.entrySet()) {
            raiseTo(store, fromKey + leftover.getKey().substring(toKey.length()), leftover.getValue().number());
        }
        BlobStore blobs = getBlobStore();
        List<PersistenceStore.Moved> moved;
        Map<String, Leftover> deleted = new TreeMap<>();
        try {
            // blobs first: moving the entries tells other controllers, which may then look for the blobs at the new
            // location. A blob move may fail partway: what it moved is moved back below
            if (blobs != null) {
                blobs.move(fromKey, toKey);
            }
            for (Map.Entry<String, Leftover> leftover : leftovers.entrySet()) {
                // only as read: a counter advanced meanwhile (the destination recreated) makes this a conflict
                store.delete(leftover.getKey(), leftover.getValue().version());
                deleted.put(leftover.getKey(), leftover.getValue());
            }
            moved = store.move(fromKey, toKey);
            checkItemMoved(store, fromKey, toKey, moved);
        } catch (IOException | RuntimeException e) {
            for (Map.Entry<String, Leftover> leftover : deleted.entrySet()) {
                try {
                    store.write(leftover.getKey(), (leftover.getValue().number() + "\n").getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
                } catch (IOException | RuntimeException x) {
                    e.addSuppressed(x);
                }
            }
            if (blobs != null) {
                try {
                    blobs.move(toKey, fromKey);
                } catch (IOException | RuntimeException x) {
                    e.addSuppressed(x);
                }
            }
            throw e;
        }
        // this controller does not apply its own move events: placeholders cached for the old location would otherwise
        // show for an item created there later
        RunningBuilds.invalidateBelow(fromKey);
        RunningBuilds.invalidateBelow(toKey);
        // the moved entries have new versions: take them as seen where this JVM had seen the entry as it was moved, so
        // that the moved item's next save is still checked for conflicts; where it had seen an older version (changed
        // elsewhere since), keep that older one, so that the next save conflicts and merges as it would have unmoved
        for (PersistenceStore.Moved m : moved) {
            Long last = versions.get(m.from());
            Set<String> held = elements.remove(m.from());
            if (held != null) {
                elements.put(m.to(), held); // the same content
            }
            // nothing is at the old key now: a write made there from an object loaded before (a save resolving the old
            // name) conflicts with something created there elsewhere since, rather than replacing it
            seen(m.from(), PersistenceStore.ABSENT);
            if (last != null) {
                seen(m.to(), last == m.fromVersion() ? m.version() : last);
            }
        }
    }

    /** A {@code nextBuildNumber} entry left behind by a deleted item. */
    private record Leftover(int number, long version) {}

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
     * If all the given keys are {@code nextBuildNumber} entries, as left behind by deleted items, returns them.
     *
     * @return key to number and version; empty if there are no keys, or something else too
     */
    private static Map<String, Leftover> leftoverBuildNumbers(PersistenceStore store, List<String> keys) throws IOException {
        Map<String, Leftover> leftovers = new TreeMap<>();
        for (String key : keys) {
            PersistenceStore.Entry entry = key.endsWith("/nextBuildNumber") ? store.read(key) : null;
            Integer number = entry == null ? null : parseNumber(entry.content());
            if (number == null) {
                return Map.of();
            }
            leftovers.put(key, new Leftover(number, entry.version()));
        }
        return leftovers;
    }

    /** Raises a stored {@code nextBuildNumber} to at least {@code number}, by compare-and-set. */
    private static void raiseTo(PersistenceStore store, String key, int number) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            Integer current = entry == null ? null : parseNumber(entry.content());
            if (current != null && current >= number) {
                return;
            }
            try {
                store.write(key, (number + "\n").getBytes(StandardCharsets.UTF_8), entry == null ? PersistenceStore.ABSENT : entry.version());
                return;
            } catch (ConflictException e) {
                // changed meanwhile: look again
            }
        }
        throw new IOException("Could not raise " + key + " to " + number);
    }

    private static void collectKeys(PersistenceStore store, String prefix, List<String> keys) throws IOException {
        if (store.read(prefix) != null) {
            keys.add(prefix);
        }
        for (String child : store.children(prefix)) {
            collectKeys(store, prefix + "/" + child, keys);
        }
    }

    @CheckForNull
    private static Integer parseNumber(byte[] content) {
        try {
            return Integer.parseInt(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
        if (version <= PersistenceStore.ABSENT) {
            elements.remove(key);
        }
        Base base = bases.get(key);
        if (base != null && base.version() != version) {
            bases.remove(key); // not the content of this version: never merge against it
        }
    }

    /**
     * The version of a key this JVM last read or wrote, or {@link PersistenceStore#ANY} if unknown.
     */
    public static long lastSeen(@NonNull String key) {
        return versions.getOrDefault(key, PersistenceStore.ANY);
    }

    /** Content of an XML key at a version, the base of a merge. */
    private record Base(long version, byte[] content) {}

    private static final Map<String, Base> bases = new ConcurrentHashMap<>();
    private static final int MERGE_ATTEMPTS = 5;
    private static final int MAX_BASE_SIZE = 1024 * 1024;
    private static final AtomicLong merges = new AtomicLong();

    private static void remember(String key, long version, byte[] content) {
        if (key.endsWith(".xml") && content.length <= MAX_BASE_SIZE) {
            bases.put(key, new Base(version, content));
        } else {
            bases.remove(key);
        }
    }

    /**
     * Forgets the versions read or written of every key below a prefix, such as an item's directory and everything in
     * it, so that each is applied again when next changed elsewhere, whatever the version: what was read there no
     * longer stands for what this controller has (an item loaded there and then dropped, say).
     */
    @Restricted(NoExternalUse.class)
    public static void forgetUnder(@NonNull String prefix) {
        versions.keySet().removeIf(k -> k.startsWith(prefix + "/"));
        elements.keySet().removeIf(k -> k.startsWith(prefix + "/"));
        bases.keySet().removeIf(k -> k.startsWith(prefix + "/"));
    }

    /**
     * Number of conflicting writes merged (Target 11) by this JVM.
     */
    public static long getMergeCount() {
        return merges.get();
    }

    /**
     * Number of conflicting writes detected (and, under the {@code warn} policy, merged or overwritten) by this JVM.
     */
    public static long getConflictCount() {
        return conflicts.get();
    }

    /**
     * Reloads an object from its file, changed by another controller: {@linkplain #clearRemovedFields clears} the fields
     * the new version removed, then loads it. Clearing and loading each read the file: if yet another version was stored
     * between the two reads, loading took that one, as seen, so that its own change is not applied again; what it removed
     * is then cleared, and it is loaded once more.
     *
     * @param load loads the object from the file
     */
    @Restricted(NoExternalUse.class)
    public static void reload(@NonNull XmlFile file, @NonNull Object o, @NonNull ThrowingRunnable<IOException> load) throws IOException {
        String key = keyFor(file.getPath());
        for (int attempt = 1; ; attempt++) {
            long read = clearRemovedFields(file, o);
            load.run();
            if (key == null || read <= PersistenceStore.ABSENT || lastSeen(key) == read) {
                return;
            }
            if (attempt == 3) {
                LOGGER.fine(() -> key + " kept changing while being reloaded; fields it no longer has may be left");
                return;
            }
        }
    }

    /**
     * Prepares an object for reloading from its file, changed by another controller: clears the fields the new version
     * no longer has. Loading into a live object leaves alone the fields the file has no element for, and XStream writes
     * no element for a null field, so a value cleared elsewhere (a system message, an administrator address) would
     * survive here and be brought back everywhere by the next save here. Only top-level fields that the content this JVM
     * last read or wrote had, and the new version lacks, are cleared: a field the file never had keeps its default (a
     * file written by an older version, say), and nothing is cleared if that content is unknown. A collection or map is
     * emptied rather than nulled, as code expects one initialised by the constructor to be there. Call it just before
     * loading, so that what loading derives sees them cleared; or use {@link #reload}, which also covers a version
     * stored between the two reads.
     *
     * @return the version read, {@link PersistenceStore#ABSENT} if none is stored, or {@link PersistenceStore#ANY} if
     *     the file is not served by a store
     */
    @Restricted(NoExternalUse.class)
    public static long clearRemovedFields(@NonNull XmlFile file, @NonNull Object o) throws IOException {
        Route route = route(file.getPath());
        if (route == null) {
            return PersistenceStore.ANY;
        }
        PersistenceStore.Entry entry = route.store().read(route.key()); // not taken as seen: loading reads it again
        if (entry == null) {
            return PersistenceStore.ABSENT;
        }
        Set<String> kept = topLevelElements(entry.content());
        Set<String> had = elements.get(route.key());
        XStream xs = file.getXStream();
        for (String element : had == null ? Set.<String>of() : had) {
            if (kept.contains(element)) {
                continue;
            }
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
                    c.clear();
                } else if (value instanceof Map<?, ?> map) {
                    map.clear();
                } else {
                    f.set(o, null);
                }
                LOGGER.fine(() -> "Cleared " + f + ", removed elsewhere from " + file);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not clear " + f, e);
            }
        }
        elements.put(route.key(), kept); // what it may hold once cleared; loading adds what it reads then
        return entry.version();
    }

    /**
     * Records the top-level elements of content an object of an item, user or root configuration file was just loaded
     * from (added to those it held, which loading leaves alone) or saved as (replacing them).
     */
    private static void held(String key, byte[] content, boolean loaded) {
        if (!key.endsWith("/config.xml") && (key.indexOf('/') >= 0 || !key.endsWith(".xml"))) {
            return; // not one cleared on reloading (build records, say)
        }
        try {
            Set<String> names = topLevelElements(content);
            if (loaded) {
                elements.merge(key, names, (a, b) -> {
                    Set<String> both = new TreeSet<>(a);
                    both.addAll(b);
                    return both;
                });
            } else {
                elements.put(key, names);
            }
        } catch (RuntimeException e) {
            elements.remove(key); // not XML: nothing cleared on reloading it
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
        setForTesting(store, null);
    }

    @Restricted(NoExternalUse.class)
    public static void setForTesting(@CheckForNull PersistenceStore store, @CheckForNull BlobStore blobs) {
        testingStore = store;
        testingBlobStore = blobs;
        versions.clear();
        elements.clear();
        bases.clear();
        conflicts.set(0);
        merges.set(0);
    }

    /**
     * A file served by the store, with version tracking and the conflict policy applied.
     */
    public record Route(@NonNull PersistenceStore store, @NonNull String key) {

        @CheckForNull
        public PersistenceStore.Entry read() throws IOException {
            PersistenceStore.Entry entry = store.read(key);
            seen(key, entry == null ? PersistenceStore.ABSENT : entry.version());
            if (entry != null) {
                held(key, entry.content(), true);
                remember(key, entry.version(), entry.content());
            }
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
            long expected = createOnly ? PersistenceStore.ABSENT : lastSeen(key);
            long version;
            try {
                version = store.write(key, content, expected);
            } catch (ConflictException e) {
                conflicts.incrementAndGet();
                if (createOnly || "fail".equals(CONFLICTS)) {
                    throw e;
                }
                Long merged = mergeAndWrite(content, expected);
                if (merged != null) {
                    version = merged;
                } else {
                    LOGGER.log(Level.WARNING, e.getMessage() + "; overwriting (last writer wins)");
                    version = store.write(key, content, PersistenceStore.ANY);
                }
            }
            if (creation != null) {
                creation.written = version;
            }
            seen(key, version);
            held(key, content, false);
            remember(key, version, content);
            return version;
        }

        /**
         * Merges what this JVM wrote with what was written meanwhile, against what it had read, and writes the result.
         * The object in memory is reloaded from it afterwards, so that it also has the other change.
         *
         * @return the new version, or null if the contents cannot be merged
         */
        @CheckForNull
        private Long mergeAndWrite(byte[] mine, long expected) throws IOException {
            Base base = bases.get(key);
            if (base == null || base.version() != expected || !key.endsWith(".xml")) {
                return null;
            }
            for (int attempt = 0; attempt < MERGE_ATTEMPTS; attempt++) {
                PersistenceStore.Entry theirs = store.read(key);
                if (theirs == null) {
                    return null; // deleted meanwhile
                }
                XmlMerge.Result result = XmlMerge.merge(base.content(), mine, theirs.content());
                if (result == null) {
                    return null;
                }
                try {
                    long version = store.write(key, result.merged(), theirs.version());
                    merges.incrementAndGet();
                    if (result.conflicts() > 0) {
                        LOGGER.warning(() -> key + " was changed meanwhile; merged, but " + result.conflicts()
                                + " part(s) changed on both sides keep this controller's version");
                    } else {
                        LOGGER.info(() -> key + " was changed meanwhile; merged both changes");
                    }
                    remember(key, version, result.merged());
                    ItemChangeApplier.reload(key); // this JVM's object lacks the other change
                    return version;
                } catch (ConflictException again) {
                    LOGGER.fine(() -> key + " changed again while merging; retrying");
                }
            }
            return null;
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
