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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FileParameterValue;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.FoldableAction;
import hudson.model.queue.QueueListener;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.queue.QueueIdStrategy;
import jenkins.model.queue.QueueStartGate;
import jenkins.security.HMACConfidentialKey;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shares the build queue between controllers through a {@link ClusterQueue}.
 *
 * <p>Every controller keeps its own {@link Queue}, which still applies quiet periods, blocking and executor matching;
 * the cluster queue is the source of truth for which items exist:
 * <ul>
 * <li>an item scheduled here is published; scheduling a job and parameters already waiting anywhere folds into that item
 * (causes merge, the earlier quiet period wins), so it builds once
 * <li>items published elsewhere are mirrored here, with the same id
 * <li>an item may only start on the controller which claims it ({@link QueueStartGate}); the others drop their mirror
 * <li>cancelling an item anywhere cancels it everywhere
 * </ul>
 * Only items of jobs are shared; parts of running builds (such as Pipeline {@code node} blocks) stay with their build.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class SharedQueue {

    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    /** Items scheduled by these actions wait for their originating controller (their completion is delivered there). */
    private static final String BUILD_TRIGGER_ACTION = "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerAction";

    /**
     * How long after its quiet period an item is left to the controller it was scheduled on, before others may start it.
     * A Pipeline's agents are only known once it runs, so by default a build starts where it was requested, if that controller can.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for tests and the script console */ long ORIGIN_GRACE_MILLIS =
            jenkins.util.SystemProperties.getLong(SharedQueue.class.getName() + ".originGraceMillis", 10_000L);

    private SharedQueue() {}

    @CheckForNull
    static ClusterQueue queue() {
        PersistenceStore store = PersistenceStores.get();
        return store == null ? null : store.queue();
    }

    private static String origin() {
        return PersistenceStores.get().getOrigin();
    }

    static boolean shared(@NonNull Queue.Task task) {
        return task instanceof Job<?, ?> && !(task instanceof Queue.TransientTask) && queue() != null;
    }

    /**
     * Items with equal keys fold into one, as the local queue does by task and {@link ParametersAction}.
     * Each parameter is encoded with explicit lengths (so no value can pass for several parameters); sensitive values
     * enter it only as a keyed hash, so that queue records reveal nothing of them.
     * Other actions which decide or take part in folding locally ({@link Queue.QueueAction}, such as a replay's, and
     * {@link FoldableAction}, such as a {@code build} step's) cannot be asked across controllers: they enter the key as
     * they are stored, so that a request carrying one folds only into an item carrying an equal one.
     */
    static String dedupKey(@NonNull Queue.Task task, @NonNull List<Action> actions) {
        List<byte[]> parameters = new ArrayList<>();
        List<byte[]> others = new ArrayList<>();
        for (Action a : actions) {
            if (a instanceof ParametersAction pa) {
                for (ParameterValue v : pa.getParameters()) {
                    // while queued a ParametersAction may hold nulls; one field cannot pass for a parameter's three
                    parameters.add(v == null ? encode("null") : encode(v));
                }
            } else if (a instanceof Queue.QueueAction || a instanceof FoldableAction && !(a instanceof CauseAction)) {
                others.add(encode(a.getClass().getName(), Queue.XSTREAM.toXML(a)));
            }
        }
        String name = ((Job<?, ?>) task).getFullName();
        if (parameters.isEmpty() && others.isEmpty()) {
            return name;
        }
        String key = parameters.isEmpty() ? name : name + "#" + digest(parameters);
        return others.isEmpty() ? key : key + "#actions:" + digest(others);
    }

    private static String digest(List<byte[]> parts) {
        parts.sort(Arrays::compare);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                digest.update(ByteBuffer.allocate(4).putInt(p.length).array());
                digest.update(p);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Keys the hash of sensitive parameter values; shared by the controllers once runtime keys are. */
    private static final HMACConfidentialKey SENSITIVE = new HMACConfidentialKey(SharedQueue.class, "sensitiveParameters");

    private static byte[] encode(ParameterValue v) {
        Object value = v.getValue();
        String text = value instanceof Secret secret ? secret.getPlainText() : String.valueOf(value);
        if (v.isSensitive()) {
            text = "mac:" + SENSITIVE.mac(text);
        }
        return encode(v.getName(), v.getClass().getName(), text);
    }

    private static byte[] encode(String... fields) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] b = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
            bytes.writeBytes(b);
        }
        return bytes.toByteArray();
    }

    /** pipeline-build-step's handler of downstream outcomes delivered across controllers ({@link RunningBuilds.Delivery}). */
    private static final String BUILD_STEP_DELIVERY = "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerDelivery";

    /**
     * The controller a downstream build must run on, or null if any may run it. A {@code build} step waiting for its
     * downstream build to complete gets the outcome delivered wherever the downstream build runs (Target 15), when
     * pipeline-build-step can take deliveries; one waiting only for the start ({@code waitForStart}) needs the started
     * build itself, so it stays pinned to the controller that scheduled it.
     */
    @CheckForNull
    private static String pinnedTo(@NonNull List<Action> actions) {
        for (Action a : actions) {
            if (a.getClass().getName().equals(BUILD_TRIGGER_ACTION)) {
                boolean delivered = ExtensionList.lookup(RunningBuilds.Delivery.class).stream()
                        .anyMatch(d -> d.getClass().getName().equals(BUILD_STEP_DELIVERY));
                if (!delivered || Queue.XSTREAM.toXML(a).contains("<waitForStart>true</waitForStart>")) {
                    return origin();
                }
            }
            if (a instanceof ParametersAction pa && pa.getParameters().stream().anyMatch(FileParameterValue.class::isInstance)) {
                return origin(); // the uploaded file is only here
            }
        }
        return null;
    }

    /** Whether a pin still holds: its controller is alive (a pin to one that died must not keep the item forever). */
    private static boolean pinHolds(@NonNull String pinnedTo) {
        return seemsAlive(pinnedTo); // as last read, off the queue lock
    }

    static String toXml(@NonNull List<? extends Action> actions) {
        return Queue.XSTREAM.toXML(new ArrayList<>(actions));
    }

    @SuppressWarnings("unchecked")
    static List<Action> fromXml(@NonNull String xml) {
        return (List<Action>) Queue.XSTREAM.fromXML(xml);
    }

    private static long dueOf(Queue.Item item) {
        return item instanceof Queue.WaitingItem wi ? wi.timestamp.getTimeInMillis() : System.currentTimeMillis();
    }

    /**
     * Merges a request folded into an item: its causes join the item's, and its other {@link FoldableAction}s (such as a
     * {@code build} step's, which the local queue folded into its copy) are added unless an equal one is there.
     */
    private static List<Action> merge(List<Action> into, List<? extends Action> from) {
        List<Cause> causes = new ArrayList<>();
        for (Action a : into) {
            if (a instanceof CauseAction ca) {
                causes.addAll(ca.getCauses());
            }
        }
        for (Action a : from) {
            if (a instanceof CauseAction ca) {
                causes.addAll(ca.getCauses());
            }
        }
        List<Action> merged = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (Action a : into) {
            if (!(a instanceof CauseAction)) {
                merged.add(a);
                present.add(Queue.XSTREAM.toXML(a));
            }
        }
        for (Action a : from) {
            if (a instanceof FoldableAction && !(a instanceof CauseAction) && present.add(Queue.XSTREAM.toXML(a))) {
                merged.add(a);
            }
        }
        merged.add(new CauseAction(causes));
        return merged;
    }

    @CheckForNull
    private static Queue.Item localItem(long id) {
        Queue.Item item = Queue.getInstance().getLocalItem(id);
        return item instanceof Queue.LeftItem ? null : item;
    }

    // ------------------------------------------------------------------ what is decided under the queue lock

    /*
     * The queue lock is held while items are scheduled and while the queue is maintained (QueueStartGate), so nothing
     * here talks to the store with it held: a slow store would stop the whole queue. Publishing goes through an outbox,
     * the gate decides from the records this controller knows (fed by the mirror), and claims are made off the lock.
     * One exception: a new item's id, when the pool of ids taken ahead ran dry (see Ids.nextId).
     */

    /** The records of items in this controller's queue, as last read or delivered. */
    static final Map<Long, ClusterQueue.Record> known = new ConcurrentHashMap<>();
    /** Local items being shared (published, or folded into an item from elsewhere): they do not start meanwhile. */
    static final Set<Long> sharing = ConcurrentHashMap.newKeySet();
    /** Items cancelled here while being shared, whose cancellation is shared once they are. */
    private static final Set<Long> cancelledWhileSharing = ConcurrentHashMap.newKeySet();
    /** Items whose record is being looked up, nothing being known of it here. */
    private static final Set<Long> reconciling = ConcurrentHashMap.newKeySet();
    /** Items with a claim under way. */
    private static final Set<Long> claiming = ConcurrentHashMap.newKeySet();
    /** Items with a local id which may start here: the store answered, and their job's exclusive lease is held ({@link QueueStartGate#claimLocal}). */
    private static final Set<Long> readyLocal = ConcurrentHashMap.newKeySet();

    /**
     * A few threads: items are shared independently, so one waiting on a slow write does not hold up the others; the
     * changes of one item go through one thread, in order (its publication before the folds into it).
     */
    private static final ExecutorService[] outbox = new ExecutorService[4];

    static {
        for (int i = 0; i < outbox.length; i++) {
            outbox[i] = Executors.newSingleThreadExecutor(new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue outbox " + i));
        }
    }

    private static ExecutorService outbox(long id) {
        return outbox[(int) Math.floorMod(id, (long) outbox.length)];
    }

    private static final ExecutorService claims = Executors.newFixedThreadPool(4, new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue claims"));

    /** Records a record, unless it is older than the one known (records only move on: waiting, claimed, started). */
    static void know(@NonNull ClusterQueue.Record r) {
        known.merge(r.id(), r, (old, now) -> rank(now) >= rank(old) ? now : old);
    }

    private static int rank(ClusterQueue.Record r) {
        return switch (r.state()) {
            case WAITING -> 0;
            case CLAIMED -> 1;
            case STARTED, CANCELLED -> 2;
        };
    }

    /**
     * Records a record read after a claim. Claimed here, the local item first takes its actions as claimed, before the
     * gate (which decides from what is known) lets it start: a fold made elsewhere just before may not have been
     * mirrored yet. Both under the queue lock, so that a late mirrored change does not put older actions back between.
     */
    private static void knowClaimed(@NonNull ClusterQueue.Record now) {
        if (now.state() != ClusterQueue.State.CLAIMED || !origin().equals(now.claimedBy())) {
            know(now);
            return;
        }
        Queue.withLock(() -> {
            Calendar due = new GregorianCalendar();
            due.setTimeInMillis(now.dueAt());
            Queue.getInstance().updateShared(now.id(), due, fromXml(now.actions()));
            know(now);
        });
    }

    /** Whether an item is known to be claimed here: its actions are then those it was claimed with (knowClaimed). */
    private static boolean knownClaimedHere(long id) {
        ClusterQueue.Record k = known.get(id);
        return k != null && k.state() == ClusterQueue.State.CLAIMED && origin().equals(k.claimedBy());
    }

    /** Live origins as last read, refreshed off the lock; null if unknown (taken as everyone alive). */
    private static volatile Set<String> live;
    private static volatile long liveAt;
    private static final java.util.concurrent.atomic.AtomicBoolean liveRefreshing = new java.util.concurrent.atomic.AtomicBoolean();

    /** Whether an origin was alive when last asked; asks again, off the lock, when that is a few seconds old. */
    private static boolean seemsAlive(String origin) {
        if (System.currentTimeMillis() - liveAt > 2_000 && liveRefreshing.compareAndSet(false, true)) {
            claims.submit(() -> {
                try {
                    live = PersistenceStores.get().liveOrigins();
                    liveAt = System.currentTimeMillis();
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Cannot read which controllers are alive", e);
                } finally {
                    liveRefreshing.set(false);
                }
            });
        }
        Set<String> l = live;
        return l == null || l.contains(origin);
    }

    /** Why a shared item does not start here. */
    static final class Blocked extends CauseOfBlockage {
        private final String text;

        Blocked(String text) {
            this.text = text;
        }

        @Override
        public String getShortDescription() {
            return text;
        }
    }

    /** Removes an item from this controller's queue because it was claimed, started or cancelled elsewhere. */
    private static void dropLocal(long id) {
        if (PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().cancelLocal(id))) {
            LOGGER.fine(() -> "Dropped queue item " + id + ", taken or cancelled elsewhere");
            droppedHere.add(id);
        }
    }

    /**
     * Items dropped here because they were taken or cancelled elsewhere: should the controller which took one go away
     * before starting it, the store puts it back, under the same id, and it is mirrored again (Mirror.create).
     */
    private static final Set<Long> droppedHere = java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 10_000;
        }
    }));

    // ------------------------------------------------------------------ local changes → cluster

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Publisher extends QueueListener {

        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            if (PersistenceStores.isApplyingRemoteChange() || !shared(wi.task)) {
                return;
            }
            if (wi.getId() >= Ids.LOCAL_RANGE) {
                // a local id, taken while the cluster sequence was unreachable: another controller may have taken the
                // same one, so it is never shared; it stays (and builds) here
                return;
            }
            // under the queue lock (schedule2): only prepared here, shared by the outbox; it does not start meanwhile
            List<Action> actions = List.copyOf(wi.getActions());
            ClusterQueue.Record record = record(wi, actions);
            sharing.add(wi.getId());
            outbox(wi.getId()).submit(() -> share(wi, record, actions));
        }

        private static ClusterQueue.Record record(Queue.Item item, List<Action> actions) {
            String key = dedupKey(item.task, actions);
            if (keptApart(actions)) {
                key += "#apart:" + item.getId(); // folds into nothing
            }
            return new ClusterQueue.Record(item.getId(), ((Job<?, ?>) item.task).getFullName(),
                    key, toXml(actions), dueOf(item), ClusterQueue.State.WAITING, origin(), pinnedTo(actions), null, 0);
        }

        /**
         * Whether the local queue would keep a request apart even from an item with equal actions: one of its
         * {@link Queue.QueueAction}s (such as a replay's) asks for a new item whatever is queued.
         */
        private static boolean keptApart(List<Action> actions) {
            for (Action a : actions) {
                if (a instanceof Queue.QueueAction qa && qa.shouldSchedule(new ArrayList<>(actions))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Off the lock: looks up the record of an item nothing is known of here (restored from before a restart, or
         * whose publication failed, perhaps after the store had it), or publishes the item if the store has none.
         */
        static void reconcile(long id) {
            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                ClusterQueue.Record r = queue().get(id);
                if (r != null) {
                    know(r);
                    return;
                }
                Queue.Item item = localItem(id);
                if (item != null && sharing.add(id)) {
                    List<Action> actions = List.copyOf(item.getActions());
                    share(item, record(item, actions), actions);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Cannot look up queue item " + id + "; not starting it for now", e);
            } finally {
                reconciling.remove(id);
                Queue.getInstance().scheduleMaintenance();
            }
        }

        private static void share(Queue.Item item, ClusterQueue.Record record, List<Action> actions) {
            long id = item.getId();
            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) { // to see the job when mirroring the item folded into
                ClusterQueue cq = queue();
                ClusterQueue.Record stored = cq.publish(record);
                know(stored);
                if (stored.id() != item.getId()) {
                    // the same job and parameters were already waiting (scheduled elsewhere at the same time): fold into
                    // that item, then drop this one, which has not started (the gate held it)
                    LOGGER.fine(() -> item + " folds into queue item " + stored.id() + " from " + stored.origin());
                    if (!fold(cq, stored.id(), actions, dueOf(item), record.pinnedTo())) {
                        // taken meanwhile: this one is kept, and, nothing being known under its id, the gate has it
                        // published again (as an item of its own, or folded into another waiting one)
                        return;
                    }
                    // requests folded into this one here meanwhile, shared after this, go where it went
                    foldedInto.put(id, stored.id());
                    // by id: with no quiet period, it may be blocked or buildable by now
                    PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().cancelLocal(id));
                    outbox(id).submit(() -> foldedInto.remove(id)); // after those: none come once it is cancelled
                    Mirror.mirror(stored.id());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to share " + item + "; it does not start until it is", e);
            } finally {
                sharing.remove(id);
                if (cancelledWhileSharing.remove(id)) {
                    shareCancellation(id, 0); // published meanwhile (or not: then there is nothing to cancel)
                }
                Queue.getInstance().scheduleMaintenance();
            }
        }

        /**
         * Merges a request into a waiting item, compare-and-set: one fold elsewhere at the same time is kept.
         *
         * @param pinTo pins the item to this controller, unless it is pinned already: the request must run here
         * @return whether the request was merged into it
         */
        private static boolean fold(ClusterQueue cq, long id, List<Action> actions, long due, @CheckForNull String pinTo) throws IOException {
            for (int attempt = 0; attempt < 20; attempt++) {
                ClusterQueue.Record now = cq.get(id);
                if (now == null || now.state() != ClusterQueue.State.WAITING) {
                    if (pinTo != null && now != null && !pinTo.equals(now.claimedBy())) {
                        LOGGER.info(() -> "Queue item " + id + " was taken by " + now.claimedBy() + " before a request needing this controller was folded into it");
                    }
                    return false; // started (or cancelled) meanwhile, as a request folded a moment later would have found
                }
                if (cq.update(id, now.actions(), toXml(merge(fromXml(now.actions()), actions)), Math.min(now.dueAt(), due), pinTo)) {
                    if (pinTo != null && now.pinnedTo() != null && !pinTo.equals(now.pinnedTo())) {
                        LOGGER.info(() -> "Queue item " + id + " is pinned to " + now.pinnedTo() + "; a request needing this controller was folded into it");
                    }
                    return true;
                }
            }
            LOGGER.warning(() -> "Queue item " + id + " kept changing; a request folded into it may lack its cause");
            return false;
        }

        /** Local items folded into an item from elsewhere when shared, by id: the item folded into, by the time it was. */
        private static final Map<Long, Long> foldedInto = new ConcurrentHashMap<>();

        @Override
        public void onFolded(Queue.Item item, List<Action> request) {
            if (PersistenceStores.isApplyingRemoteChange() || !shared(item.task)) {
                return;
            }
            long id = item.getId();
            List<Action> actions = List.copyOf(request);
            long due = dueOf(item);
            String pinTo = pinnedTo(actions);
            // after the item's publication (the same outbox thread): only the request is merged into what is stored
            outbox(id).submit(() -> {
                try {
                    fold(queue(), foldedInto.getOrDefault(id, id), actions, due, pinTo);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to share changes to queue item " + id, e);
                }
            });
        }

        @Override
        public void onLeft(Queue.LeftItem li) {
            known.remove(li.getId());
            notClaimed.remove(li.getId());
            readyLocal.remove(li.getId());
            if (li.isCancelled() && li.task instanceof Job<?, ?> job) {
                // what a claim here held of its job's exclusive lease (not under the queue lock, which this holds); one
                // still under way lets go of it when it ends
                long id = li.getId();
                if (claiming.contains(id)) {
                    leftWhileClaiming.add(id);
                    if (!claiming.contains(id)) {
                        leftWhileClaiming.remove(id); // it ended meanwhile: released below
                    }
                }
                Timer.get().submit(() -> releaseExclusive(job, claimHolder(id)));
            }
            if (li.isCancelled() && shared(li.task)) {
                long id = li.getId();
                Timer.get().submit(() -> Reservations.release(id)); // a store call: not under the queue lock
            }
            if (!li.isCancelled() || PersistenceStores.isApplyingRemoteChange() || !shared(li.task)) {
                return;
            }
            // while it is being published, the store may not have it yet: share() cancels it once it does
            cancelledWhileSharing.add(li.getId());
            if (sharing.contains(li.getId()) || !cancelledWhileSharing.remove(li.getId())) {
                return; // share() cancels it, or did already
            }
            // not under the queue lock, which Queue.cancel holds here: a slow store would stop the whole queue
            shareCancellation(li.getId(), 0);
        }

        private static void shareCancellation(long id, int attempt) {
            Timer.get().schedule(() -> {
                try {
                    ClusterQueue cq = queue();
                    if (cq != null) {
                        cq.cancel(id);
                    }
                } catch (IOException | RuntimeException e) {
                    if (attempt < 10) {
                        LOGGER.log(Level.FINE, "Failed to share the cancellation of queue item " + id + "; retrying", e);
                        shareCancellation(id, attempt + 1);
                    } else {
                        LOGGER.log(Level.WARNING, "Failed to share the cancellation of queue item " + id, e);
                    }
                }
            }, attempt == 0 ? 0 : 1L << Math.min(attempt, 6), TimeUnit.SECONDS);
        }
    }

    /**
     * An exclusive lease held here, and what holds it here: {@code q:<id>} for a queue item claimed here, {@code r:<number>}
     * for a build running here. The store's lease is this controller's, whichever build it is for: it is released once
     * nothing here holds it, so that a build finalizing does not release it under the next build of the job (claimed as
     * soon as the first one completed), nor a build adopted here run beside another.
     */
    private record Hold(long epoch, Set<String> holders) {}

    /** Exclusive leases held here on jobs which do not allow concurrent builds: lease key → its hold; changed under {@link #lockOf}. */
    private static final Map<String, Hold> exclusive = new ConcurrentHashMap<>();
    private static final Map<String, Object> exclusiveLocks = new ConcurrentHashMap<>();
    /**
     * Build holders of exclusive leases whose build is being adopted here and not resumed yet, by lease key; changed
     * under {@link #lockOf}. Until it is resumed nothing here shows the build running, so a claim must not join its hold.
     */
    private static final Map<String, Set<String>> adopting = new ConcurrentHashMap<>();

    /** One lock per lease key: taking and releasing the lease (in the store) is done under it, never under the queue lock. */
    private static Object lockOf(String key) {
        return exclusiveLocks.computeIfAbsent(key, k -> new Object());
    }

    private static String claimHolder(long id) {
        return "q:" + id;
    }

    private static String buildHolder(int number) {
        return "r:" + number;
    }

    @CheckForNull
    private static String exclusiveKey(Job<?, ?> job) {
        String jobKey = PersistenceStores.keyFor(job.getRootDir());
        return jobKey == null ? null : jobKey + "/" + RunningBuilds.PREFIX + "/exclusive";
    }

    /**
     * Takes the exclusive lease on a job which does not allow concurrent builds, so that only one controller runs it at a time.
     *
     * @param holder what it is held for here ({@link #claimHolder}, {@link #buildHolder})
     * @return true if this controller holds it (or the job allows concurrent builds, or there are no leases)
     */
    private static boolean acquireExclusive(Job<?, ?> job, String holder) throws IOException {
        BuildLeases leases = PersistenceStores.get().leases();
        String key = exclusiveKey(job);
        if (leases == null || key == null || !(job instanceof Queue.Task task) || task.isConcurrentBuild()) {
            return true;
        }
        synchronized (lockOf(key)) {
            Hold hold = exclusive.get(key);
            if (hold != null && holder.startsWith("q:") && adopting.containsKey(key)) {
                return false; // a build of the job is being adopted here: the queue here would not see it running yet
            }
            if (hold == null) {
                long epoch = leases.acquire(key);
                if (epoch == 0) {
                    return false;
                }
                hold = new Hold(epoch, new HashSet<>());
                exclusive.put(key, hold);
            }
            hold.holders().add(holder);
            return true;
        }
    }

    /** Lets go of what a holder held here of a job's exclusive lease, releasing the lease once nothing here holds it. */
    private static void releaseExclusive(Job<?, ?> job, String holder) {
        String key = exclusiveKey(job);
        if (key == null || !exclusive.containsKey(key)) {
            return;
        }
        synchronized (lockOf(key)) {
            notAdopting(key, holder);
            Hold hold = exclusive.get(key);
            if (hold == null || !hold.holders().remove(holder) || !hold.holders().isEmpty()) {
                return;
            }
            exclusive.remove(key);
            release(key, hold.epoch(), 0);
        }
    }

    /**
     * Releases an exclusive lease nothing here holds any more, under {@link #lockOf}; retried if the store fails, as the
     * lease of a live controller never lapses and would block the job everywhere. A retry releases nothing if the job is
     * held here again meanwhile (by the same lease, released with that hold).
     */
    private static void release(String key, long epoch, int attempt) {
        BuildLeases leases = PersistenceStores.get().leases();
        if (leases == null || exclusive.containsKey(key)) {
            return;
        }
        try {
            leases.release(key, epoch);
        } catch (IOException | RuntimeException e) {
            if (attempt < 10) {
                LOGGER.log(Level.INFO, "Failed to release " + key + "; retrying", e);
                Timer.get().schedule(() -> {
                    synchronized (lockOf(key)) {
                        release(key, epoch, attempt + 1);
                    }
                }, Math.min(60, 1L << attempt), TimeUnit.SECONDS);
            } else {
                LOGGER.log(Level.WARNING, "Failed to release " + key, e);
            }
        }
    }

    private static void notAdopting(String key, String holder) {
        adopting.computeIfPresent(key, (k, holders) -> holders.remove(holder) && holders.isEmpty() ? null : holders);
    }

    /**
     * Takes over the exclusive lease of a job before adopting one of its builds (the previous holder is gone).
     *
     * @return false if another controller holds it (it started a newer build meanwhile), or another build of the job is
     *     claimed or runs here: the build must not resume now
     */
    @Restricted(NoExternalUse.class)
    public static boolean adoptExclusive(@NonNull Job<?, ?> job, int number) throws IOException {
        String key = exclusiveKey(job);
        if (key == null) {
            return acquireExclusive(job, buildHolder(number));
        }
        synchronized (lockOf(key)) {
            Hold hold = exclusive.get(key);
            if (hold != null && hold.holders().stream().anyMatch(h -> !h.equals(buildHolder(number)))) {
                return false; // the lease is this controller's, but for another build of the job
            }
            if (!acquireExclusive(job, buildHolder(number))) {
                return false;
            }
            if (exclusive.containsKey(key)) {
                adopting.computeIfAbsent(key, k -> new HashSet<>()).add(buildHolder(number)); // until resumedExclusive
            }
            return true;
        }
    }

    /**
     * Tells that a build adopted with {@link #adoptExclusive} was resumed (it is loaded, so the queue here sees it
     * running): items of its job claimed here may share its hold from now on (to be blocked by it locally).
     */
    @Restricted(NoExternalUse.class)
    public static void resumedExclusive(@NonNull Job<?, ?> job, int number) {
        String key = exclusiveKey(job);
        if (key != null && adopting.containsKey(key)) {
            synchronized (lockOf(key)) {
                notAdopting(key, buildHolder(number));
            }
        }
    }

    /**
     * Gives up the exclusive lease taken by {@link #adoptExclusive} for an adoption that did not happen after all (if it
     * was taken), so that the job is not blocked everywhere by this controller, which does not run that build.
     */
    @Restricted(NoExternalUse.class)
    public static void abandonExclusive(@NonNull Job<?, ?> job, int number) {
        releaseExclusive(job, buildHolder(number));
    }

    /**
     * Whether a claim that failed with an exception went through after all.
     *
     * @return null if that is not known either (the store cannot be read)
     */
    @CheckForNull
    private static Boolean claimedHere(ClusterQueue cq, long id) {
        try {
            ClusterQueue.Record r = cq.get(id);
            return r != null && origin().equals(r.claimedBy());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Items whose controller is starting cloud agents for them (Target 9). With the shared queue, the controller an item
     * was scheduled on provisions for it (it alone counts it as demand), but starting an agent can take longer than the
     * origin's head start; without a reservation, another controller with a free executor would take the item, and the
     * agent started for it would have nothing to do. A reservation is a store entry, {@code queue/reserved/<id>}, holding
     * the controller's origin; others wait for it while that controller is alive, for at most {@link #RESERVATION_MILLIS}.
     * It is a hint, not a fence: a controller claiming the item at the moment it is reserved may still take it, and the
     * agent started for it then idles until its cloud's retention removes it.
     */
    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Reservations extends hudson.slaves.CloudProvisioningListener {

        static String key(long id) {
            return "queue/reserved/" + id;
        }

        /** The origin of the controller holding a current reservation of an item, or null. */
        @CheckForNull
        static String holder(long id) throws IOException {
            PersistenceStore store = PersistenceStores.get();
            PersistenceStore.Entry e = store == null ? null : store.read(key(id));
            if (e == null) {
                return null;
            }
            String[] parts = new String(e.content(), StandardCharsets.UTF_8).split(" ", 2);
            if (parts.length < 2 || System.currentTimeMillis() - Long.parseLong(parts[1].trim()) > RESERVATION_MILLIS) {
                return null;
            }
            return parts[0];
        }

        static void release(long id) {
            PersistenceStore store = PersistenceStores.get();
            if (store != null) {
                try {
                    store.delete(key(id), PersistenceStore.ANY);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Could not release the reservation of queue item " + id);
                }
            }
        }

        @Override
        public void onStarted(hudson.slaves.Cloud cloud, hudson.model.Label label, Collection<hudson.slaves.NodeProvisioner.PlannedNode> plannedNodes) {
            ClusterQueue cq = queue();
            PersistenceStore store = PersistenceStores.get();
            if (cq == null || store == null) {
                return;
            }
            int executors = plannedNodes.stream().mapToInt(p -> p.numExecutors).sum();
            String me = origin();
            for (Queue.BuildableItem bi : Queue.getInstance().getBuildableItems()) {
                if (executors <= 0) {
                    break;
                }
                // demand is counted per part of the task (as LoadStatistics does): any part needing this label
                int needing = 0;
                for (SubTask st : bi.task.getSubTasks()) {
                    if (Objects.equals(label, bi.getAssignedLabelFor(st))) {
                        needing++;
                    }
                }
                if (!shared(bi.task) || needing == 0) {
                    continue;
                }
                try {
                    ClusterQueue.Record r = cq.get(bi.getId());
                    if (r == null || r.claimedBy() != null || !provisionsHere(r, System.currentTimeMillis())) {
                        continue;
                    }
                    String reservedBy = holder(bi.getId());
                    if (reservedBy != null && (me.equals(reservedBy) || !gone(reservedBy))) {
                        continue;
                    }
                    store.write(key(bi.getId()), (me + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                    executors -= needing;
                    LOGGER.fine(() -> "Reserved " + bi + " while starting an agent for it on " + cloud.name);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Could not reserve " + bi);
                }
            }
        }
    }

    /** Whether a controller which reserved an item is known to be gone (its reservation then holds nothing up). */
    private static boolean gone(String origin) throws IOException {
        Set<String> live = PersistenceStores.get().liveOrigins();
        return live != null && !live.contains(origin);
    }

    /**
     * Whether this controller provisions for an item: the one that claimed it, or it is pinned to; otherwise its origin
     * while alive (it goes first, and may hold the agents the build needs); otherwise, any.
     */
    static boolean provisionsHere(@NonNull ClusterQueue.Record r, long now) throws IOException {
        String me = origin();
        if (r.claimedBy() != null) {
            return me.equals(r.claimedBy());
        }
        if (r.pinnedTo() != null && pinHolds(r.pinnedTo())) {
            return me.equals(r.pinnedTo());
        }
        if (me.equals(r.origin())) {
            return true;
        }
        Set<String> live = PersistenceStores.get().liveOrigins();
        if (live == null) {
            // membership unknown: as the gate decides, its controller goes first, any other after a while
            return now > r.dueAt() + ORIGIN_GRACE_MILLIS && !reservedElsewhere(r.id(), me, live);
        }
        // its controller is gone: one of the others provisions, the same one whichever of them asks (unless another
        // reserved it first, before a controller joining changed which one that is)
        return !live.contains(r.origin()) && me.equals(provisioner(r.id(), live)) && !reservedElsewhere(r.id(), me, live);
    }

    /** Whether another controller, not known to be gone, holds a reservation of an item: it is starting an agent for it. */
    private static boolean reservedElsewhere(long id, String me, @CheckForNull Set<String> live) throws IOException {
        String holder = Reservations.holder(id);
        return holder != null && !holder.equals(me) && (live == null || live.contains(holder));
    }

    /** How long a reservation holds at most (the agent may never come up). */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for the script console")
    public static /* non-final for tests and the script console */ long RESERVATION_MILLIS =
            SystemProperties.getLong(SharedQueue.class.getName() + ".reservationMillis", 5 * 60_000L);

    /** The live controller which provisions for an item whose controller is gone: highest hash of item and controller. */
    @CheckForNull
    static String provisioner(long id, @NonNull Set<String> live) {
        String chosen = null;
        int best = 0;
        for (String o : new TreeSet<>(live)) {
            int h = (o + "#" + id).hashCode();
            if (chosen == null || h > best) {
                chosen = o;
                best = h;
            }
        }
        return chosen;
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Gate implements QueueStartGate {
        /** Recent provisioning answers per item id: load statistics ask for every label, every few seconds. */
        private final Map<Long, Map.Entry<Long, Boolean>> provisioning = new ConcurrentHashMap<>();

        /** Only one controller provisions for an item: see {@link #provisionsHere}. */
        @Override
        public boolean countsForProvisioning(@NonNull Queue.BuildableItem item) {
            if (!shared(item.task)) {
                return true;
            }
            long now = System.currentTimeMillis();
            Map.Entry<Long, Boolean> cached = provisioning.get(item.getId());
            if (cached != null && now - cached.getKey() < 2_000) {
                return cached.getValue();
            }
            boolean answer;
            try {
                ClusterQueue.Record r = queue().get(item.getId());
                answer = r == null || provisionsHere(r, now);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Cannot tell who provisions for " + item);
                answer = true;
            }
            if (provisioning.size() > 10_000) {
                provisioning.clear();
            }
            provisioning.put(item.getId(), Map.entry(now, answer));
            return answer;
        }

        @Override
        public CauseOfBlockage mayStart(@NonNull Queue.BuildableItem item) {
            if (!shared(item.task)) {
                return null;
            }
            // under the queue lock: from what is known only; the store is asked off the lock (claim below)
            long id = item.getId();
            if (id >= Ids.LOCAL_RANGE) {
                // a local id, never shared (see Publisher.onEnterWaiting): it builds here, once the store answers (its
                // build number is taken there, after it has left the queue) and with its job's exclusive lease
                if (readyLocal.contains(id)) {
                    return null;
                }
                if (claiming.add(id)) {
                    Job<?, ?> job = (Job<?, ?>) item.task;
                    claims.submit(() -> claimLocal(job, id));
                }
                String why = notClaimed.get(id);
                return new Blocked(why != null ? why : "Waiting for the persistence store");
            }
            if (sharing.contains(id)) {
                return new Blocked("Being shared with the other controllers");
            }
            ClusterQueue.Record r = known.get(id);
            if (r == null) {
                // restored from before a restart, or its publication failed (perhaps after the store had it): whether
                // it may start depends on its record, if any; during a store outage it waits rather than risk a duplicate
                if (reconciling.add(id)) {
                    outbox(id).submit(() -> Publisher.reconcile(id));
                }
                return new Blocked("Being checked with the other controllers");
            }
            String me = origin();
            if (me.equals(r.claimedBy()) && r.state() == ClusterQueue.State.CLAIMED) {
                return null;
            }
            if (r.state() != ClusterQueue.State.WAITING) {
                // taken elsewhere, or started here already: an item started here has left the queue, so a copy still in
                // it is stale (e.g. restored by a reload from a queue saved before it started)
                Timer.get().submit(() -> dropLocal(id));
                return new Blocked(r.state() == ClusterQueue.State.STARTED && me.equals(r.claimedBy()) ? "Started already" : "Starting on another controller");
            }
            if (r.pinnedTo() != null && !me.equals(r.pinnedTo()) && pinHolds(r.pinnedTo())) {
                return new Blocked("Waiting for the controller that scheduled it (it waits for the result)");
            }
            if (!me.equals(r.origin()) && System.currentTimeMillis() < r.dueAt() + ORIGIN_GRACE_MILLIS && seemsAlive(r.origin())) {
                // the controller it was requested on goes first (e.g. it may hold the agents the build needs)
                return new Blocked("Waiting for the controller it was scheduled on");
            }
            Job<?, ?> job = (Job<?, ?>) item.task;
            if (claiming.add(id)) {
                claims.submit(() -> claim(job, id));
            }
            String why = notClaimed.get(id);
            return new Blocked(why != null ? why : "Being claimed for this controller");
        }

        /**
         * Off the lock: claims an item for this controller (first taking its job's exclusive lease, for a job allowing
         * one build at a time), then has the queue look at it again.
         */
        private static void claim(Job<?, ?> job, long id) {
            ClusterQueue cq = queue();
            String holder = claimHolder(id);
            boolean changed = true;
            try {
                String me = origin();
                ClusterQueue.Record r = cq.get(id);
                // not if a request folded into it since the gate looked pinned it elsewhere (the claim checks it did not since)
                if (r != null && r.state() == ClusterQueue.State.WAITING && (r.pinnedTo() == null || me.equals(r.pinnedTo()))) {
                    String reservedBy = Reservations.holder(id);
                    if (reservedBy != null && !origin().equals(reservedBy)) {
                        if (!gone(reservedBy)) {
                            notClaimed.put(id, "Waiting for the agent the controller it was scheduled on is starting for it");
                            changed = false; // looked at again at the next maintenance, not at once
                            return;
                        }
                    }
                    if (!acquireExclusive(job, holder)) {
                        notClaimed.put(id, "A build is already in progress on another controller");
                        changed = false; // looked at again at the next maintenance, not at once
                        return;
                    }
                    notClaimed.remove(id);
                    if (cq.claim(id, r.pinnedTo())) {
                        LOGGER.fine(() -> "Claimed queue item " + id);
                    } else {
                        releaseExclusive(job, holder);
                    }
                } else if (r == null || !origin().equals(r.claimedBy())) {
                    releaseExclusive(job, holder); // held since a claim whose outcome was unknown, which went elsewhere
                }
                ClusterQueue.Record now = cq.get(id);
                if (now != null) {
                    knowClaimed(now);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cannot claim queue item " + id + "; not starting it for now", e);
                if (Boolean.FALSE.equals(claimedHere(cq, id))) {
                    releaseExclusive(job, holder);
                }
                // not known: the lease is kept, and the claim tried again (it finds it went through, or releases it)
            } finally {
                claiming.remove(id);
                if (leftWhileClaiming.remove(id)) {
                    releaseExclusive(job, holder); // cancelled meanwhile (Publisher.onLeft): the build will not start
                }
                if (changed) {
                    Queue.getInstance().scheduleMaintenance();
                }
            }
        }

        /**
         * Off the lock: readies an item with a local id (taken while the store was unreachable) to start here, as a claim
         * does a shared item: once the store answers, so that its build number can be taken there (failing that once it
         * has left the queue would lose it), and its job's exclusive lease taken, so that it does not run beside a build
         * of the job on another controller. The store may still fail between this and the build number being taken.
         */
        private static void claimLocal(Job<?, ?> job, long id) {
            String holder = claimHolder(id);
            boolean changed = true;
            try {
                PersistenceStores.Route route = PersistenceStores.route(new java.io.File(job.getRootDir(), "nextBuildNumber"));
                if (route != null) {
                    route.peek(); // whether the store answers
                }
                if (!acquireExclusive(job, holder)) {
                    notClaimed.put(id, "A build is already in progress on another controller");
                    changed = false; // looked at again at the next maintenance, not at once
                    return;
                }
                notClaimed.remove(id);
                readyLocal.add(id);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Cannot ready queue item " + id + " with a local id; not starting it for now", e);
                notClaimed.put(id, "Waiting for the persistence store");
                changed = false;
            } finally {
                claiming.remove(id);
                if (leftWhileClaiming.remove(id)) {
                    readyLocal.remove(id);
                    releaseExclusive(job, holder); // cancelled meanwhile (Publisher.onLeft): the build will not start
                }
                if (changed) {
                    Queue.getInstance().scheduleMaintenance();
                }
            }
        }
    }

    /** Items cancelled here while being claimed, by id: the claim lets go of what it holds once it ends. */
    private static final Set<Long> leftWhileClaiming = ConcurrentHashMap.newKeySet();

    /** Why items could not be claimed here (yet), by id, for the gate to say. */
    private static final Map<Long, String> notClaimed = new ConcurrentHashMap<>();

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Starts extends RunListener<Run<?, ?>> {
        @Override
        public void onFinalized(Run<?, ?> run) {
            // whether claimed here (with a shared queue) or adopted (also without one)
            releaseExclusive(run.getParent(), buildHolder(run.getNumber()));
        }

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            ClusterQueue cq = queue();
            long id = run.getQueueId();
            String key = exclusiveKey(run.getParent());
            if (key != null && id > 0 && exclusive.containsKey(key)) {
                synchronized (lockOf(key)) {
                    Hold hold = exclusive.get(key);
                    if (hold != null && hold.holders().remove(claimHolder(id))) {
                        hold.holders().add(buildHolder(run.getNumber())); // held by the build now, until it is finalized
                    }
                }
            }
            if (cq == null || id <= 0) {
                return;
            }
            recordStart(cq, id, run.getNumber(), 0);
        }

        /**
         * Marks the item started, retrying while the store is unreachable: an item left claimed would be put back into
         * the queue (and built again) if this controller later went away (Target 14).
         */
        private static void recordStart(ClusterQueue cq, long id, int number, int attempt) {
            try {
                ClusterQueue.Record r = cq.get(id);
                if (r != null && origin().equals(r.claimedBy())) {
                    cq.started(id, number);
                }
                Reservations.release(id);
            } catch (IOException | RuntimeException e) {
                if (attempt < 30) {
                    LOGGER.log(Level.INFO, "Failed to record that queue item " + id + " started; retrying", e);
                    jenkins.util.Timer.get().schedule(() -> recordStart(cq, id, number, attempt + 1),
                            Math.min(30, 1L << Math.min(attempt, 5)), java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    LOGGER.log(Level.WARNING, "Failed to record that queue item " + id + " started", e);
                }
            }
        }
    }

    /**
     * Ids unique across the cluster, so that an item has the same id on every controller.
     */
    @Restricted(NoExternalUse.class)
    @Extension(ordinal = 100)
    public static final class Ids extends QueueIdStrategy {
        private static final QueueIdStrategy LOCAL = new QueueIdStrategy.DefaultStrategy();
        /** Local ids taken while the cluster sequence is unreachable are offset by this much (2^52). */
        static final long LOCAL_RANGE = 1L << 52;
        /** The id of the mirror being created. */
        static final ThreadLocal<Long> MIRRORED = new ThreadLocal<>();

        @Override
        public long generateIdFor(@NonNull Queue.Task project, @NonNull List<Action> actions) {
            Long mirrored = MIRRORED.get();
            if (mirrored != null) {
                return mirrored;
            }
            ClusterQueue cq = queue();
            if (cq != null) {
                try {
                    return nextId(cq);
                } catch (IOException | RuntimeException e) {
                    // the store is unreachable: an id from a range the cluster sequence never reaches, so that it
                    // cannot collide with one handed out elsewhere (Target 14)
                    LOGGER.log(Level.WARNING, "Cannot get a cluster-wide queue id; using a local one", e);
                    return LOCAL_RANGE + LOCAL.generateIdFor(project, actions);
                }
            }
            return withoutSharedQueue().generateIdFor(project, actions);
        }

        /** The strategy used when there is no shared queue: whichever would have been used without this one. */
        private QueueIdStrategy withoutSharedQueue() {
            for (QueueIdStrategy other : hudson.ExtensionList.lookup(QueueIdStrategy.class)) {
                if (other != this) {
                    return other;
                }
            }
            return LOCAL;
        }

        /** Ids taken from the cluster sequence ahead of need, off the queue lock (unused ones are skipped numbers). */
        private record Pool(PersistenceStore store, BlockingQueue<Long> ids) {}

        private static volatile Pool pool;
        private static final int SPARE = 64;
        private static final java.util.concurrent.atomic.AtomicBoolean filling = new java.util.concurrent.atomic.AtomicBoolean();

        private static Pool pool() {
            PersistenceStore store = PersistenceStores.get();
            Pool p = pool;
            if (p == null || p.store() != store) {
                p = new Pool(store, new LinkedBlockingQueue<>()); // ids of another store's sequence are no use
                pool = p;
            }
            return p;
        }

        /** Off the lock: tops the pool up, with ids taken in one store call; at startup, and once it is half used. */
        static void fill(ClusterQueue cq) {
            Pool p = pool();
            if (p.ids().size() < SPARE / 2 && filling.compareAndSet(false, true)) {
                claims.submit(() -> {
                    try {
                        int n = SPARE - p.ids().size();
                        if (n > 0) {
                            for (long id : cq.nextIds(n)) {
                                p.ids().add(id);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Cannot take queue ids ahead", e);
                    } finally {
                        filling.set(false);
                    }
                });
            }
        }

        /** An id from the pool (refilled in the background), or from the store if the pool ran dry. */
        private static long nextId(ClusterQueue cq) throws IOException {
            Long id = pool().ids().poll();
            fill(cq);
            if (id != null) {
                return id;
            }
            // the pool ran dry (a burst of items larger than it, before it was refilled): a store call made under the
            // queue lock, as an exception (an id is needed now, and one from a local range would leave the item
            // unshared); the other is a shared job's build number (StoredBuildNumberAssigner)
            return cq.nextId();
        }

        @Override
        public void persist(@NonNull Queue.State queueState) {
            (queue() != null ? LOCAL : withoutSharedQueue()).persist(queueState);
        }

        @Override
        public void load(@NonNull Queue.State queueState) {
            (queue() != null ? LOCAL : withoutSharedQueue()).load(queueState);
        }
    }

    // ------------------------------------------------------------------ cluster changes → local queue

    /**
     * Applies changes to the cluster queue to this controller's queue, one at a time.
     */
    @Restricted(NoExternalUse.class)
    public static final class Mirror {
        private static Mirror instance;

        private final AutoCloseable subscription;
        private final BlockingQueue<ClusterQueue.Record> changes = new LinkedBlockingQueue<>();
        private final Thread worker;

        private Mirror(ClusterQueue cq) {
            subscription = cq.subscribe(changes::add);
            worker = new Thread(this::run, "Shared queue mirror");
            worker.setDaemon(true);
        }

        /** Once the queue is loaded, which clears it first (and would drop items mirrored before). */
        @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED, requires = Queue.LOADED)
        public static synchronized void start() throws IOException {
            stop();
            ClusterQueue cq = queue();
            if (cq == null) {
                return;
            }
            instance = new Mirror(cq);
            for (ClusterQueue.Record r : cq.pending()) {
                instance.changes.add(r);
            }
            instance.worker.start();
            Ids.fill(cq); // so that the first items do not wait for the store with the queue lock held
        }

        @Terminator
        public static synchronized void stop() {
            if (instance != null) {
                try {
                    instance.subscription.close();
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, null, e);
                }
                instance.worker.interrupt();
                instance = null;
            }
        }

        /** Whether every change received so far has been applied, for tests. */
        static synchronized boolean isIdle() {
            return instance == null || instance.changes.isEmpty() && instance.busy == 0;
        }

        private volatile int busy;

        private void run() {
            while (true) {
                ClusterQueue.Record r;
                try {
                    r = changes.take();
                } catch (InterruptedException e) {
                    return;
                }
                busy = 1;
                try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                    apply(r);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to apply queue change " + r, e);
                } finally {
                    busy = 0;
                }
            }
        }

        private static void apply(ClusterQueue.Record r) {
            String me = origin();
            if (localItem(r.id()) != null) {
                know(r); // for the gate, which decides from what is known
            }
            switch (r.state()) {
                case WAITING -> {
                    if (localItem(r.id()) != null) {
                        // e.g. another controller folded a request into it: take over its causes and quiet period
                        // (unless claimed here since: this change came late, and its actions are older)
                        Calendar due = new GregorianCalendar();
                        due.setTimeInMillis(r.dueAt());
                        List<Action> actions = fromXml(r.actions());
                        Queue.withLock(() -> {
                            if (!knownClaimedHere(r.id())) {
                                Queue.getInstance().updateShared(r.id(), due, actions);
                            }
                        });
                    } else if (!me.equals(r.origin()) || droppedHere.contains(r.id())) {
                        // our own items are recreated only if dropped: else absent here means they already left
                        create(r);
                    }
                }
                case CLAIMED, STARTED -> {
                    if (!me.equals(r.claimedBy())) {
                        dropLocal(r.id());
                    }
                }
                case CANCELLED -> dropLocal(r.id());
                default -> throw new IllegalStateException(r.toString());
            }
        }

        /** Mirrors a waiting item, if it is not here yet. */
        static void mirror(long id) {
            try {
                ClusterQueue.Record r = queue().get(id);
                if (r != null && r.state() == ClusterQueue.State.WAITING && localItem(id) == null) {
                    create(r);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to mirror queue item " + id, e);
            }
        }

        private static void create(ClusterQueue.Record event) {
            ClusterQueue.Record r;
            try {
                r = queue().get(event.id()); // the event may be stale: the item may have been claimed meanwhile
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot mirror queue item " + event.id(), e);
                return;
            }
            Queue.Item here = r == null ? null : Queue.getInstance().getLocalItem(r.id());
            if (r == null || r.state() != ClusterQueue.State.WAITING || here != null && !(here instanceof Queue.LeftItem && droppedHere.contains(r.id()))) {
                return; // e.g. it left here (an item put back after being dropped here is mirrored again)
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(r.task(), Job.class);
            if (job == null) {
                // its job may not be here yet: item changes are applied apart from queue changes
                if (awaitingJob.size() > 10_000) {
                    awaitingJob.clear(); // jobs which never came
                }
                awaitingJob.computeIfAbsent(r.task(), k -> ConcurrentHashMap.newKeySet()).add(r.id());
                job = Jenkins.get().getItemByFullName(r.task(), Job.class); // created meanwhile?
                if (job == null) {
                    LOGGER.fine(() -> "Not mirroring " + r + " until its job is here");
                    return;
                }
                awaitingJob.computeIfPresent(r.task(), (k, ids) -> ids.remove(r.id()) && ids.isEmpty() ? null : ids);
            }
            if (!(job instanceof Queue.Task task)) {
                return;
            }
            int quietPeriod = (int) Math.max(0, (r.dueAt() - System.currentTimeMillis() + 999) / 1000);
            know(r);
            Ids.MIRRORED.set(r.id());
            try {
                // never folded here: records the shared queue kept apart stay apart
                PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().scheduleShared(task, quietPeriod, fromXml(r.actions())));
            } finally {
                Ids.MIRRORED.remove();
            }
            droppedHere.remove(r.id());
        }

        /** Waiting items whose job was not here when they were mirrored, by job: they are once it comes. */
        private static final Map<String, Set<Long>> awaitingJob = new ConcurrentHashMap<>();

        @Restricted(NoExternalUse.class)
        @Extension
        public static final class JobArrivals extends hudson.model.listeners.ItemListener {
            @Override
            public void onCreated(hudson.model.Item item) {
                arrived(item.getFullName());
            }

            @Override
            public void onLocationChanged(hudson.model.Item item, String oldFullName, String newFullName) {
                arrived(newFullName);
            }

            private static void arrived(String fullName) {
                Set<Long> ids = awaitingJob.remove(fullName);
                if (ids != null) {
                    Timer.get().submit(() -> {
                        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                            ids.forEach(Mirror::mirror);
                        }
                    });
                }
            }
        }
    }

    // ------------------------------------------------------------------ queue items not (or no longer) here

    /**
     * An item of the shared queue which is not in this controller's queue, for {@code queue/item/<id>}.
     */
    @Restricted(NoExternalUse.class)
    @CheckForNull
    public static Queue.Item remoteItem(long id) {
        ClusterQueue cq = queue();
        if (cq == null) {
            return null;
        }
        ClusterQueue.Record r;
        try {
            r = cq.get(id);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Cannot look up queue item " + id, e);
            return null;
        }
        Job<?, ?> job = r == null ? null : Jenkins.get().getItemByFullName(r.task(), Job.class);
        if (!(job instanceof Queue.Task task)) {
            return null;
        }
        Queue.RemoteExecutable executable = null;
        CauseOfBlockage why = null;
        switch (r.state()) {
            case STARTED -> {
                String root = Jenkins.get().getRootUrl();
                executable = new Queue.RemoteExecutable(r.buildNumber(), (root == null ? "/" : root) + job.getUrl() + r.buildNumber() + "/");
            }
            case CLAIMED -> why = new Blocked("Starting on another controller");
            case WAITING -> why = new Blocked("Waiting on another controller");
            default -> { }
        }
        return new Queue.RemoteItem(task, id, fromXml(r.actions()), why, r.state() == ClusterQueue.State.CANCELLED, executable);
    }

    /**
     * Cancels an item of the shared queue which is not in this controller's queue.
     *
     * @return true if it was waiting and is now cancelled
     */
    @Restricted(NoExternalUse.class)
    public static boolean cancelRemote(long id) {
        ClusterQueue cq = queue();
        if (cq == null) {
            return false;
        }
        try {
            ClusterQueue.Record r = cq.get(id);
            Job<?, ?> job = r == null ? null : Jenkins.get().getItemByFullName(r.task(), Job.class);
            if (job == null || !job.hasPermission(hudson.model.Item.CANCEL)) {
                return false;
            }
            return cq.cancel(id);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot cancel queue item " + id, e);
            return false;
        }
    }
}
