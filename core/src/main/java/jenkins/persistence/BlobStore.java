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
import java.io.InputStream;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Stores large, rarely changing content which several controllers share, such as snapshots of completed builds.
 * Typically backed by an object store; complements a {@link PersistenceStore}, which holds small records.
 *
 * <p>Keys use the same {@code JENKINS_HOME}-relative scheme as {@link PersistenceStore},
 * so that a prefix covers everything stored for an item and moves with it.
 * There are no versions or change events: writers publish a blob before the record which refers to it.
 *
 * @see BlobStoreProvider
 * @since TODO
 */
@Restricted(Beta.class)
public interface BlobStore {

    /**
     * Stores a blob, replacing any existing blob with the same key.
     *
     * @param length the number of bytes {@code content} will deliver
     */
    void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException;

    /**
     * Opens a blob for reading.
     *
     * @return the content, which the caller must close, or null if there is no such blob
     */
    @CheckForNull
    InputStream get(@NonNull String key) throws IOException;

    /**
     * Deletes the blob with this key and every blob below it ({@code prefix + "/..."}), if any.
     */
    void delete(@NonNull String prefix) throws IOException;

    /**
     * Renames the blob with this key and every blob below it, as {@link PersistenceStore#move} does for entries.
     * Need not be atomic, but must be safe to repeat after a partial failure.
     */
    void move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException;
}
