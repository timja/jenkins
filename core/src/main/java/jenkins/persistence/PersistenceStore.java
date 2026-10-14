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
     * Moved entries get new versions.
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
     * Stored content with its version.
     *
     * @param content the raw bytes
     * @param version a positive number which increases with every modification of the key
     */
    record Entry(@NonNull byte[] content, long version) {}
}
