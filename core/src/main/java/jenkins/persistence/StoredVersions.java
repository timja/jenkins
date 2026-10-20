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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps, per stored key, the version this JVM last read or wrote, against which saves are checked
 * ({@link Route#write}).
 */
@Restricted(NoExternalUse.class)
public final class StoredVersions {

    /** The version of each key this JVM last read or wrote ({@link PersistenceStore#ABSENT} if it saw none). */
    static final Map<String, Long> versions = new ConcurrentHashMap<>();
    /** The content of each XML key at the version this JVM last read or wrote, the base of a merge. */
    static final Map<String, Base> bases = new ConcurrentHashMap<>();
    /** Bases larger than this are not kept: such a save is refused rather than merged on a conflict. */
    private static final int MAX_BASE_SIZE = 1024 * 1024;

    private StoredVersions() {}

    /** Forgets every key, as when the store is resolved again. */
    static void clear() {
        versions.clear();
        bases.clear();
    }

    /**
     * Records the version of a key as seen by this JVM, for example after applying a change made elsewhere.
     * {@link PersistenceStore#ABSENT} is a version too: the next write then expects the key to still be absent, so that
     * another controller creating it meanwhile is a conflict. {@link PersistenceStore#ANY} forgets it.
     */
    @Restricted(NoExternalUse.class)
    public static void seen(@NonNull String key, long version) {
        if (version >= PersistenceStore.ABSENT) {
            versions.put(key, version);
        } else {
            versions.remove(key);
        }
        Base base = bases.get(key);
        if (base != null && base.version() != version) {
            bases.remove(key); // not the content of this version: never merged against
        }
    }

    /**
     * The version of a key this JVM last read or wrote, or {@link PersistenceStore#ANY} if unknown.
     */
    @Restricted(NoExternalUse.class)
    public static long lastSeen(@NonNull String key) {
        return versions.getOrDefault(key, PersistenceStore.ANY);
    }

    /** Content of an XML key at a version, the base of a merge. */
    record Base(long version, byte[] content) {}

    static void remember(String key, long version, byte[] content) {
        if (key.endsWith(".xml") && content.length <= MAX_BASE_SIZE) {
            bases.put(key, new Base(version, content));
        } else {
            bases.remove(key);
        }
    }

    /** Records that this JVM's object now holds an entry read with {@link Route#peek} (as {@link Route#read} does). */
    @Restricted(NoExternalUse.class)
    public static void loaded(@NonNull String key, @NonNull PersistenceStore.Entry entry) {
        seen(key, entry.version());
        remember(key, entry.version(), entry.content());
    }
}
