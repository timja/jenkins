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
import hudson.model.AbstractItem;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Items enumerated, created, renamed, moved and deleted through the store: what this JVM holds of the keys below
 * them, and the stored entries moved or deleted with them.
 */
@Restricted(NoExternalUse.class)
public final class StoredItems {

    private static final Logger LOGGER = Logger.getLogger(StoredItems.class.getName());

    /** Directories below an item which hold other items, loaded by their parent rather than as part of it. */
    static final Set<String> ITEM_CONTAINERS = Set.of("jobs", "branches");

    private StoredItems() {}

    /**
     * Creates the local directories of the configuration files stored below an item which are not items themselves
     * (such as a plugin's sub-items, configured in the item), so that code listing those directories finds them on a
     * controller which did not create them. Build directories and directories of child items are not looked into.
     */
    @Restricted(NoExternalUse.class)
    public static void makeStoredDirectories(@NonNull File itemDir) {
        PersistenceStore store = PersistenceStores.get();
        String key = store == null ? null : PersistenceStores.keyFor(itemDir);
        if (key == null) {
            return;
        }
        try {
            walk(store, key, true, 0);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not list what is stored below " + key, e);
        }
    }

    private static void walk(PersistenceStore store, String prefix, boolean item, int depth) throws IOException {
        if (depth > 12) {
            return;
        }
        for (String name : store.children(prefix)) {
            if (!PersistenceStores.isSafeName(name) || item && (ITEM_CONTAINERS.contains(name) || name.equals("builds"))) {
                continue;
            }
            if (name.equals("config.xml")) {
                if (!item && StoreRouting.isRouted(prefix + "/config.xml")) {
                    Files.createDirectories(PersistenceStores.fileFor(prefix).toPath());
                }
            } else {
                walk(store, prefix + "/" + name, false, depth + 1);
            }
        }
    }

    /**
     * Deletes the routed entries stored below an item's directory, as when the item is deleted here. Does nothing while
     * {@linkplain PersistenceStores#applyingRemoteChange applying a remote change}, or if no store is active.
     */
    @Restricted(NoExternalUse.class)
    public static void deleteUnder(@NonNull File dir) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        String key = store == null || PersistenceStores.isApplyingRemoteChange() ? null : PersistenceStores.keyFor(dir);
        if (key == null) {
            return;
        }
        deleteRouted(store, key);
    }

    private static void deleteRouted(PersistenceStore store, String prefix) throws IOException {
        for (String name : store.children(prefix)) {
            String key = prefix + "/" + name;
            if (StoreRouting.isRouted(key) && store.read(key) != null) {
                new Route(store, key).delete();
            }
            deleteRouted(store, key);
        }
    }

    /**
     * Moves the stored entries below one item directory to another, as when an item is renamed or moved: one atomic
     * {@link PersistenceStore#move} with preconditions (the source configuration at the version held here, the
     * destination parent's configuration present, nothing at the destination). Does nothing if no store is active.
     *
     * @throws ConflictException if a precondition fails
     */
    @Restricted(NoExternalUse.class)
    public static void move(@NonNull File from, @NonNull File to) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return;
        }
        String fromKey = PersistenceStores.keyFor(from);
        String toKey = PersistenceStores.keyFor(to);
        if (fromKey == null || toKey == null || fromKey.equals(toKey) || PersistenceStores.isApplyingRemoteChange()) {
            return;
        }
        if (toKey.startsWith(fromKey + "/") || fromKey.startsWith(toKey + "/")) {
            // the entries would be moved below themselves (or over what holds them), and could not be moved back
            throw new IllegalArgumentException("Cannot move " + fromKey + " to " + toKey);
        }
        List<PersistenceStore.Precondition> preconditions = new ArrayList<>();
        String config = fromKey + "/config.xml";
        if (StoreRouting.isRouted(config)) {
            // as held here: renamed, moved, deleted or changed elsewhere meanwhile (not applied here yet), it fails
            long held = StoredVersions.lastSeen(config);
            preconditions.add(new PersistenceStore.Precondition(config, held > PersistenceStore.ABSENT ? held : PersistenceStore.PRESENT));
        }
        int slash = toKey.lastIndexOf('/');
        int parentEnd = slash < 0 ? -1 : toKey.lastIndexOf('/', slash - 1);
        if (parentEnd > 0) {
            String parentConfig = toKey.substring(0, parentEnd) + "/config.xml";
            if (StoreRouting.isRouted(parentConfig)) {
                // the destination folder still there: two overlapping moves serialise, the second failing here
                preconditions.add(new PersistenceStore.Precondition(parentConfig, PersistenceStore.PRESENT));
            }
        }
        preconditions.add(new PersistenceStore.Precondition(toKey + "/config.xml", PersistenceStore.ABSENT));
        List<PersistenceStore.Moved> moved = store.move(fromKey, toKey, preconditions);
        // the moved entries have new versions: taken as seen where this JVM had seen the entry as it was moved, so that
        // the moved item's next save is still checked; where it had seen an older version (changed elsewhere since),
        // that older one is kept, so that the next save conflicts as it would have unmoved (with the base of a merge)
        for (PersistenceStore.Moved m : moved) {
            Long last = StoredVersions.versions.get(m.from());
            StoredVersions.Base base = StoredVersions.bases.remove(m.from());
            // nothing is at the old key now: until the item is in its new place here (movedAway), a save resolving to the
            // old key (a child of a folder being moved, say) is refused rather than storing it there again
            StoredVersions.versions.put(m.from(), GONE);
            StoredVersions.bases.remove(m.to());
            MaterializedCopies.forget(m.from());
            if (last != null) {
                long version = last == m.fromVersion() ? m.version() : last;
                StoredVersions.seen(m.to(), version);
                if (base != null && base.version() == last) {
                    StoredVersions.bases.put(m.to(), new StoredVersions.Base(version, base.content()));
                }
            }
        }
    }

    /**
     * Records that an item {@linkplain #move moved} from a directory is now in its new place here (or back in its old
     * one, the move undone): what it left behind is absent, a later write there only creating it, as by a new item of
     * that name. Until then saves resolving to keys below it are refused.
     */
    @Restricted(NoExternalUse.class)
    public static void movedAway(@NonNull File from) {
        String prefix = PersistenceStores.get() == null ? null : PersistenceStores.keyFor(from);
        if (prefix != null) {
            StoredVersions.versions.replaceAll((k, v) -> v == GONE && k.startsWith(prefix + "/") ? PersistenceStore.ABSENT : v);
        }
    }

    /**
     * The version this JVM holds of a key while an item is being moved away from it: a write there is refused (see
     * {@link #movedAway}).
     */
    static final long GONE = -3;

    /**
     * Records that the item whose configuration a key is is being moved away from it, as a move made elsewhere is
     * applied: until it is in its new place ({@link #movedAway}), a save or deletion of it there is refused.
     */
    @Restricted(NoExternalUse.class)
    public static void movingAway(@NonNull String key) {
        StoredVersions.versions.put(key, GONE);
        StoredVersions.bases.remove(key);
    }

    /** Items deleted here: a late save of one (a build completing, say) is refused rather than storing it again. */
    private static final Map<Object, Boolean> deleted = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Records that an item was deleted (here, or as deleted elsewhere): a save of it after that is refused, rather than
     * storing it again for every controller. Does nothing if no store is active.
     */
    @Restricted(NoExternalUse.class)
    public static void deleted(@NonNull AbstractItem item) {
        if (PersistenceStores.get() != null) {
            deleted.put(item, Boolean.TRUE);
        }
    }

    /**
     * Refuses the write of an object which may not be stored any more: an item {@linkplain #deleted deleted}.
     *
     * @throws ConflictException if it is one
     */
    @Restricted(NoExternalUse.class)
    public static void checkStorable(@NonNull Object o, @NonNull String key) throws ConflictException {
        if (o instanceof AbstractItem && deleted.containsKey(o)) {
            LOGGER.warning(() -> "Not saving " + o + " to " + key + ": it was deleted");
            throw new Gone(key);
        }
    }

    /** A write refused because what it writes was moved or deleted. */
    static final class Gone extends ConflictException {
        Gone(String key) {
            super(key, PersistenceStore.ABSENT, PersistenceStore.ABSENT);
        }

        @Override
        public String getMessage() {
            return getKey() + " was moved or deleted here: not stored again from what was loaded from it before";
        }
    }

    /** Files being created afresh, by key (see {@link #creating}). */
    static final Map<String, Creation> creations = new ConcurrentHashMap<>();

    /**
     * Starts creating a file afresh, such as the configuration of a new item.
     * Until the creation is closed, the first write of the file expects it to be absent from the store, and is refused
     * with a {@link ConflictException} if it is not, never merged: something stored under that name by another
     * controller, or not loaded by this one, is not replaced.
     *
     * @return the creation, to close once done; null if the file is not served by the store
     */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static Creation creating(@NonNull File file) {
        Route route = StoreRouting.route(file);
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
        volatile long written = PersistenceStore.ABSENT;
        /** The thread creating the file while it is {@linkplain #hold held}, else null. */
        private volatile Thread holder;
        /** What was written while held, not stored yet. */
        private volatile byte[] held;

        private Creation(Route route) {
            this.route = route;
        }

        /**
         * Keeps what is written from the store until {@link #commit}: the file then exists for the creating thread
         * only, as written, and nothing of it reaches the store, its events or other controllers. For an item made from
         * a configuration that is not its own yet (posted, or copied from another item), so that it is stored only once
         * loaded, with an identity of its own.
         */
        public void hold() {
            holder = Thread.currentThread();
        }

        /** What a read of the file gets while held: what was written, for the creating thread; null otherwise. */
        @CheckForNull
        PersistenceStore.Entry heldEntry() {
            byte[] content = held;
            return content != null && holder == Thread.currentThread() ? new PersistenceStore.Entry(content, PersistenceStore.ABSENT) : null;
        }

        /** Keeps a write while held; false if not held. */
        boolean keep(@NonNull byte[] content) {
            if (holder == null) {
                return false;
            }
            held = content.clone();
            return true;
        }

        /**
         * Stores what was written while held, only creating it, and stops holding.
         *
         * @throws ConflictException if something is stored under the key: it is not replaced
         * @throws IllegalStateException if nothing was written
         */
        public void commit() throws IOException {
            byte[] content = held;
            if (content == null) {
                throw new IllegalStateException("nothing written to " + route.key());
            }
            holder = null;
            held = null;
            route.write(content, null);
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
                StoredVersions.seen(route.key(), PersistenceStore.ABSENT);
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
}
