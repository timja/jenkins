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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * A {@link PersistenceStore} held in memory, for tests and as a reference for implementations.
 * {@link #as} gives views with other origins, standing in for other controllers sharing the store.
 * Events are delivered synchronously, after the change, on the thread making it.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public class InMemoryPersistenceStore implements PersistenceStore {

    private final Predicate<String> owns;
    private final InMemoryPersistenceStore root;
    private final String origin;
    private final Map<String, Entry> entries;
    private final List<Consumer<ChangeEvent>> listeners;
    /** Changes made but not yet delivered, in the order they were made: added to under the data lock. */
    private final Queue<ChangeEvent> outbox;
    /** Held while delivering, so that changes are delivered one at a time, in order, but not under the data lock. */
    private final Object delivering;
    /** Versions come from one counter, so a key that is deleted and recreated never repeats a version. */
    private long clock;

    /**
     * @param owns which keys this store serves
     */
    public InMemoryPersistenceStore(@NonNull Predicate<String> owns) {
        this.owns = owns;
        this.root = this;
        this.origin = UUID.randomUUID().toString();
        this.entries = new TreeMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.outbox = new ArrayDeque<>();
        this.delivering = new Object();
    }

    private InMemoryPersistenceStore(InMemoryPersistenceStore root, String origin) {
        this.owns = root.owns;
        this.root = root;
        this.origin = origin;
        this.entries = root.entries;
        this.listeners = root.listeners;
        this.outbox = root.outbox;
        this.delivering = root.delivering;
    }

    /**
     * A view of the same entries whose changes carry a different origin, as if made by another controller.
     */
    public InMemoryPersistenceStore as(@NonNull String origin) {
        return new InMemoryPersistenceStore(root, origin);
    }

    @Override
    @NonNull
    public String getOrigin() {
        return origin;
    }

    @Override
    @NonNull
    public AutoCloseable subscribe(@NonNull Consumer<ChangeEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Delivers the changes made so far in the order they were made, as {@link #subscribe} promises: two threads
     * committing one after the other must not have their changes delivered the other way round.
     */
    private void deliver() {
        synchronized (delivering) {
            while (true) {
                ChangeEvent e;
                synchronized (root) {
                    e = outbox.poll();
                }
                if (e == null) {
                    return;
                }
                for (Consumer<ChangeEvent> l : listeners) {
                    l.accept(e);
                }
            }
        }
    }

    @Override
    public boolean owns(@NonNull String key) {
        return owns.test(key);
    }

    @Override
    @CheckForNull
    public Entry read(@NonNull String key) {
        synchronized (root) {
            Entry e = entries.get(key);
            return e == null ? null : new Entry(e.content().clone(), e.version());
        }
    }

    @Override
    public long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws ConflictException {
        long version;
        synchronized (root) {
            Entry current = entries.get(key);
            long actual = current == null ? ABSENT : current.version();
            if (expectedVersion != ANY && expectedVersion != actual) {
                throw new ConflictException(key, expectedVersion, actual);
            }
            if (current != null && Arrays.equals(current.content(), content)) {
                return actual;
            }
            version = ++root.clock;
            entries.put(key, new Entry(content.clone(), version));
            outbox.add(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, version, origin));
        }
        deliver();
        return version;
    }

    @Override
    public void delete(@NonNull String key, long expectedVersion) throws ConflictException {
        long version;
        synchronized (root) {
            Entry current = entries.get(key);
            if (current == null) {
                return;
            }
            if (expectedVersion != ANY && expectedVersion != current.version()) {
                throw new ConflictException(key, expectedVersion, current.version());
            }
            entries.remove(key);
            version = ++root.clock;
            outbox.add(new ChangeEvent(ChangeEvent.Kind.DELETED, key, null, version, origin));
        }
        deliver();
    }

    @Override
    @NonNull
    public Set<String> children(@NonNull String parentKey) {
        String prefix = parentKey + "/";
        Set<String> names = new TreeSet<>();
        synchronized (root) {
            for (String key : entries.keySet()) {
                if (key.startsWith(prefix)) {
                    String rest = key.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    names.add(slash == -1 ? rest : rest.substring(0, slash));
                }
            }
        }
        return names;
    }

    @Override
    public List<Moved> move(@NonNull String fromPrefix, @NonNull String toPrefix) throws ConflictException {
        List<ChangeEvent> events = new ArrayList<>();
        List<Moved> moved = new ArrayList<>();
        synchronized (root) {
            for (String key : entries.keySet()) {
                if (key.equals(toPrefix) || key.startsWith(toPrefix + "/")) {
                    throw new ConflictException(key, ABSENT, entries.get(key).version());
                }
            }
            List<String> keys = new ArrayList<>();
            for (String key : entries.keySet()) {
                if (key.equals(fromPrefix) || key.startsWith(fromPrefix + "/")) {
                    keys.add(key);
                }
            }
            // shallower keys first, so a folder moves before its children
            keys.sort((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b));
            for (String key : keys) {
                String newKey = toPrefix + key.substring(fromPrefix.length());
                long version = ++root.clock;
                Entry old = entries.remove(key);
                entries.put(newKey, new Entry(old.content(), version));
                moved.add(new Moved(key, old.version(), newKey, version));
                events.add(new ChangeEvent(ChangeEvent.Kind.MOVED, newKey, key, version, origin));
            }
            outbox.addAll(events);
        }
        deliver();
        return moved;
    }

    /**
     * Snapshot of the stored keys, for assertions.
     */
    public Map<String, Long> versions() {
        Map<String, Long> r = new TreeMap<>();
        synchronized (root) {
            entries.forEach((k, v) -> r.put(k, v.version()));
        }
        return r;
    }
}
