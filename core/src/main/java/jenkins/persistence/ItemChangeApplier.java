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
import hudson.Util;
import hudson.XmlFile;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.DirectlyModifiableTopLevelItemGroup;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore.ChangeEvent;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Brings this controller's items up to date with changes other controllers make to item configuration in the
 * {@link PersistenceStore}: items created, reconfigured, renamed, moved or deleted elsewhere.
 *
 * <p>Subscribes before items are loaded, so nothing made in the meantime is missed, but only applies changes
 * once loading is done; changes already reflected in what was loaded are recognized by their version and skipped.
 * Changes are applied one at a time, in order, as {@link ACL#SYSTEM2}, and within
 * {@link PersistenceStores#applyingRemoteChange} so that they are not written back to the store.
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
     * Only the applier's thread uses it; the oldest are forgotten past a bound.
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
    private final Map<String, Long> newest = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Item deletions that failed to apply and are to be retried, by key: a write of the key made since (the item made
     * again) applies the deletion first. Only touched by the worker.
     */
    private final Map<String, ChangeEvent> failedDeletions = new HashMap<>();
    private Thread worker;

    private ItemChangeApplier(Jenkins jenkins, PersistenceStore store) {
        this.jenkins = jenkins;
        this.store = store;
        this.subscription = store.subscribe(e -> {
            newest.merge(e.key(), e.version(), Math::max);
            pending.incrementAndGet();
            queue.add(e);
        });
    }

    /**
     * Reloads the object stored at a key from the store, as if another controller had changed it, for example after this
     * JVM merged its write with one made elsewhere.
     */
    static void reload(String key) {
        ItemChangeApplier a = instance;
        if (a != null) {
            a.pending.incrementAndGet();
            a.queue.add(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, Long.MAX_VALUE, MERGED));
        }
    }

    /** How many times the application of a key's change has been put off, by key. */
    private final Map<String, Integer> retries = new java.util.concurrent.ConcurrentHashMap<>();
    /** The latest change put off, by key: what its retry applies (it reads the store again, so it covers the others). */
    private final Map<String, ChangeEvent> deferred = new java.util.concurrent.ConcurrentHashMap<>();
    /** Changes queued again by their retry (by identity: one delivered twice is equal to it). */
    private final Set<ChangeEvent> retrying = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private static final int MAX_RETRIES = 3;
    /** How many times a change that failed to apply has been retried, by key. */
    private final Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 8;
    /** After those, retries every five minutes, for an hour. */
    private static final int LATE_RETRIES = 12;

    /**
     * Puts off applying a change to an object with a save pending, for a second, at most {@link #MAX_RETRIES} times.
     * Changes to a key already put off (delivered again, or made since) wait for that one's retry: they neither apply
     * at once nor use up the retries.
     *
     * @return false if it has been put off enough: apply it now
     */
    private boolean retryLater(ChangeEvent e) {
        String key = e.key();
        boolean retry = retrying.remove(e);
        if (!retry && deferred.containsKey(key)) {
            deferred.merge(key, e, (a, b) -> b.version() > a.version() ? b : a);
            LOGGER.fine(() -> "Putting off " + e + " with the change to it already put off");
            return true;
        }
        int n = retries.merge(key, 1, Integer::sum);
        if (n > MAX_RETRIES) {
            applied(key);
            LOGGER.fine(() -> "Applying " + e + " although its object still has unsaved changes");
            return false;
        }
        deferred.merge(key, e, (a, b) -> b.version() > a.version() ? b : a);
        pending.incrementAndGet();
        Timer.get().schedule(() -> {
            ChangeEvent latest = deferred.get(key);
            if (latest == null) {
                pending.decrementAndGet(); // applied meanwhile
                return;
            }
            retrying.add(latest);
            queue.add(latest);
        }, 1, java.util.concurrent.TimeUnit.SECONDS);
        LOGGER.fine(() -> "Putting off " + e + ": its object has a save pending");
        return true;
    }

    /** Forgets what was put off of a key, its change applied. */
    private void applied(String key) {
        retries.remove(key);
        deferred.remove(key);
    }

    /**
     * Done with a change, applied or not (its version seen already, say): if it was a retry, what was put off of its key
     * is forgotten too, or a newer change put off since is retried in its place. Otherwise later changes would wait for
     * a retry that never comes.
     */
    private void retire(ChangeEvent e) {
        if (!retrying.remove(e)) {
            return; // not a retry, or put off again (retryLater took it)
        }
        String key = e.key();
        if (deferred.remove(key, e)) {
            retries.remove(key);
            return;
        }
        ChangeEvent newer = deferred.get(key);
        if (newer != null) {
            retrying.add(newer);
            pending.incrementAndGet();
            queue.add(newer);
        }
    }

    /** The origin of {@link #reload} events (never a controller's). */
    private static final String MERGED = "(merged here)";

    // before global configuration is loaded, which item loading (requiring SYSTEM_CONFIG_ADAPTED) follows: an item read
    // before subscribing could miss a change made in between, never delivered and newer than what was read
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static synchronized void subscribe() {
        stop();
        PersistenceStore store = PersistenceStores.get();
        if (store != null) {
            instance = new ItemChangeApplier(Jenkins.get(), store);
            LOGGER.fine("Subscribed to item changes");
        }
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static synchronized void start() {
        if (instance != null && instance.worker == null) {
            ItemChangeApplier a = instance;
            a.pending.incrementAndGet(); // the snapshots of what was loaded, taken first
            a.worker = new Thread(a::run, "Persistence store item change applier");
            a.worker.setDaemon(true);
            a.worker.start();
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
            instance = null;
        }
    }

    /**
     * Number of changes made elsewhere that this controller has applied, for diagnostics and tests.
     */
    public static long getAppliedCount() {
        ItemChangeApplier a;
        synchronized (ItemChangeApplier.class) {
            a = instance;
        }
        return a == null ? 0 : a.applied.get();
    }

    /** Whether this controller listens to changes made elsewhere, for tests. */
    static synchronized boolean isSubscribed() {
        return instance != null;
    }

    /**
     * Whether every change received so far has been processed, for tests.
     */
    public static boolean isIdle() {
        ItemChangeApplier a;
        synchronized (ItemChangeApplier.class) {
            a = instance;
        }
        return a == null || a.pending.get() == 0;
    }

    private void run() {
        try {
            snapshotLoaded();
        } finally {
            pending.decrementAndGet();
        }
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
                // deleted one's builds and history (deleted() skips one older than what this controller has read)
                boolean itemDeleted = itemDeleted(e);
                // nor a move: a later write of its destination does not move the item from its old name (or a second
                // one would be loaded at the new name, the moved one left where it was)
                boolean move = e.kind() == ChangeEvent.Kind.MOVED;
                if (!MERGED.equals(e.origin()) && latest != null && latest > e.version() && !itemDeleted && !move) {
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
                // e.g. the store was unreachable while reading the new content: try again with backoff, then every few
                // minutes for a while, or this controller would keep the old version until the key changes again
                int n = failures.merge(e.key(), 1, Integer::sum);
                if (n <= MAX_FAILURES + LATE_RETRIES) {
                    long delay = n <= MAX_FAILURES ? Math.min(60, 1L << n) : 300;
                    LOGGER.log(n == MAX_FAILURES + 1 ? Level.WARNING : Level.INFO, "Failed to apply " + e + "; retrying (" + n + ") in " + delay + " s", x);
                    pending.incrementAndGet();
                    Timer.get().schedule(() -> queue.add(e), delay, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    failures.remove(e.key());
                    newest.remove(e.key(), e.version());
                    failedDeletions.remove(e.key(), e);
                    LOGGER.log(Level.WARNING, "Failed to apply " + e + "; giving up until its key changes again", x);
                }
            } finally {
                retire(e);
                pending.decrementAndGet();
            }
        }
    }

    /**
     * Takes snapshots of the global configuration and the items loaded at startup (once load-time fixups are done), so
     * that a change made to one in memory and not saved yet is recognised from the start, and not reloaded over
     * ({@link PersistenceStores#hasUnsavedChanges}). It holds one serialisation per object, as the merge bases hold what
     * the store had. Off the startup thread: this applies nothing before it is done.
     */
    private void snapshotLoaded() {
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            synchronized (jenkins) {
                PersistenceStores.loaded(new XmlFile(Jenkins.XSTREAM, new File(jenkins.getRootDir(), "config.xml")), jenkins);
            }
            for (AbstractItem item : jenkins.allItems(AbstractItem.class)) {
                synchronized (item) {
                    PersistenceStores.loaded(item.getConfigFile(), item);
                }
            }
        } catch (RuntimeException x) {
            LOGGER.log(Level.WARNING, "Failed to take snapshots of what was loaded", x);
        }
    }

    private void apply(ChangeEvent e) throws Exception {
        if (store.getOrigin().equals(e.origin())) {
            return; // our own change
        }
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            PersistenceStores.applyingRemoteChange(() -> {
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

    private void written(ChangeEvent e) throws Exception {
        if (build(e)) {
            return;
        }
        if (!e.key().startsWith("jobs/")) {
            if (e.version() > PersistenceStores.lastSeen(e.key())) {
                if (e.key().equals("config.xml")) {
                    // check and reload under one lock (Jenkins.save and reloadGlobalConfig take it), as for items below
                    synchronized (jenkins) {
                        if (jenkins.hasUnsavedGlobalChanges() && retryLater(e)) {
                            return;
                        }
                        if (StaticStateApplier.apply(e.key(), false)) {
                            applied.incrementAndGet();
                        }
                    }
                } else if (StaticStateApplier.apply(e.key(), false)) {
                    applied.incrementAndGet();
                }
                applied(e.key());
            }
            return;
        }
        String owner = SubItems.ownerKey(e.key());
        if (owner != null) {
            // a promotion process or matrix configuration (Target 15): its job loads it, from its directory
            Util.createDirectories(SubItems.dirOf(e.key()).toPath());
            reload(owner);
            return;
        }
        List<String> names = itemPath(e.key());
        if (names == null || e.version() <= PersistenceStores.lastSeen(e.key())) {
            return; // not an item, or already have this version (e.g. loaded it at startup)
        }
        ChangeEvent failedDeletion = failedDeletions.get(e.key());
        if (failedDeletion != null && failedDeletion.version() < e.version() && !MERGED.equals(e.origin())) {
            // made again elsewhere after a deletion that failed to apply here: that deletion first, or the deleted item
            // would be reloaded as the new one, keeping its builds and history (and its retry then skipped as older
            // than what was loaded); if it fails again, so does this write, retried after it
            LOGGER.fine(() -> "Applying " + failedDeletion + ", which failed, before " + e);
            deleted(failedDeletion);
            failedDeletions.remove(e.key(), failedDeletion);
        }
        String fullName = String.join("/", names);
        Item item = jenkins.getItemByFullName(fullName);
        if (item instanceof AbstractItem existing) {
            if (!existing.getConfigFile().exists()) {
                return; // moved or deleted since; that change follows, and reloads it at its new name if moved
            }
            LOGGER.fine(() -> "Reloading " + fullName + " changed elsewhere");
            // under the item's lock, as AbstractItem.save is: a save while the object is half reloaded would write a mix
            // of the two versions, as of the version just read, so nobody would notice (lost trigger, ET11 scenario 3)
            // the new version is read outside the lock; only what changed is copied in under it (Target 15)
            ItemReload.Prepared prepared = ItemReload.prepare(existing);
            synchronized (existing) {
                if (PersistenceStores.hasUnsavedChanges(existing.getConfigFile(), existing) && retryLater(e)) {
                    return; // its save is pending: it will conflict and be merged, and reloaded with both changes
                }
                if (prepared == null || !ItemReload.apply(existing, prepared)) {
                    PersistenceStores.reload(existing.getConfigFile(), existing, existing::load); // a display name cleared, say
                }
                PersistenceStores.synced(existing.getConfigFile(), existing);
            }
            applied(e.key());
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
        if (!Items.getConfigFile(dir).exists()) {
            return; // moved or deleted since; that change follows
        }
        Util.createDirectories(dir.toPath());
        LOGGER.fine(() -> "Loading " + fullName + " created elsewhere");
        if (parent == jenkins) {
            TopLevelItem created = (TopLevelItem) Items.load(jenkins, dir);
            if (jenkins.addItemCreatedElsewhere(created) != null) {
                // created here meanwhile (stored, then added, after the check above), and changed elsewhere since: that
                // one is brought up to date, as a changed item is, rather than replaced by the copy just loaded
                PersistenceStores.seen(e.key(), PersistenceStore.ANY); // the version read was the copy's
                written(e);
                return;
            }
            if (created instanceof AbstractItem ai) {
                PersistenceStores.synced(ai.getConfigFile(), ai);
            }
        } else if (parent instanceof RemoteItemGroup group && Items.load(parent, dir) instanceof TopLevelItem created) {
            // just the new child (Target 15): reloading the folder would reload every child already in it
            if (created instanceof AbstractItem ai) {
                PersistenceStores.synced(ai.getConfigFile(), ai);
            }
            group.addRemotelyCreated(created);
            ItemListener.fireOnCreated(created);
        } else if (parent instanceof AbstractItem folder) {
            // reloading the folder loads children which so far exist only in the store; under its lock, as items are
            // reloaded above, so that a save of the folder meanwhile does not store a mix of the two versions
            synchronized (folder) {
                PersistenceStores.reload(folder.getConfigFile(), folder, folder::load);
                PersistenceStores.synced(folder.getConfigFile(), folder);
            }
            Item created = jenkins.getItemByFullName(fullName);
            if (created == null) {
                LOGGER.warning(() -> "Reloaded " + folder.getFullName() + " but " + fullName + " did not appear");
                return;
            }
            if (created instanceof AbstractItem ai) {
                PersistenceStores.synced(ai.getConfigFile(), ai);
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

    private static boolean itemDeleted(ChangeEvent e) {
        return e.kind() == ChangeEvent.Kind.DELETED && itemPath(e.key()) != null;
    }

    private void deleted(ChangeEvent e) throws Exception {
        if (build(e)) {
            return;
        }
        if (!e.key().startsWith("jobs/")) {
            if (e.version() <= PersistenceStores.lastSeen(e.key())) {
                return; // deleted before what this controller has: saved here since (a deletion it then overwrote), say
            }
            PersistenceStores.seen(e.key(), PersistenceStore.ABSENT);
            if (StaticStateApplier.apply(e.key(), true)) {
                applied.incrementAndGet();
            }
            return;
        }
        String owner = SubItems.ownerKey(e.key());
        if (owner != null) {
            Util.deleteRecursive(SubItems.dirOf(e.key())); // its job reloads without it
            reload(owner);
            return;
        }
        List<String> names = itemPath(e.key());
        if (names == null || e.version() <= PersistenceStores.lastSeen(e.key())) {
            return; // not an item, or deleted before what this controller has: an item it made again since, say
        }
        PersistenceStores.seen(e.key(), PersistenceStore.ABSENT);
        Item item = jenkins.getItemByFullName(String.join("/", names));
        if (item instanceof AbstractItem existing) {
            LOGGER.fine(() -> "Deleting " + existing.getFullName() + " deleted elsewhere");
            existing.delete();
            applied.incrementAndGet();
        }
    }

    /**
     * Applies a change to a {@linkplain BuildRecords build record}.
     *
     * @return false if the key is not a build record
     */
    private boolean build(ChangeEvent e) throws Exception {
        RunningBuilds.StopKey stop = RunningBuilds.StopKey.parse(e.key());
        if (stop != null) {
            if (e.kind() == ChangeEvent.Kind.WRITTEN) {
                RunningBuilds.stopRequested(stop.jobKey(), stop.number());
            }
            applied.incrementAndGet();
            return true;
        }
        String runningJob = RunningBuilds.jobKeyOf(e.key());
        if (runningJob != null) {
            RunningBuilds.invalidate(runningJob);
            applied.incrementAndGet();
            return true;
        }
        BuildRecords.RecordKey record = BuildRecords.RecordKey.parse(e.key());
        List<String> names = record == null ? null : itemPath(record.jobKey() + "/config.xml");
        if (names == null) {
            return false;
        }
        Job<?, ?> job = jenkins.getItemByFullName(String.join("/", names), Job.class);
        if (job == null) {
            return true; // not loaded (yet); its builds are listed from the store when it is
        }
        if (!BuildRecords.isShared(job)) {
            return true; // the store does not share this job's builds: nothing here changes
        }
        BuildRecords.seen(e.key(), e.kind() == ChangeEvent.Kind.DELETED ? PersistenceStore.ABSENT : e.version());
        if (e.oldKey() != null) {
            BuildRecords.seen(e.oldKey(), PersistenceStore.ABSENT);
        }
        if (e.kind() == ChangeEvent.Kind.DELETED) {
            BuildRecords.deletedElsewhere(job, record.number());
        } else if (e.kind() == ChangeEvent.Kind.MOVED) {
            BuildRecords.movedElsewhere(job, record.number()); // the job's own move came first
        } else {
            BuildRecords.writtenElsewhere(job, record.number());
        }
        applied.incrementAndGet();
        return true;
    }

    private void moved(ChangeEvent e) throws Exception {
        String runningJob = RunningBuilds.jobKeyOf(e.key());
        if (runningJob != null) {
            RunningBuilds.invalidate(runningJob);
            if (e.oldKey() != null && RunningBuilds.jobKeyOf(e.oldKey()) != null) {
                RunningBuilds.invalidate(RunningBuilds.jobKeyOf(e.oldKey()));
            }
            return;
        }
        if (BuildRecords.RecordKey.parse(e.key()) != null) {
            build(e);
            return;
        }
        List<String> to = itemPath(e.key());
        List<String> from = e.oldKey() == null ? null : itemPath(e.oldKey());
        if (to == null || from == null) {
            return;
        }
        // something made at the old name since the move (here, or loaded after it) is not what was moved: it stays
        boolean madeSince = PersistenceStores.lastSeen(e.oldKey()) >= e.version();
        if (!madeSince) {
            // nothing is stored at the old name now, also for children moved along
            PersistenceStores.seen(e.oldKey(), PersistenceStore.ABSENT);
        }
        Item item = madeSince ? null : jenkins.getItemByFullName(String.join("/", from));
        if (item instanceof AbstractItem existing) {
            LOGGER.fine(() -> "Moving " + existing.getFullName() + " to " + String.join("/", to) + " as done elsewhere");
            ItemGroup<?> destination = parentOf(to);
            if (destination != null && destination.getItem(to.get(to.size() - 1)) instanceof AbstractItem placeholder && placeholder != existing) {
                takeOut(destination, placeholder, "loaded before the move that brings it here was applied");
            }
            if (from.subList(0, from.size() - 1).equals(to.subList(0, to.size() - 1))) {
                Items.renameToMatchStore(existing, to.get(to.size() - 1));
            } else if (destination instanceof DirectlyModifiableTopLevelItemGroup d) {
                move(existing, d);
            } else if (destination == null && !existing.getConfigFile().exists()) {
                // where it went is not here (moved or renamed on since): stored nowhere under this name, this copy is
                // taken out, and the item is loaded where it is now as the changes that put it there are applied
                takeOut(existing.getParent(), existing, "moved elsewhere to " + String.join("/", to) + ", which is not here");
                applied.incrementAndGet();
                return;
            } else {
                LOGGER.warning(() -> "Cannot move " + existing.getFullName() + " to " + String.join("/", to));
                return;
            }
            applied.incrementAndGet();
        }
        // else moved along with an ancestor already, never loaded here, or made again since
        // whatever is at the new name now is reloaded (or loaded) from the store, recording the version read: a change
        // made just before the move was not applied to the old name, and a child moved along has its version recorded
        written(new ChangeEvent(ChangeEvent.Kind.WRITTEN, e.key(), null, e.version(), e.origin()));
    }

    /**
     * Takes an item out of its parent, its local directory deleted, or moved aside if it holds more than loading makes.
     * For an item loaded at the destination of a move from the store before the move was applied (with a folder created
     * elsewhere just before), so that the item being moved, with its builds, takes its place; or for the source of a
     * move whose destination is not here, a copy stored nowhere any more.
     * Nothing is deleted from the store.
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
            PersistenceStores.forgetUnder(key);
        }
        if (onlyWhatLoadingMakes(dir)) {
            Util.deleteRecursive(dir);
        } else {
            File aside = new File(dir.getParentFile(), dir.getName() + ".aside-" + System.currentTimeMillis());
            LOGGER.warning(() -> "Moving " + dir + " aside to " + aside + ": an item moved here elsewhere takes its place");
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
    static List<String> itemPath(String key) {
        String[] segments = key.split("/");
        if (segments.length < 3 || segments.length % 2 == 0 || !segments[0].equals("jobs") || !segments[segments.length - 1].equals("config.xml")) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i < segments.length - 1; i += 2) {
            if (i > 1 && !segments[i - 1].equals("jobs") && !segments[i - 1].equals("branches")) {
                return null; // e.g. jobs/x/promotions/p/config.xml: a sub-item of x (SubItems), not an item
            }
            names.add(segments[i]);
        }
        return names;
    }
}
