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
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A file routed to the store ({@link StoreRouting#route}), with version tracking: a save over a change made
 * elsewhere is refused.
 *
 * <p>A save conflicting with a change made meanwhile by another controller never overwrites that change: it is
 * refused ({@link ConflictException}). A save of a key this JVM never read (its read failed, say, and the object holds
 * defaults) only creates it.
 */
@Restricted(NoExternalUse.class)
public record Route(@NonNull PersistenceStore store, @NonNull String key) {

    private static final Logger LOGGER = Logger.getLogger(Route.class.getName());

    /** Reads the content, taking its version as the one this JVM's object holds. */
    @CheckForNull
    public PersistenceStore.Entry read() throws IOException {
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
        return store.read(key);
    }

    /**
     * Whether the key is stored.
     *
     * @throws IOException if the store cannot tell (being unreachable, say): never taken for absent
     */
    public boolean exists() throws IOException {
        return store.read(key) != null;
    }

    /**
     * Writes content over the version this JVM last read or wrote ({@link PersistenceStore#ABSENT} if none: only
     * creates). Does nothing while {@linkplain PersistenceStores#applyingRemoteChange applying a remote change}.
     *
     * @return the version written
     * @throws ConflictException if the save conflicts with a change made elsewhere: it is refused, never written
     *     over that change
     */
    public long write(@NonNull byte[] content) throws IOException {
        if (PersistenceStores.isApplyingRemoteChange()) {
            return StoredVersions.lastSeen(key);
        }
        long version = store.write(key, content, StoredVersions.versions.getOrDefault(key, PersistenceStore.ABSENT));
        StoredVersions.seen(key, version);
        return version;
    }

    /**
     * Deletes the key. A deletion conflicting with a change made elsewhere is logged and done all the same: the
     * caller wanted it gone. Does nothing while {@linkplain PersistenceStores#applyingRemoteChange applying a remote
     * change}.
     */
    public void delete() throws IOException {
        if (PersistenceStores.isApplyingRemoteChange()) {
            return;
        }
        try {
            store.delete(key, StoredVersions.lastSeen(key));
        } catch (ConflictException e) {
            LOGGER.log(Level.WARNING, e.getMessage() + "; deleting it anyway");
            store.delete(key, PersistenceStore.ANY);
        }
        // absent is a version: a write made from an object loaded before (a late save, a new item of that name)
        // then conflicts with the key having been created again elsewhere meanwhile, rather than replacing it
        StoredVersions.seen(key, PersistenceStore.ABSENT);
    }
}
