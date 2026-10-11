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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Stores files that would otherwise live under {@code JENKINS_HOME} in an external service,
 * so that several controllers can share them.
 *
 * <p>Keys are paths relative to {@link jenkins.model.Jenkins#getRootDir}, using {@code /} as the separator,
 * for example {@code jobs/folder/jobs/job/config.xml}.
 * A store only serves the keys it {@link #owns}; everything else stays on the local filesystem,
 * so a store can take over state incrementally.
 *
 * <p>Every stored entry carries a version which increases on each modification.
 * Writes may be conditional on the version the caller last saw.
 *
 * @see PersistenceStoreProvider
 * @since TODO
 */
@Restricted(Beta.class)
public interface PersistenceStore {

    /**
     * Expected version meaning "overwrite whatever is there, if anything".
     */
    long ANY = -1;

    /**
     * Expected version meaning "only create; fail if the key already exists".
     */
    long ABSENT = 0;

    /**
     * Whether this store serves the given key.
     * Must be cheap and must not change over the lifetime of the store.
     * Below an item's directory it must depend only on the path relative to that directory, not on where the item is,
     * so that moving or renaming an item ({@link #move}) never changes which of its files the store serves.
     */
    boolean owns(@NonNull String key);

    /**
     * Reads an entry.
     *
     * @return the entry, or null if the key does not exist
     */
    @CheckForNull
    Entry read(@NonNull String key) throws IOException;

    /**
     * Writes an entry.
     * Writing content identical to the stored content must not create a new version.
     *
     * @param expectedVersion {@link #ANY}, {@link #ABSENT}, or the version the caller last saw
     * @return the version of the entry after the write
     * @throws ConflictException if {@code expectedVersion} does not match the stored version
     */
    long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws IOException;

    /**
     * Deletes an entry if it exists. Deleting an absent entry does nothing, whatever {@code expectedVersion} is: the
     * caller wanted it gone, and it is.
     *
     * @param expectedVersion {@link #ANY} or the version the caller last saw
     * @throws ConflictException if {@code expectedVersion} does not match the stored version
     */
    void delete(@NonNull String key, long expectedVersion) throws IOException;

    /**
     * Deletes every key below a prefix ({@code prefix/…}), each with its deletion event (Target 15): e.g. all the build
     * records of a deleted job. Stores override this to do it at once instead of key by key.
     *
     * @return how many keys were deleted
     */
    default int deleteAll(@NonNull String prefix) throws IOException {
        int n = 0;
        for (String child : children(prefix)) {
            String key = prefix + "/" + child;
            if (read(key) != null) {
                delete(key, ANY);
                n++;
            }
            n += deleteAll(key);
        }
        return n;
    }

    /**
     * Lists the names directly below a key, like listing a directory.
     * For example, with entries {@code jobs/a/config.xml} and {@code jobs/f/jobs/b/config.xml},
     * the children of {@code jobs} are {@code a} and {@code f}.
     *
     * @param parentKey a key without trailing {@code /}
     */
    @NonNull
    Set<String> children(@NonNull String parentKey) throws IOException;

    /**
     * Atomically renames a key and every key below it, as when an item directory is renamed or moved.
     * For example moving {@code jobs/a} to {@code jobs/f/jobs/a} moves {@code jobs/a/config.xml}
     * and {@code jobs/a/jobs/x/config.xml}, but not {@code jobs/ab/config.xml}.
     * Moved entries get new versions. Their change events are delivered shallower keys first, so that an item's
     * configuration arrives before the entries below it (its build records, its children's configuration).
     *
     * @return each entry moved: its key and version before, and its key and version now
     * @throws ConflictException if {@code toPrefix} or anything below it already exists
     */
    @NonNull
    List<Moved> move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException;

    /**
     * An entry {@linkplain #move moved}: from {@code from} at {@code fromVersion} to {@code to} at {@code version}.
     */
    record Moved(@NonNull String from, long fromVersion, @NonNull String to, long version) {}

    /**
     * Whether completed builds of a job are shared through this store, as described in {@link BuildRecords}.
     * Their records ({@code <jobKey>/builds/<N>/build.xml}) are written directly, never routed,
     * so a store must not {@linkplain #owns own} those keys.
     * Sharing builds needs one sequence of build numbers: the store must also own {@code <jobKey>/nextBuildNumber},
     * otherwise builds are not shared.
     *
     * @param jobKey the key of the job's directory, such as {@code jobs/folder/jobs/job}
     */
    default boolean storesBuildRecords(@NonNull String jobKey) {
        return false;
    }

    /**
     * Whether confidential keys are shared through this store, as {@code secrets/<id>}, so that every controller uses
     * the same ones (see {@link jenkins.security.ConfidentialStore}): a key created at runtime is looked up there, and
     * created there by the first controller to need it. They stay encrypted with the master key, which is never shared
     * (each controller is seeded with the same one). They are read and written directly, never routed, so a store must
     * not {@linkplain #owns own} those keys; and a store not meant to hold key material, even encrypted, does not opt in.
     */
    default boolean storesConfidentialKeys() {
        return false;
    }

    /**
     * The {@linkplain #getOrigin origins} of the store instances (controllers) currently alive, including this one.
     * Used to clean up state left behind by a controller which died, such as {@linkplain RunningBuilds running build placeholders}.
     *
     * @return the live origins, or null if this store does not track membership
     */
    @CheckForNull
    default Set<String> liveOrigins() throws IOException {
        return null;
    }

    /**
     * Leases on running builds, so that exactly one controller works on each.
     *
     * @return the leases, or null if this store does not offer them (builds are then never adopted)
     */
    @CheckForNull
    default BuildLeases leases() {
        return null;
    }

    /**
     * Writes an entry only while this origin holds a {@linkplain #leases lease} with this epoch: the lease is the fence,
     * so that a controller which lost a build (another one took it over) cannot write its record over the new owner's.
     * Stores should check and write atomically (in one transaction); this default checks, then writes.
     *
     * @return the new version, as {@link #write}
     * @throws LeaseLostException if the lease is not (or no longer) held with this epoch
     */
    default long writeIfHeld(@NonNull String leaseKey, long epoch, @NonNull String key, @NonNull byte[] content, long expectedVersion)
            throws IOException {
        BuildLeases leases = leases();
        if (leases != null && !leases.holds(leaseKey, epoch)) {
            throw new LeaseLostException(leaseKey, epoch);
        }
        return write(key, content, expectedVersion);
    }

    /**
     * The build queue shared by the controllers using this store.
     *
     * @return the queue, or null if this store does not offer one (each controller then has its own queue)
     */
    @CheckForNull
    default ClusterQueue queue() {
        return null;
    }

    /**
     * Identifies this store instance (and so this controller) as the origin of the changes it makes.
     */
    @NonNull
    String getOrigin();

    /**
     * Receives every change made through any instance of the store, including this one, in the order they were made.
     * Implementations deliver events at least once and must not lose them across reconnects.
     * Listeners should not throw; one which does is logged, and neither stops the others getting the event nor makes
     * the change that caused it fail.
     *
     * @return a handle which unsubscribes when closed
     */
    @NonNull
    AutoCloseable subscribe(@NonNull Consumer<ChangeEvent> listener);

    /**
     * A change to one key.
     *
     * @param kind what happened
     * @param key the key changed; for {@link Kind#MOVED} the new key
     * @param oldKey for {@link Kind#MOVED} the previous key, else null
     * @param version the version after the change; for {@link Kind#DELETED} too. A key's versions increase across its
     *     deletion and re-creation, so that a subscriber can tell a change older than the version it last read or wrote
     * @param origin {@link #getOrigin} of the store instance which made the change
     */
    record ChangeEvent(@NonNull Kind kind, @NonNull String key, @CheckForNull String oldKey, long version, @NonNull String origin) {
        public enum Kind { WRITTEN, DELETED, MOVED }
    }

    /**
     * Stored content with its version.
     *
     * @param content the raw bytes
     * @param version a positive number which increases with every modification of the key
     */
    record Entry(@NonNull byte[] content, long version) {}
}
