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
import hudson.ExtensionList;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.DirectlyModifiableTopLevelItemGroup;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore.ChangeEvent;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Brings this controller up to date with changes other controllers make in the {@link PersistenceStore}: items
 * created, reconfigured, renamed, moved or deleted elsewhere; and, through the {@link RemoteChangeHandler}s, changes to
 * keys core does not route.
 *
 * <p>Subscribes before items are loaded, so nothing made in the meantime is missed, but only applies changes once
 * loading is done; changes already reflected in what was loaded are recognised by their version and skipped.
 * Changes are applied one at a time, in order, as {@link ACL#SYSTEM2}, and within
 * {@link PersistenceStores#applyingRemoteChange} so that they are not written back to the store. A change that fails to
 * apply (the store unreachable while reading it, say) is retried, with a growing delay, until it applies or a newer
 * change of its key does.
 *
 * <p>Every {@link #CATCH_UP_MINUTES} minutes, the versions stored are compared with those this controller holds, and
 * any difference applied as a change made elsewhere would be: a change whose event never arrived, or one that could
 * not be applied, is not kept from this controller until its key changes again or it restarts. One run at a time, a
 * listing of each item group and a read of each item's configuration; nothing with no store.
 */
@Restricted(NoExternalUse.class)
public final class ItemChangeApplier {

    private static final Logger LOGGER = Logger.getLogger(ItemChangeApplier.class.getName());

    private static ItemChangeApplier instance;

    private final Jenkins jenkins;
    private final PersistenceStore store;
    private final BlockingQueue<ChangeEvent> queue = new LinkedBlockingQueue<>();
    private final AutoCloseable subscription;
    /** Changes to items whose parent does not exist yet, by parent full name. Only touched by the worker. */
    private final Map<String, List<ChangeEvent>> parked = new HashMap<>();
    private final AtomicLong applied = new AtomicLong();
    /**
     * The version of the last deletion applied, per key, until it is written again: a delivery repeated is skipped.
     * Only the worker uses it; the oldest are forgotten past a bound.
     */
    private final Map<String, Long> deletions = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 10_000;
        }
    };
    /** Changes received and not yet fully processed. */
    private final AtomicLong pending = new AtomicLong();
    /**
     * The newest version received of each key with a change not yet applied: a change retried after a failure is
     * dropped once a newer one of its key arrived (which applies the key's state as of then), so that it never undoes
     * that one, as a delete retried after the key was made again would.
     */
    private final Map<String, Long> newest = new ConcurrentHashMap<>();
    /**
     * Item deletions that failed to apply and are to be retried, by key: a write of the key made since (the item made
     * again) applies the deletion first. Only touched by the worker.
     */
    private final Map<String, ChangeEvent> failedDeletions = new HashMap<>();
    /** How many times a change that failed to apply has been retried, by key. */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 8;
    private Thread worker;

    /** How often stored versions are compared with those held here, in minutes (see the class description). */
    static final long CATCH_UP_MINUTES = SystemProperties.getLong(ItemChangeApplier.class.getName() + ".catchUpMinutes", 15L);
    /** The origin of the changes the catch-up finds: not this controller's, nor any other's. */
    static final String CATCH_UP = "catch-up";
    /** Set while a catch-up runs: runs are not stacked. */
    private final AtomicBoolean catchingUp = new AtomicBoolean();
    /**
     * The version of the last difference the catch-up found, by key: one which nothing here applies (an item which does
     * not load here, say) is not queued again each time, until the stored version changes.
     */
    private final Map<String, Long> caughtUp = new ConcurrentHashMap<>();
    private ScheduledFuture<?> catchUps;

    private ItemChangeApplier(Jenkins jenkins, PersistenceStore store) {
        this.jenkins = jenkins;
        this.store = store;
        this.subscription = store.subscribe(e -> {
            newest.merge(e.key(), e.version(), Math::max);
            pending.incrementAndGet();
            queue.add(e);
        });
    }

    // before global configuration is loaded, which item loading (requiring SYSTEM_CONFIG_ADAPTED) follows: an item read
    // before subscribing could miss a change made in between, never delivered and newer than what was read
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static synchronized void subscribe() {
        stop();
        PersistenceStore store = PersistenceStores.get();
        if (store != null) {
            instance = new ItemChangeApplier(Jenkins.get(), store);
            LOGGER.fine("Subscribed to changes made elsewhere");
        }
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static synchronized void start() {
        if (instance != null && instance.worker == null) {
            ItemChangeApplier a = instance;
            a.worker = new Thread(a::run, "Persistence store change applier");
            a.worker.setDaemon(true);
            a.worker.start();
            long period = TimeUnit.MINUTES.toMillis(CATCH_UP_MINUTES);
            if (period > 0) {
                // spread out: controllers started together do not all list the store at once
                long first = period + ThreadLocalRandom.current().nextLong(period / 4 + 1);
                a.catchUps = Timer.get().scheduleWithFixedDelay(a::catchUpQuietly, first, period, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Compares stored versions with those held here now, for tests; see the class description. */
    static void catchUpNow() {
        ItemChangeApplier a;
        synchronized (ItemChangeApplier.class) {
            a = instance;
        }
        if (a != null) {
            a.catchUpQuietly();
        }
    }

    @Terminator
    public static synchronized void stop() {
        if (instance != null) {
            try {
                instance.subscription.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, null, e);
            }
            if (instance.worker != null) {
                instance.worker.interrupt();
            }
            if (instance.catchUps != null) {
                instance.catchUps.cancel(false);
            }
            instance = null;
        }
    }

    /** Number of changes made elsewhere that this controller has applied, for diagnostics and tests. */
    public static synchronized long getAppliedCount() {
        return instance == null ? 0 : instance.applied.get();
    }

    /** Whether this controller listens to changes made elsewhere, for tests. */
    static synchronized boolean isSubscribed() {
        return instance != null;
    }

    /** Whether every change received so far has been processed, for tests. */
    public static boolean isIdle() {
        ItemChangeApplier a;
        synchronized (ItemChangeApplier.class) {
            a = instance;
        }
        return (a == null || a.pending.get() == 0 && a.worker != null) && StoredReloads.isIdle();
    }

    private void run() {
        while (Jenkins.getInstanceOrNull() == jenkins) {
            ChangeEvent e;
            try {
                e = queue.take();
            } catch (InterruptedException x) {
                return;
            }
            try {
                Long latest = newest.get(e.key());
                // except an item's deletion: the item made again since is another one, which must not take over the
                // deleted one's builds and history (deleted() skips one older than what this controller has read);
                // nor a move: a later write of its destination does not move the item from its old name
                // nor a key handed to the handlers, which get every change of it
                if (latest != null && latest > e.version() && !itemDeleted(e) && e.kind() != ChangeEvent.Kind.MOVED
                        && StoreRouting.isRouted(e.key())) {
                    failures.remove(e.key()); // overtaken by a newer change of its key, which applies the latest state
                    continue;
                }
                apply(e);
                failures.remove(e.key());
                newest.remove(e.key(), e.version());
                failedDeletions.remove(e.key(), e);
            } catch (Exception | LinkageError x) {
                if (itemDeleted(e)) {
                    failedDeletions.merge(e.key(), e, (a, b) -> b.version() > a.version() ? b : a);
                }
                // the store unreachable while reading the new content, say: try again with backoff, then every five
                // minutes until it applies (or a newer change of its key does), never giving up: this controller would
                // otherwise keep the old version until the key changes again
                int n = failures.merge(e.key(), 1, Integer::sum);
                long delay = n <= MAX_FAILURES ? Math.min(60, 1L << n) : 300;
                // a warning once it has failed for a few minutes, and then once an hour
                boolean warn = n == MAX_FAILURES + 1 || n > MAX_FAILURES && (n - MAX_FAILURES) % 12 == 0;
                LOGGER.log(warn ? Level.WARNING : Level.INFO, "Failed to apply " + e + "; retrying (" + n + ") in " + delay + " s", x);
                pending.incrementAndGet();
                Timer.get().schedule(() -> queue.add(e), delay, TimeUnit.SECONDS);
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private void catchUpQuietly() {
        if (!catchingUp.compareAndSet(false, true)) {
            return; // the previous run is still going
        }
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            List<ChangeEvent> found = new ArrayList<>();
            catchUp(found);
            found.removeIf(e -> Long.valueOf(e.version()).equals(caughtUp.put(e.key(), e.version())));
            if (!found.isEmpty()) {
                LOGGER.info(() -> found.size() + " stored change(s) not applied here yet; applying them: " + found);
            }
            for (ChangeEvent e : found) {
                pending.incrementAndGet();
                queue.add(e);
            }
        } catch (Exception | LinkageError x) {
            LOGGER.log(Level.WARNING, "Failed to compare what is stored with what is loaded here; trying again later", x);
        } finally {
            catchingUp.set(false);
        }
    }

    /**
     * Finds the differences between what is stored and what this controller holds, as the changes which would bring it
     * up to date. A key with a change of its own still to be applied (received, or failed and to be retried) is left to
     * it.
     */
    private void catchUp(List<ChangeEvent> found) throws IOException {
        if (Jenkins.getInstanceOrNull() != jenkins) {
            return;
        }
        catchUpItems(jenkins, found);
    }

    /**
     * Compares the configuration of the items of a group, and of the groups among them, with what is stored: one listing
     * of the directory holding them, one read per item.
     */
    private void catchUpItems(ItemGroup<?> group, List<ChangeEvent> found) throws IOException {
        // by directory holding them (jobs/f/jobs, say), by name
        Map<String, Map<String, AbstractItem>> loaded = new LinkedHashMap<>();
        for (Item i : group.getItems()) {
            String key = i instanceof AbstractItem ? PersistenceStores.keyFor(((AbstractItem) i).getRootDir()) : null;
            int slash = key == null ? -1 : key.lastIndexOf('/');
            if (slash > 0 && StoredItems.ITEM_CONTAINERS.contains(key.substring(key.lastIndexOf('/', slash - 1) + 1, slash))) {
                loaded.computeIfAbsent(key.substring(0, slash), k -> new HashMap<>()).put(key.substring(slash + 1), (AbstractItem) i);
            }
        }
        if (loaded.isEmpty()) {
            // none loaded here: wherever its items would be stored
            String key = group == jenkins ? "" : group instanceof AbstractItem a ? PersistenceStores.keyFor(a.getRootDir()) : null;
            if (key == null) {
                return;
            }
            for (String container : group == jenkins ? List.of("jobs") : StoredItems.ITEM_CONTAINERS) {
                loaded.put(key.isEmpty() ? container : key + "/" + container, Map.of());
            }
        }
        for (Map.Entry<String, Map<String, AbstractItem>> container : loaded.entrySet()) {
            String prefix = container.getKey();
            Set<String> stored = store.children(prefix);
            Set<String> names = new TreeSet<>(stored);
            names.addAll(container.getValue().keySet());
            for (String name : names) {
                String key = prefix + "/" + name + "/config.xml";
                if (!PersistenceStores.isSafeName(name) || itemPath(key) == null || !StoreRouting.isRouted(key)
                        || newest.containsKey(key) || failures.containsKey(key)) {
                    continue;
                }
                AbstractItem item = container.getValue().get(name);
                PersistenceStore.Entry entry = stored.contains(name) ? store.read(key) : null;
                long held = StoredVersions.lastSeen(key);
                if (entry != null && entry.version() > held) {
                    found.add(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, entry.version(), CATCH_UP));
                } else if (entry == null && item != null && held > PersistenceStore.ABSENT) {
                    if (item instanceof Job<?, ?> job && job.isBuilding()) {
                        // not deleted while it builds here: a move whose event was lost, say, would take its build
                        LOGGER.fine(() -> item.getFullName() + " is no longer stored but is building; looking again later");
                        continue;
                    }
                    // the version of the deletion is unknown: any above what is held
                    found.add(new ChangeEvent(ChangeEvent.Kind.DELETED, key, null, held + 1, CATCH_UP));
                }
                if (item instanceof ItemGroup<?> g && entry != null && entry.version() <= held) {
                    catchUpItems(g, found); // (a group reloaded loads its items as they are stored)
                }
            }
        }
    }

    private void apply(ChangeEvent e) throws Exception {
        if (store.getOrigin().equals(e.origin())) {
            return; // our own change
        }
        if (!PersistenceStores.isSafeKey(e.key()) || e.oldKey() != null && !PersistenceStores.isSafeKey(e.oldKey())) {
            // not written by a controller: a segment such as ".." would make it a path outside its directory here
            LOGGER.warning(() -> "Ignoring " + e + ": its key names no file");
            return;
        }
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            PersistenceStores.applyingRemoteChange(() -> {
                if (!StoreRouting.isRouted(e.key())) {
                    handOff(e);
                    return null;
                }
                switch (e.kind()) {
                    case WRITTEN -> {
                        deletions.remove(e.key());
                        written(e);
                    }
                    case DELETED -> {
                        Long last = deletions.get(e.key());
                        if (last != null && e.version() <= last) {
                            return null; // delivered again (subscribers get changes at least once): applied already
                        }
                        deleted(e);
                        deletions.put(e.key(), e.version());
                    }
                    case MOVED -> moved(e);
                    default -> throw new IllegalStateException(e.toString());
                }
                return null;
            });
        }
        if (itemPath(e.key()) != null) {
            // an item was created, changed, moved or deleted: its triggers may link it to other jobs
            jenkins.rebuildDependencyGraphAsync();
        }
    }

    /** Offers a change of a key core does not route to the {@link RemoteChangeHandler}s. */
    private void handOff(ChangeEvent e) throws Exception {
        for (RemoteChangeHandler h : ExtensionList.lookup(RemoteChangeHandler.class)) {
            if (e.kind() == ChangeEvent.Kind.MOVED) {
                if (e.oldKey() != null && h.handles(e.oldKey())) {
                    h.changed(e.oldKey(), true);
                }
                if (h.handles(e.key())) {
                    h.changed(e.key(), false);
                }
            } else if (h.handles(e.key())) {
                h.changed(e.key(), e.kind() == ChangeEvent.Kind.DELETED);
            }
        }
        applied.incrementAndGet();
    }

    private void written(ChangeEvent e) throws Exception {
        if (!e.key().startsWith("jobs/")) {
            return; // global configuration, users, nodes: applied by their own code
        }
        List<String> names = itemPath(e.key());
        if (names == null) {
            belowItem(e.key(), false);
            return;
        }
        if (e.version() <= StoredVersions.lastSeen(e.key())) {
            return; // already have this version (loaded it at startup, say)
        }
        ChangeEvent failedDeletion = failedDeletions.get(e.key());
        if (failedDeletion != null && failedDeletion.version() < e.version()) {
            // made again elsewhere after a deletion that failed to apply here: that deletion first, or the deleted item
            // would be reloaded as the new one; if it fails again, so does this write, retried after it
            LOGGER.fine(() -> "Applying " + failedDeletion + ", which failed, before " + e);
            deleted(failedDeletion);
            failedDeletions.remove(e.key(), failedDeletion);
        }
        String fullName = String.join("/", names);
        Item item = jenkins.getItemByFullName(fullName);
        if (item instanceof AbstractItem existing) {
            reload(existing);
            ItemListener.fireOnUpdated(existing);
            applied.incrementAndGet();
            return;
        }
        ItemGroup<?> parent = parentOf(names);
        if (parent == null) {
            String parentName = String.join("/", names.subList(0, names.size() - 1));
            LOGGER.fine(() -> "Parking " + e + " until " + parentName + " exists");
            parked.computeIfAbsent(parentName, k -> new ArrayList<>()).add(e);
            return;
        }
        File dir = PersistenceStores.fileFor(e.key()).getParentFile();
        if (!Items.getConfigFile(dir).checkExists()) { // a store unreachable throws: retried, not taken for a deletion
            return; // moved or deleted since; that change follows
        }
        Util.createDirectories(dir.toPath());
        LOGGER.fine(() -> "Loading " + fullName + " created elsewhere");
        if (parent == jenkins) {
            TopLevelItem created = (TopLevelItem) Items.load(jenkins, dir);
            if (jenkins.addItemCreatedElsewhere(created) != null) {
                // created here meanwhile, and changed elsewhere since: that one is brought up to date, as a changed item
                // is, rather than replaced by the copy just loaded
                StoredVersions.seen(e.key(), PersistenceStore.ANY); // the version read was the copy's
                written(e);
                return;
            }
        } else if (parent instanceof RemoteItemGroup group && Items.load(parent, dir) instanceof TopLevelItem created) {
            // just the new child: reloading the folder would reload every child already in it
            group.addRemotelyCreated(created);
            ItemListener.fireOnCreated(created);
        } else if (parent instanceof AbstractItem folder) {
            // reloading the folder loads children which so far exist only in the store
            reload(folder);
            Item created = jenkins.getItemByFullName(fullName);
            if (created == null) {
                LOGGER.warning(() -> "Reloaded " + folder.getFullName() + " but " + fullName + " did not appear");
                return;
            }
            ItemListener.fireOnCreated(created);
        } else {
            LOGGER.warning(() -> "Do not know how to add " + fullName + " to " + parent);
            return;
        }
        applied.incrementAndGet();
        List<ChangeEvent> waiting = parked.remove(fullName);
        if (waiting != null) {
            for (ChangeEvent w : waiting) {
                written(w);
            }
        }
    }

    /** Reloads an item from its stored configuration: a {@link StoredConfigReconciler}'s way, or the default. */
    private static void reload(AbstractItem item) throws IOException {
        Route route = StoreRouting.route(item.getConfigFile().getPath());
        PersistenceStore.Entry entry = route == null ? null : route.peek(); // a store unreachable throws: retried
        if (entry == null) {
            return; // moved or deleted since; that change follows
        }
        LOGGER.fine(() -> "Reloading " + item.getFullName() + " changed elsewhere");
        StoredReloads.reload(item.getConfigFile(), item, entry);
    }

    /**
     * Applies a change to a key below an item's directory that is not the configuration of an item, such as what a
     * plugin configures in the item (sub-items, extra files): the nearest item holding it is reloaded, after the local
     * directory of a configuration file is made, for code listing it.
     */
    private void belowItem(String key, boolean deleted) throws Exception {
        AbstractItem owner = owner(key);
        if (owner == null) {
            return; // not loaded (yet): loading it reads what is stored then
        }
        File file = PersistenceStores.fileFor(key);
        if (deleted) {
            Files.deleteIfExists(file.toPath()); // a local copy of what is no longer stored
        } else if (file.getName().equals("config.xml")) {
            Util.createDirectories(file.getParentFile().toPath());
        }
        reload(owner);
        applied.incrementAndGet();
    }

    /** The nearest loaded item whose directory holds a key, or null. */
    @CheckForNull
    private AbstractItem owner(String key) {
        String[] segments = key.split("/");
        AbstractItem owner = null;
        for (int i = 2; i < segments.length; i += 2) {
            if (i > 2 && !StoredItems.ITEM_CONTAINERS.contains(segments[i - 2])) {
                break;
            }
            List<String> path = itemPath(String.join("/", java.util.Arrays.copyOfRange(segments, 0, i)) + "/config.xml");
            if (path == null || !(jenkins.getItemByFullName(String.join("/", path)) instanceof AbstractItem found)) {
                break;
            }
            owner = found;
        }
        return owner;
    }

    private static boolean itemDeleted(ChangeEvent e) {
        return e.kind() == ChangeEvent.Kind.DELETED && itemPath(e.key()) != null;
    }

    private void deleted(ChangeEvent e) throws Exception {
        if (!e.key().startsWith("jobs/")) {
            return;
        }
        List<String> names = itemPath(e.key());
        if (names == null) {
            belowItem(e.key(), true);
            return;
        }
        if (e.version() <= StoredVersions.lastSeen(e.key())) {
            return; // deleted before what this controller has: an item it made again since, say
        }
        Item item = jenkins.getItemByFullName(String.join("/", names));
        if (item instanceof AbstractItem existing) {
            LOGGER.fine(() -> "Deleting " + existing.getFullName() + " deleted elsewhere");
            existing.delete();
            applied.incrementAndGet();
        }
        // only now: a save of the item before it is deleted here conflicts with the deletion, rather than making it again
        StoredVersions.seen(e.key(), PersistenceStore.ABSENT);
    }

    private void moved(ChangeEvent e) throws Exception {
        List<String> to = itemPath(e.key());
        List<String> from = e.oldKey() == null ? null : itemPath(e.oldKey());
        if (to == null || from == null) {
            if (e.oldKey() != null && StoreRouting.isRouted(e.oldKey())) {
                StoredVersions.seen(e.oldKey(), PersistenceStore.ABSENT);
            }
            return; // what is below a moved item moves with it
        }
        // something made at the old name since the move (here, or loaded after it) is not what was moved: it stays.
        // Versions of the two keys compare, being from one sequence for the whole store (see PersistenceStore)
        boolean madeSince = StoredVersions.lastSeen(e.oldKey()) >= e.version();
        Item item = madeSince ? null : jenkins.getItemByFullName(String.join("/", from));
        if (!madeSince) {
            if (item instanceof AbstractItem) {
                // until it is renamed or moved here, a save or deletion of it is refused, never made at the old name
                // (where nothing is stored now); once moved, the old name is absent (Items.move, renameTo)
                StoredItems.movingAway(e.oldKey());
            } else {
                StoredVersions.seen(e.oldKey(), PersistenceStore.ABSENT); // nothing is stored at the old name now
            }
        }
        if (item instanceof AbstractItem existing) {
            LOGGER.fine(() -> "Moving " + existing.getFullName() + " to " + String.join("/", to) + " as done elsewhere");
            ItemGroup<?> destination = parentOf(to);
            if (destination != null && destination.getItem(to.get(to.size() - 1)) instanceof AbstractItem placeholder && placeholder != existing) {
                takeOut(destination, placeholder, "loaded before the move that brings it here was applied");
            }
            File destDir = PersistenceStores.fileFor(e.key()).getParentFile();
            if (destination != null && destination.getItem(to.get(to.size() - 1)) == null && destDir.exists()) {
                // left behind here by an item of that name deleted elsewhere while this controller was down, say: the
                // move would fail on it (or merge it into the moved item's)
                clear(destDir, "left at " + String.join("/", to) + ", where an item moved elsewhere goes");
            }
            if (from.subList(0, from.size() - 1).equals(to.subList(0, to.size() - 1))) {
                Items.renameToMatchStore(existing, to.get(to.size() - 1));
            } else if (destination instanceof DirectlyModifiableTopLevelItemGroup d) {
                move(existing, d);
            } else if (destination == null && !existing.getConfigFile().checkExists()) {
                // where it went is not here (moved or renamed on since): this copy is taken out, and the item is loaded
                // where it is now as the changes that put it there are applied
                takeOut(existing.getParent(), existing, "moved elsewhere to " + String.join("/", to) + ", which is not here");
                applied.incrementAndGet();
                return;
            } else {
                LOGGER.warning(() -> "Cannot move " + existing.getFullName() + " to " + String.join("/", to));
                StoredItems.movedAway(existing.getRootDir()); // left where it is: absent there, as before
                return;
            }
            applied.incrementAndGet();
        }
        // else moved along with an ancestor already, never loaded here, or made again since: whatever is at the new
        // name now is reloaded (or loaded) from the store, recording the version read
        written(new ChangeEvent(ChangeEvent.Kind.WRITTEN, e.key(), null, e.version(), e.origin()));
    }

    /**
     * Takes an item out of its parent, its local directory deleted, or moved aside if it holds more than loading makes.
     * For an item loaded at the destination of a move before the move was applied, so that the item being moved, with
     * its builds, takes its place; or for the source of a move whose destination is not here. Nothing is deleted from
     * the store.
     */
    private void takeOut(ItemGroup<?> parent, AbstractItem placeholder, String why) throws Exception {
        if (!(parent instanceof DirectlyModifiableTopLevelItemGroup group) || !(placeholder instanceof TopLevelItem item)) {
            return;
        }
        LOGGER.fine(() -> "Dropping " + placeholder.getFullName() + ", " + why);
        group.remove(item);
        File dir = placeholder.getRootDir();
        String key = PersistenceStores.keyFor(dir);
        if (key != null) {
            // the versions read for it and its children are not those of the moved item's: each is reloaded
            StoredVersions.forgetUnder(key);
        }
        clear(dir, "an item moved here elsewhere takes its place");
    }

    /** Deletes a local directory if it holds no more than loading an item makes, else moves it aside. */
    private static void clear(File dir, String why) throws IOException {
        if (onlyWhatLoadingMakes(dir)) {
            Util.deleteRecursive(dir);
        } else {
            File aside = new File(dir.getParentFile(), dir.getName() + ".aside-" + System.currentTimeMillis());
            LOGGER.warning(() -> "Moving " + dir + " aside to " + aside + ": " + why);
            Files.move(dir.toPath(), aside.toPath());
        }
    }

    /** Whether a directory holds no files but those loading an item may have made. */
    private static boolean onlyWhatLoadingMakes(File dir) throws IOException {
        if (!dir.isDirectory()) {
            return true;
        }
        try (Stream<Path> files = Files.walk(dir.toPath())) {
            return files.filter(Files::isRegularFile).map(f -> f.getFileName().toString())
                    .allMatch(n -> n.equals("config.xml") || n.equals("nextBuildNumber") || n.equals("legacyIds") || n.equals("permalinks"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <I extends AbstractItem & TopLevelItem> void move(AbstractItem item, DirectlyModifiableTopLevelItemGroup destination) throws Exception {
        Items.move((I) item, destination);
    }

    @CheckForNull
    private ItemGroup<?> parentOf(List<String> names) {
        if (names.size() == 1) {
            return jenkins;
        }
        return jenkins.getItemByFullName(String.join("/", names.subList(0, names.size() - 1))) instanceof ItemGroup<?> g ? g : null;
    }

    /**
     * Item names along the path of an item configuration key, for example
     * {@code jobs/f/jobs/x/config.xml} → [f, x] and {@code jobs/mb/branches/main/config.xml} → [mb, main].
     *
     * @return null if the key is not the configuration of an item
     */
    @CheckForNull
    static List<String> itemPath(@NonNull String key) {
        String[] segments = key.split("/");
        if (segments.length < 3 || segments.length % 2 == 0 || !segments[0].equals("jobs") || !segments[segments.length - 1].equals("config.xml")) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i < segments.length - 1; i += 2) {
            if (i > 1 && !StoredItems.ITEM_CONTAINERS.contains(segments[i - 1])) {
                return null; // jobs/x/promotions/p/config.xml, say: configured in x, not an item
            }
            names.add(segments[i]);
        }
        return names;
    }
}
