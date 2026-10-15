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
import hudson.Extension;
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
import hudson.model.queue.ScheduleResult;
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
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                    parameters.add(encode(v));
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

    @CheckForNull
    private static String pinnedTo(@NonNull List<Action> actions) {
        for (Action a : actions) {
            if (a.getClass().getName().equals(BUILD_TRIGGER_ACTION)) {
                return origin();
            }
            if (a instanceof ParametersAction pa && pa.getParameters().stream().anyMatch(FileParameterValue.class::isInstance)) {
                return origin(); // the uploaded file is only here
            }
        }
        return null;
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

    /** Merges the causes of {@code from} into {@code into}. */
    private static List<Action> mergeCauses(List<Action> into, List<? extends Action> from) {
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
        for (Action a : into) {
            if (!(a instanceof CauseAction)) {
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
     */

    /** The records of items in this controller's queue, as last read or delivered. */
    static final Map<Long, ClusterQueue.Record> known = new ConcurrentHashMap<>();
    /** Local items being shared (published, or folded into an item from elsewhere): they do not start meanwhile. */
    static final Set<Long> sharing = ConcurrentHashMap.newKeySet();
    /** Items with a claim under way. */
    private static final Set<Long> claiming = ConcurrentHashMap.newKeySet();

    /** A few threads: items are shared independently, so one waiting on a slow write does not hold up the others. */
    private static final ExecutorService outbox = Executors.newFixedThreadPool(4, new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue outbox"));
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
        Queue.Item item = localItem(id);
        if (item != null) {
            LOGGER.fine(() -> "Dropping " + item + ", taken or cancelled elsewhere");
            PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().cancel(item));
        }
    }

    // ------------------------------------------------------------------ local changes → cluster

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Publisher extends QueueListener {

        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            if (PersistenceStores.isApplyingRemoteChange() || !shared(wi.task)) {
                return;
            }
            // under the queue lock (schedule2): only prepared here, shared by the outbox; it does not start meanwhile
            List<Action> actions = List.copyOf(wi.getActions());
            ClusterQueue.Record record = new ClusterQueue.Record(wi.getId(), ((Job<?, ?>) wi.task).getFullName(),
                    dedupKey(wi.task, actions), toXml(actions), dueOf(wi), ClusterQueue.State.WAITING, origin(), pinnedTo(actions), null, 0);
            sharing.add(wi.getId());
            outbox.submit(() -> share(wi, record, actions));
        }

        private static void share(Queue.WaitingItem wi, ClusterQueue.Record record, List<Action> actions) {
            try {
                ClusterQueue cq = queue();
                ClusterQueue.Record stored = cq.publish(record);
                know(stored);
                if (stored.id() != wi.getId()) {
                    // the same job and parameters were already waiting (scheduled elsewhere at the same time): fold into
                    // that item, then drop this one, which has not started (the gate held it)
                    LOGGER.fine(() -> wi + " folds into queue item " + stored.id() + " from " + stored.origin());
                    fold(cq, stored.id(), actions, dueOf(wi));
                    PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().cancel(wi));
                    Mirror.mirror(stored.id());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to share " + wi + "; it stays local", e);
            } finally {
                sharing.remove(wi.getId());
                Queue.getInstance().scheduleMaintenance();
            }
        }

        /** Merges a request's causes into a waiting item, compare-and-set: one fold elsewhere at the same time is kept. */
        private static void fold(ClusterQueue cq, long id, List<Action> actions, long due) throws IOException {
            for (int attempt = 0; attempt < 20; attempt++) {
                ClusterQueue.Record now = cq.get(id);
                if (now == null || now.state() != ClusterQueue.State.WAITING) {
                    return; // started (or cancelled) meanwhile, as a request folded a moment later would have found
                }
                if (cq.update(id, now.actions(), toXml(mergeCauses(fromXml(now.actions()), actions)), Math.min(now.dueAt(), due))) {
                    return;
                }
            }
            LOGGER.warning(() -> "Queue item " + id + " kept changing; a request folded into it may lack its cause");
        }

        @Override
        public void onFolded(Queue.Item item) {
            if (PersistenceStores.isApplyingRemoteChange() || !shared(item.task)) {
                return;
            }
            long id = item.getId();
            String actions = toXml(item.getActions());
            long due = dueOf(item);
            outbox.submit(() -> {
                try {
                    queue().update(id, actions, due);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to share changes to queue item " + id, e);
                }
            });
        }

        @Override
        public void onLeft(Queue.LeftItem li) {
            known.remove(li.getId());
            if (!li.isCancelled() || PersistenceStores.isApplyingRemoteChange() || !shared(li.task)) {
                return;
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

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Gate implements QueueStartGate {
        @Override
        public CauseOfBlockage mayStart(@NonNull Queue.BuildableItem item) {
            if (!shared(item.task)) {
                return null;
            }
            // under the queue lock: from what is known only; the store is asked off the lock (claim below)
            long id = item.getId();
            if (sharing.contains(id)) {
                return new Blocked("Being shared with the other controllers");
            }
            ClusterQueue.Record r = known.get(id);
            if (r == null) {
                return null; // never shared (e.g. publishing failed)
            }
            String me = origin();
            if (me.equals(r.claimedBy())) {
                return null;
            }
            if (r.state() != ClusterQueue.State.WAITING) {
                Timer.get().submit(() -> dropLocal(id));
                return new Blocked("Starting on another controller");
            }
            if (r.pinnedTo() != null && !me.equals(r.pinnedTo())) {
                return new Blocked("Waiting for the controller that scheduled it (it waits for the result)");
            }
            if (!me.equals(r.origin()) && System.currentTimeMillis() < r.dueAt() + ORIGIN_GRACE_MILLIS && seemsAlive(r.origin())) {
                // the controller it was requested on goes first (e.g. it may hold the agents the build needs)
                return new Blocked("Waiting for the controller it was scheduled on");
            }
            if (claiming.add(id)) {
                claims.submit(() -> claim(id));
            }
            return new Blocked("Being claimed for this controller");
        }

        /** Off the lock: claims an item for this controller, then has the queue look at it again. */
        private static void claim(long id) {
            try {
                ClusterQueue cq = queue();
                ClusterQueue.Record r = cq.get(id);
                if (r != null && r.state() == ClusterQueue.State.WAITING && cq.claim(id)) {
                    LOGGER.fine(() -> "Claimed queue item " + id);
                }
                ClusterQueue.Record now = cq.get(id);
                if (now != null) {
                    know(now);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cannot claim queue item " + id + "; not starting it for now", e);
            } finally {
                claiming.remove(id);
                Queue.getInstance().scheduleMaintenance();
            }
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Starts extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            ClusterQueue cq = queue();
            long id = run.getQueueId();
            if (cq == null || id <= 0) {
                return;
            }
            try {
                ClusterQueue.Record r = cq.get(id);
                if (r != null && origin().equals(r.claimedBy())) {
                    cq.started(id, run.getNumber());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to record that " + run + " started", e);
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
                    LOGGER.log(Level.WARNING, "Cannot get a cluster-wide queue id; using a local one", e);
                }
            }
            return LOCAL.generateIdFor(project, actions);
        }

        /** Ids taken from the cluster sequence ahead of need, off the queue lock (unused ones are skipped numbers). */
        private static final BlockingQueue<Long> spare = new LinkedBlockingQueue<>();
        private static final int SPARE = 16;
        private static final java.util.concurrent.atomic.AtomicBoolean filling = new java.util.concurrent.atomic.AtomicBoolean();

        /** An id from the pool (refilled in the background), or from the store if the pool ran dry. */
        private static long nextId(ClusterQueue cq) throws IOException {
            Long id = spare.poll();
            if (spare.size() < SPARE / 2 && filling.compareAndSet(false, true)) {
                claims.submit(() -> {
                    try {
                        while (spare.size() < SPARE) {
                            spare.add(cq.nextId());
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Cannot take queue ids ahead", e);
                    } finally {
                        filling.set(false);
                    }
                });
            }
            return id != null ? id : cq.nextId();
        }

        @Override
        public void persist(@NonNull Queue.State queueState) {
            LOCAL.persist(queueState);
        }

        @Override
        public void load(@NonNull Queue.State queueState) {
            LOCAL.load(queueState);
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

        @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
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
                        Calendar due = new GregorianCalendar();
                        due.setTimeInMillis(r.dueAt());
                        Queue.getInstance().updateShared(r.id(), due, fromXml(r.actions()));
                    } else if (!me.equals(r.origin())) {
                        create(r); // our own items are never recreated: absent here means they already left
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
            if (r == null || r.state() != ClusterQueue.State.WAITING || Queue.getInstance().getLocalItem(r.id()) != null) {
                return;
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(r.task(), Job.class);
            if (!(job instanceof Queue.Task task)) {
                LOGGER.fine(() -> "Not mirroring " + r + ": no such job here (yet)");
                return;
            }
            int quietPeriod = (int) Math.max(0, (r.dueAt() - System.currentTimeMillis() + 999) / 1000);
            know(r);
            Ids.MIRRORED.set(r.id());
            try {
                ScheduleResult result = PersistenceStores.applyingRemoteChange(
                        () -> Queue.getInstance().schedule2(task, quietPeriod, fromXml(r.actions())));
                if (result.getItem() != null && result.getItem().getId() != r.id()) {
                    LOGGER.warning(() -> "Queue item " + r.id() + " folded into local item " + result.getItem().getId() + " instead of being mirrored");
                }
            } finally {
                Ids.MIRRORED.remove();
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
