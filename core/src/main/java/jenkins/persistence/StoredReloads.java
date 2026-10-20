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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Descriptor;
import hudson.model.Items;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Reloads objects from their stored files: after a save of one here was refused or merged ({@link #reloadLater}), and
 * as a change made elsewhere is applied ({@link #reload}).
 */
@Restricted(NoExternalUse.class)
public final class StoredReloads {

    private static final Logger LOGGER = Logger.getLogger(StoredReloads.class.getName());

    private static final ExecutorService reloads = Executors.newSingleThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), "Persistence store reload"));

    private static final AtomicInteger pendingReloads = new AtomicInteger();

    private StoredReloads() {}

    /** Whether no reload {@linkplain #reloadLater scheduled} is pending, for tests. */
    @Restricted(NoExternalUse.class)
    public static boolean isIdle() {
        return pendingReloads.get() == 0;
    }

    /**
     * Reloads an object from its stored file, soon, on another thread: after a save of it was refused (it holds the
     * refused values, which a later save would otherwise store after all), or merged (it lacks the other change).
     * Not on the saving thread, which may hold locks (the object's, say) that reloading takes.
     */
    @Restricted(NoExternalUse.class)
    public static void reloadLater(@NonNull XmlFile file, @NonNull Saveable o) {
        pendingReloads.incrementAndGet();
        try {
            reloads.execute(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    Route route = StoreRouting.route(file.getPath());
                    PersistenceStore.Entry entry = route == null ? null : route.peek();
                    if (entry == null || StoredVersions.lastSeen(route.key()) == entry.version()) {
                        return; // no longer stored (a deletion is applied as such), or held already
                    }
                    PersistenceStores.applyingRemoteChange(() -> {
                        reload(file, o, entry, StoredConfigReconciler.Reason.AFTER_SAVE_HERE);
                        return null;
                    });
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to reload " + o + " from " + file, e);
                } finally {
                    pendingReloads.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            pendingReloads.decrementAndGet();
            LOGGER.log(Level.WARNING, "Failed to schedule the reload of " + o, e);
        }
    }

    /**
     * {@link #reload(XmlFile, Saveable, PersistenceStore.Entry, StoredConfigReconciler.Reason)} as a change made
     * elsewhere is applied.
     */
    @Restricted(NoExternalUse.class)
    public static void reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry) throws IOException {
        reload(file, o, entry, StoredConfigReconciler.Reason.CHANGED_ELSEWHERE);
    }

    /**
     * Brings a live object up to date with stored content: the first {@link StoredConfigReconciler} that does it, or
     * else, for a user, {@link User#loadStored}; either under the object's monitor, as {@code save()} takes it, the
     * content's version being taken as held before the monitor is released. Otherwise the default for its kind: for the
     * global configuration {@link Jenkins#reloadGlobalConfig}; for a node of this controller, as one changed elsewhere
     * ({@code Nodes.changedElsewhere}); for a descriptor its own {@link Descriptor#load}, after
     * {@link RemovedFields#clear}; for others {@link XmlFile#unmarshalNullingOut} under the object's monitor. And, for
     * an item, its {@code onLoad}, as {@code updateByXml} does, once that monitor is released: {@code onLoad} takes
     * other locks (the builds' of a job), which code holding them and then the item's (a new build being numbered) takes
     * in the other order. Content older than what this JVM holds of the key is not applied: it was read before a
     * save or reload here, which it would undo. Call with {@link PersistenceStores#applyingRemoteChange} in effect.
     *
     * @param reason why it is reloaded, as the reconcilers are told
     */
    @Restricted(NoExternalUse.class)
    public static void reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry,
            @NonNull StoredConfigReconciler.Reason reason) throws IOException {
        String key = PersistenceStores.keyFor(file.getPath());
        boolean applied = false;
        // the content applied and its version taken as held at once: a save here then merges against the content the
        // object holds, never against what it held before, which would take what was reloaded for its own change
        synchronized (o) {
            if (key != null && entry.version() < StoredVersions.lastSeen(key)) {
                LOGGER.fine(() -> "Not reloading " + key + " from version " + entry.version() + ": version " + StoredVersions.lastSeen(key) + " is held");
                return;
            }
            for (StoredConfigReconciler r : ExtensionList.lookup(StoredConfigReconciler.class)) {
                if (r.reload(file, o, entry, reason)) {
                    applied = true;
                    break;
                }
            }
            if (!applied && o instanceof User u) {
                u.loadStored(entry.content()); // as at startup: its properties bound to it again
                applied = true;
            }
            if (applied && key != null) {
                StoredVersions.loaded(key, entry);
            }
        }
        if (!applied) {
            if (o instanceof Jenkins j) {
                // not nulled out: the global configuration is loaded over the root object as at startup
                j.reloadGlobalConfig();
                return;
            }
            if (o instanceof Node n && Jenkins.get().getNode(n.getNodeName()) == n) {
                // loaded afresh and its computer rebound to it, as a node changed elsewhere is (its executors, labels)
                Jenkins.get().getNodesObject().changedElsewhere(n.getNodeName(), false);
                return;
            }
            if (o instanceof Descriptor<?> d) {
                synchronized (d) {
                    // through its own load(), as at startup, so that an override migrating old data runs; not nulled out,
                    // as load() does not null out, but a field the stored content lacks is cleared
                    RemovedFields.clear(file, d, entry.content());
                    d.load(); // reads the stored content, so takes its version as held
                }
                return;
            }
            synchronized (o) {
                file.unmarshalNullingOut(o); // reads the stored content, so takes its version as held
            }
        }
        if (o instanceof AbstractItem item) {
            Items.runWhileUpdatingByXml(() -> item.onLoad(item.getParent(), item.getParent().getItemName(item.getRootDir(), item)));
            Jenkins.get().rebuildDependencyGraphAsync();
        }
    }
}
