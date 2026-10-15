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
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOGGER = Logger.getLogger(InMemoryPersistenceStore.class.getName());

    private final Predicate<String> owns;
    private final InMemoryPersistenceStore root;
    private final String origin;
    private final Map<String, Entry> entries;
    private final List<Consumer<ChangeEvent>> listeners;
    /** Changes made but not yet delivered, in the order they were made: added to under the data lock. */
    private final Queue<ChangeEvent> outbox;
    /** Held while delivering, so that changes are delivered one at a time, in order, but not under the data lock. */
    private final Object delivering;
    /** The thread delivering changes, guarded by {@link #delivering}. */
    private Thread draining;
    private final Map<String, long[]> leaseEpochs;
    private final Map<String, String> leaseOwners;
    private final Map<Long, ClusterQueue.Record> queueRecords;
    private final List<Consumer<ClusterQueue.Record>> queueListeners;
    /** Queue changes made but not yet delivered, in the order they were made: added to under the data lock. */
    private final Queue<ClusterQueue.Record> queueOutbox;
    /** Held while delivering queue changes, as {@link #delivering} is for the others. */
    private final Object deliveringQueue;
    /** The thread delivering queue changes, guarded by {@link #deliveringQueue}. */
    private Thread drainingQueue;
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
        this.leaseEpochs = new TreeMap<>();
        this.leaseOwners = new TreeMap<>();
        this.queueRecords = new TreeMap<>();
        this.queueListeners = new CopyOnWriteArrayList<>();
        this.queueOutbox = new ArrayDeque<>();
        this.deliveringQueue = new Object();
    }

    private InMemoryPersistenceStore(InMemoryPersistenceStore root, String origin) {
        this.owns = root.owns;
        this.root = root;
        this.origin = origin;
        this.entries = root.entries;
        this.listeners = root.listeners;
        this.outbox = root.outbox;
        this.delivering = root.delivering;
        this.leaseEpochs = root.leaseEpochs;
        this.leaseOwners = root.leaseOwners;
        this.queueRecords = root.queueRecords;
        this.queueListeners = root.queueListeners;
        this.queueOutbox = root.queueOutbox;
        this.deliveringQueue = root.deliveringQueue;
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
            if (root.draining == Thread.currentThread()) {
                return; // a listener made a change: the loop below delivers it once every listener had the current one
            }
            root.draining = Thread.currentThread();
            try {
                while (true) {
                    ChangeEvent e;
                    synchronized (root) {
                        e = outbox.poll();
                    }
                    if (e == null) {
                        return;
                    }
                    for (Consumer<ChangeEvent> l : listeners) {
                        try {
                            l.accept(e);
                        } catch (RuntimeException | LinkageError x) {
                            // the change is made: the other listeners still get it, and the caller is not told it failed
                            LOGGER.log(Level.WARNING, "A listener failed to handle " + e, x);
                        }
                    }
                }
            } finally {
                root.draining = null;
            }
        }
    }

    /** Delivers the queue changes made so far in the order they were made, as {@link ClusterQueue#subscribe} promises. */
    private void deliverQueue() {
        synchronized (deliveringQueue) {
            if (root.drainingQueue == Thread.currentThread()) {
                return; // a listener made a change: the loop below delivers it once every listener had the current one
            }
            root.drainingQueue = Thread.currentThread();
            try {
                while (true) {
                    ClusterQueue.Record r;
                    synchronized (root) {
                        r = queueOutbox.poll();
                    }
                    if (r == null) {
                        return;
                    }
                    for (Consumer<ClusterQueue.Record> l : queueListeners) {
                        l.accept(r);
                    }
                }
            } finally {
                root.drainingQueue = null;
            }
        }
    }

    /**
     * A shared queue held in memory, for tests. Not returned by {@link #queue} unless a subclass does so.
     */
    @NonNull
    public ClusterQueue clusterQueue() {
        return new ClusterQueue() {
            /** Under the data lock: queues a change for delivery, in commit order, once the lock is released. */
            private void fire(Record r) {
                queueOutbox.add(r);
            }

            private Record with(Record r, State state, String claimedBy, int buildNumber, String actions, long dueAt) {
                return new Record(r.id(), r.task(), r.dedupKey(), actions, dueAt, state, r.origin(), r.pinnedTo(), claimedBy, buildNumber);
            }

            @Override
            public long nextId() {
                synchronized (root) {
                    return ++root.clock;
                }
            }

            @Override
            @NonNull
            public Record publish(@NonNull Record item) {
                Record stored;
                synchronized (root) {
                    Record same = queueRecords.get(item.id());
                    if (same != null) {
                        return same;
                    }
                    for (Record r : queueRecords.values()) {
                        if (r.state() == State.WAITING && r.dedupKey().equals(item.dedupKey())) {
                            return r;
                        }
                    }
                    stored = item;
                    queueRecords.put(item.id(), item);
                    fire(stored);
                }
                deliverQueue();
                return stored;
            }

            @Override
            public boolean update(long id, @NonNull String expectedActions, @NonNull String actions, long dueAt, @CheckForNull String pinTo) {
                synchronized (root) {
                    Record r = queueRecords.get(id);
                    if (r == null || r.state() != State.WAITING || !r.actions().equals(expectedActions)) {
                        return false;
                    }
                    Record next = new Record(r.id(), r.task(), r.dedupKey(), actions, Math.min(r.dueAt(), dueAt), r.state(), r.origin(),
                            r.pinnedTo() != null ? r.pinnedTo() : pinTo, r.claimedBy(), r.buildNumber());
                    if (next.equals(r)) {
                        return true;
                    }
                    queueRecords.put(id, next);
                    fire(next);
                }
                deliverQueue();
                return true;
            }

            @Override
            public boolean claim(long id) {
                Record next;
                synchronized (root) {
                    Record r = queueRecords.get(id);
                    if (r == null || r.state() != State.WAITING) {
                        return r != null && origin.equals(r.claimedBy());
                    }
                    next = with(r, State.CLAIMED, origin, 0, r.actions(), r.dueAt());
                    queueRecords.put(id, next);
                    fire(next);
                }
                deliverQueue();
                return true;
            }

            @Override
            public void started(long id, int buildNumber) {
                Record next;
                synchronized (root) {
                    Record r = queueRecords.get(id);
                    if (r == null || !origin.equals(r.claimedBy())) {
                        return;
                    }
                    next = with(r, State.STARTED, origin, buildNumber, r.actions(), r.dueAt());
                    queueRecords.put(id, next);
                    fire(next);
                }
                deliverQueue();
            }

            @Override
            public boolean cancel(long id) {
                Record next;
                synchronized (root) {
                    Record r = queueRecords.get(id);
                    if (r == null || r.state() != State.WAITING && !(r.state() == State.CLAIMED && origin.equals(r.claimedBy()))) {
                        return false;
                    }
                    next = with(r, State.CANCELLED, null, 0, r.actions(), r.dueAt());
                    queueRecords.put(id, next);
                    fire(next);
                }
                deliverQueue();
                return true;
            }

            @Override
            public Record get(long id) {
                synchronized (root) {
                    return queueRecords.get(id);
                }
            }

            @Override
            @NonNull
            public List<Record> pending() {
                synchronized (root) {
                    return queueRecords.values().stream().filter(r -> r.state() == State.WAITING || r.state() == State.CLAIMED).toList();
                }
            }

            @Override
            @NonNull
            public AutoCloseable subscribe(@NonNull Consumer<Record> listener) {
                queueListeners.add(listener);
                return () -> queueListeners.remove(listener);
            }
        };
    }

    /**
     * Puts an item claimed by a controller which went away back into the queue, under the same id, as a store does once
     * it finds that controller gone; for tests.
     */
    public void requeue(long id) {
        synchronized (root) {
            ClusterQueue.Record r = queueRecords.get(id);
            if (r == null || r.state() != ClusterQueue.State.CLAIMED) {
                return;
            }
            ClusterQueue.Record next = new ClusterQueue.Record(r.id(), r.task(), r.dedupKey(), r.actions(), r.dueAt(), ClusterQueue.State.WAITING,
                    r.origin(), r.pinnedTo(), null, 0);
            queueRecords.put(id, next);
            queueOutbox.add(next);
        }
        deliverQueue();
    }

    /**
     * Views made by {@link #as} share the liveness of the store they were made from, so tests can override it once.
     */
    @Override
    public java.util.Set<String> liveOrigins() throws java.io.IOException {
        return root == this ? null : root.liveOrigins();
    }

    /**
     * Leases lapse when their owner is missing from {@link #liveOrigins} (when that is null, they never lapse).
     */
    @Override
    @NonNull
    public BuildLeases leases() {
        return new BuildLeases() {
            private boolean alive(String o) throws java.io.IOException {
                java.util.Set<String> live = liveOrigins();
                return live == null || live.contains(o);
            }

            @Override
            public long acquire(@NonNull String key) throws java.io.IOException {
                synchronized (root) {
                    String owner = leaseOwners.get(key);
                    if (owner != null && owner.equals(origin)) {
                        return leaseEpochs.get(key)[0];
                    }
                    if (owner != null && alive(owner)) {
                        return 0;
                    }
                    long epoch = ++root.clock;
                    leaseOwners.put(key, origin);
                    leaseEpochs.put(key, new long[] {epoch});
                    return epoch;
                }
            }

            @Override
            public boolean holds(@NonNull String key, long epoch) throws java.io.IOException {
                synchronized (root) {
                    // a lease lapses with its holder's liveness, as BuildLeases says
                    return held(key, epoch) && alive(origin);
                }
            }

            private boolean held(String key, long epoch) {
                return origin.equals(leaseOwners.get(key)) && leaseEpochs.get(key)[0] == epoch;
            }

            @Override
            public void release(@NonNull String key, long epoch) {
                synchronized (root) {
                    if (held(key, epoch)) {
                        leaseOwners.remove(key);
                        leaseEpochs.remove(key);
                    }
                }
            }
        };
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
            version = commitWrite(key, content, expectedVersion);
        }
        deliver();
        return version;
    }

    /** The lease check and the write in one step, as a store doing both in one transaction would. */
    @Override
    public long writeIfHeld(@NonNull String leaseKey, long epoch, @NonNull String key, @NonNull byte[] content, long expectedVersion)
            throws java.io.IOException {
        long version;
        synchronized (root) {
            if (!leases().holds(leaseKey, epoch)) {
                throw new LeaseLostException(leaseKey, epoch);
            }
            version = commitWrite(key, content, expectedVersion);
        }
        deliver();
        return version;
    }

    /** Under the data lock: writes, and queues the change for delivery. */
    private long commitWrite(String key, byte[] content, long expectedVersion) throws ConflictException {
        Entry current = entries.get(key);
        long actual = current == null ? ABSENT : current.version();
        if (expectedVersion != ANY && expectedVersion != actual) {
            throw new ConflictException(key, expectedVersion, actual);
        }
        if (current != null && Arrays.equals(current.content(), content)) {
            return actual;
        }
        long version = ++root.clock;
        entries.put(key, new Entry(content.clone(), version));
        outbox.add(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, version, origin));
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
