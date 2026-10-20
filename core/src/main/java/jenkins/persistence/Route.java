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
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A file routed to the store ({@link StoreRouting#route}), with version tracking and the conflict policy applied
 * (refuse and reload unless a {@link StoredConfigReconciler} merges).
 *
 * <p>A save conflicting with a change made meanwhile by another controller never overwrites that change: it is merged
 * onto it if a {@link StoredConfigReconciler} can, and otherwise refused ({@link ConflictException}), the object then
 * being reloaded from the stored version. A save of a key this JVM never read (its read failed, say, and the object
 * holds defaults) only creates it.
 */
@Restricted(NoExternalUse.class)
public record Route(@NonNull PersistenceStore store, @NonNull String key) {

    private static final Logger LOGGER = Logger.getLogger(Route.class.getName());
    private static final int MERGE_ATTEMPTS = 5;

    /** Reads the content, taking its version as the one this JVM's object holds (and the base of a later merge). */
    @CheckForNull
    public PersistenceStore.Entry read() throws IOException {
        PersistenceStore.Entry held = held();
        if (held != null) {
            return held; // not stored, so no version is held
        }
        PersistenceStore.Entry entry = store.read(key);
        if (entry == null) {
            StoredVersions.seen(key, PersistenceStore.ABSENT);
        } else {
            StoredVersions.loaded(key, entry);
        }
        return entry;
    }

    /** Reads the current content without this JVM taking that version as the one its object holds. */
    @CheckForNull
    public PersistenceStore.Entry peek() throws IOException {
        PersistenceStore.Entry held = held();
        return held != null ? held : store.read(key);
    }

    /**
     * Whether the key is stored.
     *
     * @throws IOException if the store cannot tell (being unreachable, say): never taken for absent
     */
    public boolean exists() throws IOException {
        return held() != null || store.read(key) != null;
    }

    /** What the current thread wrote to the key while creating it {@linkplain StoredItems.Creation#hold held}. */
    @CheckForNull
    private PersistenceStore.Entry held() {
        StoredItems.Creation creation = StoredItems.creations.get(key);
        return creation == null ? null : creation.heldEntry();
    }

    /**
     * Writes content over the version this JVM last read or wrote ({@link PersistenceStore#ABSENT} if none: only
     * creates). On a conflict, merges it onto the stored version if a {@link StoredConfigReconciler} can.
     * Does nothing while {@linkplain PersistenceStores#applyingRemoteChange applying a remote change}.
     *
     * @param reload run if the save was merged or refused: the object saved should then be reloaded
     * @return the version written
     * @throws ConflictException if the save conflicts with a change made elsewhere which cannot be merged: it is
     *     refused, never written over that change
     */
    public long write(@NonNull byte[] content, @CheckForNull Runnable reload) throws IOException {
        if (PersistenceStores.isApplyingRemoteChange()) {
            return StoredVersions.lastSeen(key);
        }
        StoredItems.Creation creation = StoredItems.creations.get(key);
        if (creation != null && creation.keep(content)) {
            return PersistenceStore.ABSENT; // stored on commit
        }
        boolean createOnly = creation != null && creation.written == PersistenceStore.ABSENT;
        if (!createOnly && StoredVersions.versions.get(key) instanceof Long held && held == StoredItems.GONE) {
            LOGGER.warning(() -> "Not saving " + key + ": it is being moved away");
            throw new StoredItems.Gone(key);
        }
        long expected = createOnly ? PersistenceStore.ABSENT : StoredVersions.versions.getOrDefault(key, PersistenceStore.ABSENT);
        long version;
        try {
            version = store.write(key, content, expected);
        } catch (ConflictException e) {
            if (createOnly) {
                LOGGER.fine(() -> e.getMessage() + "; not created over what is stored");
                throw e;
            }
            Long merged = mergeAndWrite(content, expected);
            if (merged == null) {
                PersistenceStores.refused.incrementAndGet();
                LOGGER.log(Level.WARNING, e.getMessage() + "; not saved, so as not to overwrite that change; reloading what is stored");
                if (reload != null) {
                    reload.run();
                }
                throw e;
            }
            MaterializedCopies.changed(key);
            if (reload != null && merged != Long.MIN_VALUE) {
                reload.run();
            }
            return merged == Long.MIN_VALUE ? StoredVersions.lastSeen(key) : merged;
        }
        if (creation != null) {
            creation.written = version;
        }
        MaterializedCopies.changed(key);
        StoredVersions.seen(key, version);
        StoredVersions.remember(key, version, content);
        return version;
    }

    /**
     * Merges what this JVM wrote with what was written meanwhile, against what it had read, and writes the result.
     *
     * @return the new version, {@link Long#MIN_VALUE} if what is stored is what was being written (nothing to
     *     reload), or null if the save cannot be merged
     */
    @CheckForNull
    private Long mergeAndWrite(byte[] mine, long expected) throws IOException {
        StoredVersions.Base base = StoredVersions.bases.get(key);
        for (int attempt = 0; attempt < MERGE_ATTEMPTS; attempt++) {
            PersistenceStore.Entry theirs = store.read(key);
            if (theirs == null) {
                return null; // deleted meanwhile: not created again
            }
            if (Arrays.equals(theirs.content(), mine)) {
                StoredVersions.loaded(key, theirs); // the same change was made elsewhere
                return Long.MIN_VALUE;
            }
            if (base == null || base.version() != expected) {
                return null; // what this object was loaded from is unknown: nothing to merge against
            }
            byte[] merged = null;
            if (Arrays.equals(theirs.content(), base.content())) {
                merged = mine; // changed and changed back elsewhere: this change applies as it is
            } else {
                for (StoredConfigReconciler r : ExtensionList.lookup(StoredConfigReconciler.class)) {
                    merged = r.merge(key, base.content(), mine, theirs.content());
                    if (merged != null) {
                        break;
                    }
                }
            }
            if (merged == null) {
                return null;
            }
            try {
                long version = store.write(key, merged, theirs.version());
                if (version != theirs.version()) {
                    // the object in memory still has only this side until it is reloaded: a save of it meanwhile
                    // must conflict and merge again, against what it holds, rather than write its stale part over
                    // the merged content
                    StoredVersions.seen(key, theirs.version());
                    StoredVersions.remember(key, theirs.version(), mine);
                }
                // else the merge is what was stored already (the write kept that version): keeping the version and
                // base read before makes the next save from this stale object merge again rather than pass
                LOGGER.info(() -> key + " was changed meanwhile elsewhere; merged both changes");
                return version;
            } catch (ConflictException again) {
                LOGGER.fine(() -> key + " changed again while merging; retrying");
            }
        }
        return null;
    }

    /**
     * Deletes the key. A deletion conflicting with a change made elsewhere is logged and done all the same: the
     * caller wanted it gone. Does nothing while {@linkplain PersistenceStores#applyingRemoteChange applying a remote
     * change}.
     *
     * @throws ConflictException if this JVM holds a version of the key but nothing is stored there any more: it was
     *     deleted, or renamed or moved away, elsewhere meanwhile, so what the caller deletes is not there (and what
     *     it would delete along with it, such as an item's builds, may be the moved item's). Nothing is deleted.
     */
    public void delete() throws IOException {
        if (PersistenceStores.isApplyingRemoteChange()) {
            return;
        }
        long held = StoredVersions.lastSeen(key);
        if (held == StoredItems.GONE) {
            throw new StoredItems.Gone(key); // never what is there now: something made since the item moved away
        }
        if (held > PersistenceStore.ABSENT && store.read(key) == null) {
            LOGGER.fine(() -> key + " was deleted or moved elsewhere since this controller read it; not deleting");
            throw new ConflictException(key, held, PersistenceStore.ABSENT);
        }
        try {
            store.delete(key, held);
        } catch (ConflictException e) {
            LOGGER.log(Level.WARNING, e.getMessage() + "; deleting it anyway");
            store.delete(key, PersistenceStore.ANY);
        }
        MaterializedCopies.changed(key);
        // absent is a version: a write made from an object loaded before (a late save, a new item of that name)
        // then conflicts with the key having been created again elsewhere meanwhile, rather than replacing it
        StoredVersions.seen(key, PersistenceStore.ABSENT);
    }
}
