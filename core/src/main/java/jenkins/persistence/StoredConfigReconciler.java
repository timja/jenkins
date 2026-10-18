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
import hudson.ExtensionPoint;
import hudson.XmlFile;
import hudson.model.Saveable;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * What to do when a save of a stored file conflicts with a change made meanwhile by another controller, and how to
 * bring a live object up to date with stored content. Only consulted while a {@link PersistenceStore} is active.
 *
 * <p>Registered reconcilers are consulted in ordinal order: the first non-null {@link #merge} and the first
 * {@link #reload} that returns true win.
 *
 * <p>Without a reconciler (or when all decline), core never overwrites the other change (no last-writer-wins): a
 * conflicting save fails with {@link ConflictException} and the object is reloaded from the stored version; and a
 * reload unmarshals the stored content onto the object with {@link XmlFile#unmarshalNullingOut} and calls its
 * {@code onLoad}, under the object's monitor, as {@code updateByXml} does.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface StoredConfigReconciler extends ExtensionPoint {

    /**
     * Merges a local change onto the stored version. Called from the conflict path of a save, only when the content
     * this controller last read or wrote (the base) is known.
     *
     * @param key the stored key
     * @param base the content this controller's object was loaded from or last saved
     * @param mine the content this controller is saving
     * @param theirs the content stored now (written by another controller)
     * @return merged content, written over {@code theirs} at its version (and retried as a new conflict if that moved
     *     again), or null to refuse: the save then fails with {@link ConflictException} and the object is reloaded
     *     from {@code theirs}
     */
    @CheckForNull
    default byte[] merge(@NonNull String key, @NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
        return null;
    }

    /**
     * Brings a live object up to date with stored content, as after another controller changed it, or after a save of
     * it here was refused or merged. Core calls {@link #reload(XmlFile, Saveable, PersistenceStore.Entry, Reason)},
     * which calls this one unless overridden.
     * Called with {@link PersistenceStores#applyingRemoteChange} in effect, under the object's monitor (as
     * {@code save()} takes it), and never with content older than the version this controller holds; once this returns
     * true, that version is taken as held, and the content as the base of the next save's merge, before the monitor is
     * released. A reconciler sets the object's state only: for an item, core calls {@code onLoad} afterwards, outside
     * the monitor ({@code onLoad} takes other locks, such as a job's builds', which code holding them and then asking
     * for the item's takes in the other order).
     *
     * @param file the file the object is saved to
     * @param o the object, such as an item, the {@code Jenkins} root, a descriptor, a user
     * @param entry the stored content and its version
     * @return true if done; false to leave it to the next reconciler, or to the default
     */
    default boolean reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry) throws IOException {
        return false;
    }

    /**
     * {@link #reload(XmlFile, Saveable, PersistenceStore.Entry)}, told why the object is reloaded. Called the same
     * way; by default calls that method, whatever the reason.
     *
     * @param reason why the object is reloaded
     */
    default boolean reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry, @NonNull Reason reason)
            throws IOException {
        return reload(file, o, entry);
    }

    /** Why an object is {@linkplain #reload(XmlFile, Saveable, PersistenceStore.Entry, Reason) reloaded}. */
    enum Reason {
        /** The stored content was changed by another controller, and that change is applied here. */
        CHANGED_ELSEWHERE,
        /**
         * A save of the object here was refused, or merged with a change made elsewhere. What the object holds and the
         * stored content lacks is what that save tried to store (refused), or the object lacks the other change
         * (merged): the stored content is what the object should hold.
         */
        AFTER_SAVE_HERE
    }
}
