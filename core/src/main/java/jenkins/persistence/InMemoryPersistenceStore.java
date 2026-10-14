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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * A {@link PersistenceStore} held in memory, for tests and as a reference for implementations.
 * Several Jenkins instances in one JVM may share an instance.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public class InMemoryPersistenceStore implements PersistenceStore {

    private final Predicate<String> owns;
    private final Map<String, Entry> entries = new TreeMap<>();
    /** Versions come from one counter, so a key that is deleted and recreated never repeats a version. */
    private long clock;

    /**
     * @param owns which keys this store serves
     */
    public InMemoryPersistenceStore(@NonNull Predicate<String> owns) {
        this.owns = owns;
    }

    @Override
    public boolean owns(@NonNull String key) {
        return owns.test(key);
    }

    @Override
    @CheckForNull
    public synchronized Entry read(@NonNull String key) {
        Entry e = entries.get(key);
        return e == null ? null : new Entry(e.content().clone(), e.version());
    }

    @Override
    public synchronized long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws ConflictException {
        Entry current = entries.get(key);
        long actual = current == null ? ABSENT : current.version();
        if (expectedVersion != ANY && expectedVersion != actual) {
            throw new ConflictException(key, expectedVersion, actual);
        }
        if (current != null && Arrays.equals(current.content(), content)) {
            return actual;
        }
        long version = ++clock;
        entries.put(key, new Entry(content.clone(), version));
        return version;
    }

    @Override
    public synchronized void delete(@NonNull String key, long expectedVersion) throws ConflictException {
        Entry current = entries.get(key);
        if (current == null) {
            return;
        }
        if (expectedVersion != ANY && expectedVersion != current.version()) {
            throw new ConflictException(key, expectedVersion, current.version());
        }
        entries.remove(key);
    }

    @Override
    @NonNull
    public synchronized Set<String> children(@NonNull String parentKey) {
        String prefix = parentKey + "/";
        Set<String> names = new TreeSet<>();
        for (String key : entries.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash == -1 ? rest : rest.substring(0, slash));
            }
        }
        return names;
    }

    @Override
    public synchronized List<Moved> move(@NonNull String fromPrefix, @NonNull String toPrefix) throws ConflictException {
        for (String key : entries.keySet()) {
            if (key.equals(toPrefix) || key.startsWith(toPrefix + "/")) {
                throw new ConflictException(key, ABSENT, entries.get(key).version());
            }
        }
        List<Moved> moved = new ArrayList<>();
        Map<String, Entry> now = new TreeMap<>();
        for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            String key = e.getKey();
            if (key.equals(fromPrefix) || key.startsWith(fromPrefix + "/")) {
                String to = toPrefix + key.substring(fromPrefix.length());
                long version = ++clock;
                now.put(to, new Entry(e.getValue().content(), version));
                moved.add(new Moved(key, e.getValue().version(), to, version));
                it.remove();
            }
        }
        entries.putAll(now);
        return moved;
    }

    /**
     * Snapshot of the stored keys, for assertions.
     */
    public synchronized Map<String, Long> versions() {
        Map<String, Long> r = new TreeMap<>();
        entries.forEach((k, v) -> r.put(k, v.version()));
        return r;
    }
}
