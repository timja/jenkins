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
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.DirectlyModifiableTopLevelItemGroup;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore.ChangeEvent;
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
    /** Changes received and not yet fully processed. */
    private final AtomicLong pending = new AtomicLong();
    private Thread worker;

    private ItemChangeApplier(Jenkins jenkins, PersistenceStore store) {
        this.jenkins = jenkins;
        this.store = store;
        this.subscription = store.subscribe(e -> {
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
            LOGGER.fine("Subscribed to item changes");
        }
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static synchronized void start() {
        if (instance != null && instance.worker == null) {
            ItemChangeApplier a = instance;
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
        while (Jenkins.getInstanceOrNull() == jenkins) {
            ChangeEvent e;
            try {
                e = queue.take();
            } catch (InterruptedException x) {
                return;
            }
            try {
                apply(e);
            } catch (Exception | LinkageError x) {
                LOGGER.log(Level.WARNING, "Failed to apply " + e, x);
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private void apply(ChangeEvent e) throws Exception {
        if (store.getOrigin().equals(e.origin())) {
            return; // our own change
        }
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            PersistenceStores.applyingRemoteChange(() -> {
                switch (e.kind()) {
                    case WRITTEN -> written(e);
                    case DELETED -> deleted(e);
                    case MOVED -> moved(e);
                    default -> throw new IllegalStateException(e.toString());
                }
                return null;
            });
        }
    }

    private void written(ChangeEvent e) throws Exception {
        List<String> names = itemPath(e.key());
        if (names == null || e.version() <= PersistenceStores.lastSeen(e.key())) {
            return; // not an item, or already have this version (e.g. loaded it at startup)
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
            synchronized (existing) {
                PersistenceStores.clearRemovedFields(existing.getConfigFile(), existing); // a display name cleared, say
                existing.load();
            }
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
            if (jenkins.addItemCreatedElsewhere((TopLevelItem) Items.load(jenkins, dir)) != null) {
                // created here meanwhile (stored, then added, after the check above), and changed elsewhere since: that
                // one is brought up to date, as a changed item is, rather than replaced by the copy just loaded
                PersistenceStores.seen(e.key(), PersistenceStore.ANY); // the version read was the copy's
                written(e);
                return;
            }
        } else if (parent instanceof AbstractItem folder) {
            // reloading the folder loads children which so far exist only in the store; under its lock, as items are
            // reloaded above, so that a save of the folder meanwhile does not store a mix of the two versions
            synchronized (folder) {
                PersistenceStores.clearRemovedFields(folder.getConfigFile(), folder);
                folder.load();
            }
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

    private void deleted(ChangeEvent e) throws Exception {
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

    private void moved(ChangeEvent e) throws Exception {
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
            names.add(segments[i]);
        }
        return names;
    }
}
