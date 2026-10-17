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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.InvisibleAction;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.ParametersAction;
import hudson.model.PasswordParameterValue;
import hudson.model.Project;
import hudson.model.Queue;
import hudson.model.StringParameterValue;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.queue.FoldableAction;
import hudson.model.queue.QueueTaskFuture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The queue is shared with another controller (simulated by a store view with another origin).
 */
@WithJenkins
class SharedQueueTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public ClusterQueue queue() {
            return noQueue ? null : slowFor(clusterQueue());
        }

        @Override
        public Set<String> liveOrigins() {
            return live;
        }

        @Override
        public Entry read(String key) {
            java.util.function.Predicate<String> fail = failReads;
            if (fail != null && fail.test(key)) {
                throw new java.io.UncheckedIOException(new java.io.IOException("the store is unreachable, for now"));
            }
            return super.read(key);
        }

        @Override
        public void delete(String key, long expectedVersion) throws ConflictException {
            java.util.concurrent.CountDownLatch l = slowRelease;
            if (l != null && key.startsWith("queue/reserved/")) {
                try {
                    l.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            super.delete(key, expectedVersion);
        }
    };

    /** While set, reads of the keys it matches fail, as when the store is unreachable. */
    static volatile java.util.function.Predicate<String> failReads;

    /** The controllers known to be alive, or null if that is not known. */
    static volatile Set<String> live;

    /** While set, releasing a reservation waits for it: a store answering slowly. */
    static volatile java.util.concurrent.CountDownLatch slowRelease;

    /** While set, the store has no queue (each controller has its own). */
    static volatile boolean noQueue;

    /** Ids taken one at a time, as when the pool of ids taken ahead ran dry. */
    static final java.util.concurrent.atomic.AtomicInteger singleIds = new java.util.concurrent.atomic.AtomicInteger();

    /** While set, publishing an item of a job named "slow…" waits for it: a store answering slowly. */
    static volatile java.util.concurrent.CountDownLatch slowPublish;

    /** Publishing an item of job "flaky" fails after the store took it: the answer is lost. */
    static final String FLAKY = "flaky";

    /** If set, called with the id of an item about to be updated (folded into). */
    static volatile java.util.function.LongConsumer beforeUpdate;

    /** Run once as this controller next records that an item started, before it does. */
    static volatile java.util.function.LongConsumer beforeStarted;

    /** While set, queue changes are not delivered to this controller's mirror but kept here: they come late. */
    static volatile List<Runnable> heldChanges;

    private static void deliverHeldChanges() {
        List<Runnable> held = heldChanges;
        heldChanges = null;
        if (held != null) {
            synchronized (held) {
                held.forEach(Runnable::run);
            }
        }
    }

    private static ClusterQueue slowFor(ClusterQueue cq) {
        return new ClusterQueue() {
            @Override
            public long nextId() throws java.io.IOException {
                singleIds.incrementAndGet();
                return cq.nextId();
            }

            @Override
            public long[] nextIds(int n) throws java.io.IOException {
                return cq.nextIds(n);
            }

            @Override
            public Record publish(Record item) throws java.io.IOException {
                java.util.concurrent.CountDownLatch l = slowPublish;
                if (l != null && item.task().startsWith("slow")) {
                    try {
                        l.await();
                    } catch (InterruptedException e) {
                        throw new java.io.InterruptedIOException();
                    }
                }
                Record stored = cq.publish(item);
                if (item.task().equals(FLAKY)) {
                    throw new java.io.IOException("connection reset after publishing");
                }
                return stored;
            }

            @Override
            public boolean update(long id, String expectedActions, String actions, long dueAt, String pinTo) throws java.io.IOException {
                java.util.function.LongConsumer hook = beforeUpdate;
                if (hook != null) {
                    hook.accept(id);
                }
                return cq.update(id, expectedActions, actions, dueAt, pinTo);
            }

            @Override
            public boolean claim(long id, String expectedPin) throws java.io.IOException {
                return cq.claim(id, expectedPin);
            }

            @Override
            public boolean started(long id, int buildNumber) throws java.io.IOException {
                java.util.function.LongConsumer hook = beforeStarted;
                if (hook != null) {
                    beforeStarted = null;
                    hook.accept(id);
                }
                return cq.started(id, buildNumber);
            }

            @Override
            public boolean cancel(long id) throws java.io.IOException {
                return cq.cancel(id);
            }

            @Override
            public Record get(long id) throws java.io.IOException {
                return cq.get(id);
            }

            @Override
            public List<Record> pending() throws java.io.IOException {
                return cq.pending();
            }

            @Override
            public AutoCloseable subscribe(java.util.function.Consumer<Record> listener) {
                return cq.subscribe(rec -> {
                    List<Runnable> held = heldChanges;
                    if (held != null) {
                        synchronized (held) {
                            held.add(() -> listener.accept(rec));
                        }
                    } else {
                        listener.accept(rec);
                    }
                });
            }
        };
    }

    private JenkinsRule r;
    private ClusterQueue other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        beforeStarted = null;
        failReads = null;
        other = STORE.as("other-controller").clusterQueue();
    }

    private static void awaitMirrored() throws InterruptedException {
        for (int i = 0; i < 3000 && !SharedQueue.Mirror.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(SharedQueue.Mirror.isIdle());
    }

    /** The shared record of an item, once the outbox (which shares off the queue lock) has shared it as expected. */
    private static ClusterQueue.Record awaitShared(long id, java.util.function.Predicate<ClusterQueue.Record> as) throws Exception {
        ClusterQueue.Record rec = STORE.clusterQueue().get(id);
        for (int i = 0; i < 500 && (rec == null || !as.test(rec)); i++) {
            Thread.sleep(10);
            rec = STORE.clusterQueue().get(id);
        }
        assertNotNull(rec, "shared");
        return rec;
    }

    /** Publishes an item as the other controller would. */
    private ClusterQueue.Record publishElsewhere(String job, long dueInMillis, String user) throws Exception {
        List<Action> actions = List.of(new CauseAction(new Cause.UserIdCause(user)));
        long id = other.nextId();
        ClusterQueue.Record rec = other.publish(new ClusterQueue.Record(id, job, job, SharedQueue.toXml(actions),
                System.currentTimeMillis() + dueInMillis, ClusterQueue.State.WAITING, "other-controller", null, null, 0));
        awaitMirrored();
        return rec;
    }

    private static String causes(ClusterQueue.Record rec) {
        StringBuilder b = new StringBuilder();
        for (Action a : SharedQueue.fromXml(rec.actions())) {
            if (a instanceof CauseAction ca) {
                for (Cause c : ca.getCauses()) {
                    b.append(c instanceof Cause.UserIdCause u ? u.getUserId() : c.getShortDescription()).append('\n');
                }
            }
        }
        return b.toString();
    }

    @Test
    void localItemIsPublishedClaimedAndStarted() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("local");
        r.buildAndAssertSuccess(p);
        long id = p.getLastBuild().getQueueId();
        ClusterQueue.Record rec = awaitShared(id, x -> x.state() == ClusterQueue.State.STARTED);
        assertNotNull(rec);
        assertEquals("local", rec.task());
        assertEquals(ClusterQueue.State.STARTED, rec.state());
        assertEquals(STORE.getOrigin(), rec.claimedBy());
        assertEquals(1, rec.buildNumber());
    }

    @Test
    void aBuildStartedForAnItemTakenElsewhereSinceItsClaimIsStopped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("reclaimed");
        p.getBuildersList().add(new org.jvnet.hudson.test.SleepBuilder(60_000)); // working when stopped
        // this controller paused past its claim lapsing: the store put the item back, and another controller took it
        beforeStarted = id -> {
            STORE.requeue(id);
            try {
                assertTrue(other.claim(id));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        hudson.model.FreeStyleBuild b = r.assertBuildStatus(hudson.model.Result.ABORTED, p.scheduleBuild2(0));
        assertNull(beforeStarted);
        assertEquals("other-controller", STORE.clusterQueue().get(b.getQueueId()).claimedBy());
        assertEquals(ClusterQueue.State.CLAIMED, STORE.clusterQueue().get(b.getQueueId()).state(), "not recorded as started here");
        r.assertLogContains("Another controller took the queue item of this build", b);
    }

    @Test
    void anItemClaimedHereThatMakesNoBuildIsCancelledNotLeftClaimed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("no-build");
        DisableOnStart.job = p;
        p.scheduleBuild2(0);
        long id = DisableOnStart.left.get(60, java.util.concurrent.TimeUnit.SECONDS);
        ClusterQueue.Record rec = awaitShared(id, x -> x.state() == ClusterQueue.State.CANCELLED);
        assertEquals(ClusterQueue.State.CANCELLED, rec.state(), "not left claimed by this controller, built nowhere");
        assertNull(p.getLastBuild());
    }

    /** Disables a job as its item leaves the queue to start, before its build is made: it then makes none. */
    @TestExtension("anItemClaimedHereThatMakesNoBuildIsCancelledNotLeftClaimed")
    public static final class DisableOnStart extends hudson.model.queue.QueueListener {
        static volatile FreeStyleProject job;
        static final java.util.concurrent.CompletableFuture<Long> left = new java.util.concurrent.CompletableFuture<>();

        @Override
        public void onLeft(Queue.LeftItem li) {
            if (li.task == job && !li.isCancelled()) {
                try {
                    java.lang.reflect.Field disabled = hudson.model.AbstractProject.class.getDeclaredField("disabled");
                    disabled.setAccessible(true);
                    disabled.setBoolean(job, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                left.complete(li.getId());
            }
        }
    }

    @Test
    void itemFromElsewhereIsMirroredWithItsIdAndCancelledEverywhere() throws Exception {
        r.createFreeStyleProject("mirrored");
        ClusterQueue.Record rec = publishElsewhere("mirrored", 60_000, "alice");

        Queue.Item local = Queue.getInstance().getLocalItem(rec.id());
        assertThat(local, instanceOf(Queue.WaitingItem.class));
        assertEquals(rec.id(), local.getId());

        other.cancel(rec.id());
        awaitMirrored();
        assertTrue(Queue.getInstance().isEmpty());
    }

    @Test
    void itemFromElsewhereIsLeftToItsControllerForAWhile() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 6_000;
        try {
            FreeStyleProject p = r.createFreeStyleProject("origin-first");
            ClusterQueue.Record rec = publishElsewhere("origin-first", 0, "ivan");
            Thread.sleep(3_000);
            assertNull(p.getLastBuild(), "left to the controller it was scheduled on");
            for (int i = 0; i < 300 && p.getLastBuild() == null; i++) {
                Thread.sleep(100);
            }
            assertNotNull(p.getLastBuild(), "started here once that controller did not take it");
            assertEquals(STORE.getOrigin(), STORE.clusterQueue().get(rec.id()).claimedBy());
        } finally {
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void theControllersLeftAgreeWhichOfThemProvisions() {
        for (long id = 1; id <= 50; id++) {
            String one = SharedQueue.provisioner(id, Set.of("a", "b", "c"));
            assertEquals(one, SharedQueue.provisioner(id, new LinkedHashSet<>(List.of("c", "b", "a"))), "whichever asks");
            assertTrue(Set.of("a", "b", "c").contains(one));
        }
    }

    @Test
    void onlyItsOriginProvisionsForAnItem() throws Exception {
        Label cloudy = Label.get("cloudy"); // no node has it: items stay buildable, waiting for capacity
        FreeStyleProject elsewhere = r.createFreeStyleProject("cloudy-elsewhere");
        elsewhere.setAssignedLabel(cloudy);
        publishElsewhere("cloudy-elsewhere", 0, "carol");
        for (int i = 0; i < 100 && Queue.getInstance().getBuildableItems().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertEquals(1, Queue.getInstance().getBuildableItems().size());
        assertEquals(0, cloudy.loadStatistics.computeSnapshot().getQueueLength(), "the other controller provisions for its item");

        FreeStyleProject here = r.createFreeStyleProject("cloudy-here");
        here.setAssignedLabel(cloudy);
        here.scheduleBuild2(0);
        for (int i = 0; i < 100 && Queue.getInstance().getBuildableItems().size() < 2; i++) {
            Thread.sleep(100);
        }
        assertEquals(1, cloudy.loadStatistics.computeSnapshot().getQueueLength(), "this controller provisions for its own item");
        Queue.getInstance().clear();
    }

    @Test
    void itemReservedByItsControllerWaitsForTheAgentItStarts() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 500;
        try {
            FreeStyleProject p = r.createFreeStyleProject("reserved");
            ClusterQueue.Record rec = publishElsewhere("reserved", 0, "erin");
            // the other controller is starting a cloud agent for it
            STORE.write(SharedQueue.Reservations.key(rec.id()), ("other-controller " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ANY);
            Thread.sleep(3_000);
            assertNull(p.getLastBuild(), "past the head start, but reserved");
            SharedQueue.Reservations.release(rec.id()); // its agent never came, say
            for (int i = 0; i < 100 && p.getLastBuild() == null; i++) {
                Thread.sleep(100);
            }
            assertNotNull(p.getLastBuild(), "started here once no longer reserved");
        } finally {
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void theControllerProvisioningForAnItemWhoseControllerIsGoneReservesIt() throws Exception {
        Label cloudy = Label.get("cloudy-orphan"); // no node has it: the item stays buildable, unclaimed
        FreeStyleProject p = r.createFreeStyleProject("cloudy-orphan");
        p.setAssignedLabel(cloudy);
        live = Set.of(STORE.getOrigin()); // the other controller is gone: this one provisions for its item
        try {
            ClusterQueue.Record rec = publishElsewhere("cloudy-orphan", 0, "gus");
            String key = SharedQueue.Reservations.key(rec.id());
            // which it had reserved before it went
            STORE.write(key, ("other-controller " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            for (int i = 0; i < 100 && Queue.getInstance().getBuildableItems().isEmpty(); i++) {
                Thread.sleep(100);
            }
            assertEquals(1, Queue.getInstance().getBuildableItems().size());
            new SharedQueue.Reservations().onStarted(new hudson.slaves.Cloud("test") {}, cloudy,
                    List.of(new hudson.slaves.NodeProvisioner.PlannedNode("agent", new java.util.concurrent.CompletableFuture<>(), 1)));
            assertThat(new String(STORE.read(key).content(), StandardCharsets.UTF_8), startsWith(STORE.getOrigin() + " "));
        } finally {
            live = null;
            Queue.getInstance().clear();
        }
    }

    @Test
    void anItemReservedByAnotherControllerIsNotDemandHere() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 0; // past its controller's head start at once
        Label cloudy = Label.get("cloudy-reserved"); // no node has it: the items stay buildable, unclaimed
        try {
            // membership unknown: any controller would provision for it, but its controller is starting an agent for it
            FreeStyleProject p = r.createFreeStyleProject("cloudy-reserved");
            p.setAssignedLabel(cloudy);
            ClusterQueue.Record rec = publishElsewhere("cloudy-reserved", 0, "hal");
            STORE.write(SharedQueue.Reservations.key(rec.id()), ("other-controller " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ANY);
            for (int i = 0; i < 100 && Queue.getInstance().getBuildableItems().isEmpty(); i++) {
                Thread.sleep(100);
            }
            assertEquals(1, Queue.getInstance().getBuildableItems().size());
            assertEquals(0, cloudy.loadStatistics.computeSnapshot().getQueueLength(), "reserved by its controller");

            // its controller is gone, and the one elected to provision reserved it before this one joined (and won)
            Queue.getInstance().clear();
            awaitMirrored();
            String me = STORE.getOrigin();
            ClusterQueue.Record orphan = publishElsewhere("cloudy-reserved", 0, "ida");
            String survivor = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> "controller-" + i)
                    .filter(o -> me.equals(SharedQueue.provisioner(orphan.id(), Set.of(me, o)))).findFirst().orElseThrow();
            live = Set.of(me, survivor);
            STORE.write(SharedQueue.Reservations.key(orphan.id()), (survivor + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ANY);
            for (int i = 0; i < 100 && Queue.getInstance().getBuildableItems().isEmpty(); i++) {
                Thread.sleep(100);
            }
            assertEquals(1, Queue.getInstance().getBuildableItems().size());
            assertEquals(0, cloudy.loadStatistics.computeSnapshot().getQueueLength(), "reserved by a live controller");
        } finally {
            live = null;
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
            Queue.getInstance().clear();
        }
    }

    @Test
    void cancellingAReservedItemDoesNotWaitForTheStoreToReleaseIt() throws Exception {
        r.createFreeStyleProject("reserved-cancelled");
        ClusterQueue.Record rec = publishElsewhere("reserved-cancelled", 60_000, "hal");
        String key = SharedQueue.Reservations.key(rec.id());
        STORE.write(key, (STORE.getOrigin() + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        Queue.Item item = Queue.getInstance().getItem(rec.id());
        slowRelease = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(() -> Queue.getInstance().cancel(item))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS), "not waiting for the store under the queue lock");
        } finally {
            slowRelease.countDown();
            slowRelease = null;
        }
        for (int i = 0; i < 500 && STORE.read(key) != null; i++) {
            Thread.sleep(10);
        }
        assertNull(STORE.read(key), "released afterwards");
    }

    @Test
    void itemClaimedElsewhereDoesNotStartHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("claimed");
        ClusterQueue.Record rec = publishElsewhere("claimed", 2_000, "bob");
        assertTrue(other.claim(rec.id()));
        awaitMirrored();
        Thread.sleep(4_000); // past its quiet period
        assertNull(p.getLastBuild(), "never started here");
        assertTrue(Queue.getInstance().isEmpty());
        assertEquals("other-controller", STORE.clusterQueue().get(rec.id()).claimedBy());
    }

    @Test
    void schedulingHereWhileTheSameItemArrivesFromElsewhereBuildsOnce() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 500;
        try {
            FreeStyleProject p = r.createFreeStyleProject("race");
            for (int round = 1; round <= 5; round++) {
                // the other controller publishes the job at the same moment as it is scheduled here
                long id = other.nextId();
                AtomicReference<Throwable> failed = new AtomicReference<>();
                Thread elsewhere = new Thread(() -> {
                    try {
                        other.publish(new ClusterQueue.Record(id, "race", "race", SharedQueue.toXml(List.of(new CauseAction(new Cause.UserIdCause("there")))),
                                System.currentTimeMillis() + 1_000, ClusterQueue.State.WAITING, "other-controller", null, null, 0));
                    } catch (Throwable t) {
                        failed.set(t);
                    }
                });
                elsewhere.start();
                p.scheduleBuild2(1, new CauseAction(new Cause.UserIdCause("here")));
                elsewhere.join();
                if (failed.get() != null) {
                    throw new AssertionError("publishing elsewhere failed, so the race was not exercised", failed.get());
                }
                for (int i = 0; i < 150 && (p.getBuilds().size() < round || p.isBuilding() || !Queue.getInstance().isEmpty()); i++) {
                    Thread.sleep(100);
                }
                Thread.sleep(2_000); // a second build would have started by now
                assertEquals(round, p.getBuilds().size(), "one build per round");
            }
        } finally {
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void schedulingHereFoldsIntoItemFromElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("folded");
        ClusterQueue.Record rec = publishElsewhere("folded", 60_000, "carol");

        p.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("dave")));

        assertEquals(1, Queue.getInstance().getItems().length, "one item");
        // shared off the queue lock, by the outbox
        for (int i = 0; i < 100 && !causes(STORE.clusterQueue().get(rec.id())).contains("dave"); i++) {
            Thread.sleep(100);
        }
        ClusterQueue.Record now = STORE.clusterQueue().get(rec.id());
        assertThat(causes(now), containsString("carol"));
        assertThat(causes(now), containsString("dave"));
    }

    @Test
    void foldingElsewhereIntoAnItemFromHereUpdatesIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("ours");
        p.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("grace")));
        Queue.Item local = Queue.getInstance().getItems()[0];
        ClusterQueue.Record rec = awaitShared(local.getId(), x -> true);
        // another controller folds a request into it
        List<Action> merged = List.of(new CauseAction(new Cause.UserIdCause("grace"), new Cause.UserIdCause("heidi")));
        assertTrue(other.update(rec.id(), rec.actions(), SharedQueue.toXml(merged), rec.dueAt()));
        awaitMirrored();

        StringBuilder users = new StringBuilder();
        for (Cause c : Queue.getInstance().getItem(rec.id()).getCauses()) {
            if (c instanceof Cause.UserIdCause u) {
                users.append(u.getUserId()).append(' ');
            }
        }
        assertThat(users.toString(), containsString("heidi"));
        assertEquals(1, Queue.getInstance().getItems().length);
    }

    @Test
    void foldingARequestNeedingThisControllerIntoAnItemFromElsewherePinsIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("pinned");
        ClusterQueue.Record rec = publishElsewhere("pinned", 60_000, "kim");
        assertNull(rec.pinnedTo());

        // a build step here waits for the build: it is folded into the item mirrored here
        p.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("lee")), new BuildTriggerAction("step"));
        assertEquals(1, Queue.getInstance().getItems().length, "one item");

        ClusterQueue.Record now = awaitShared(rec.id(), x -> causes(x).contains("lee"));
        assertEquals(STORE.getOrigin(), now.pinnedTo(), "the build must run where the step waits for it");
        assertThat(causes(now), containsString("kim"));
        assertThat(now.actions(), containsString(BuildTriggerAction.class.getName()));
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void aRequestFoldedHereBeforeItsItemIsSharedIsKept() throws Exception {
        FreeStyleProject slow = r.createFreeStyleProject("slow-folded-here");
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        try {
            slow.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("mia")));
            slow.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("ned"))); // folded locally
            long id = Queue.getInstance().getItems()[0].getId();
            slowPublish.countDown();
            ClusterQueue.Record rec = awaitShared(id, x -> causes(x).contains("ned"));
            assertThat(causes(rec), containsString("mia"));
            assertThat(causes(rec), containsString("ned"));
        } finally {
            slowPublish.countDown();
            slowPublish = null;
        }
        Queue.getInstance().clear();
    }

    @Test
    void itemStartedElsewhereIsVisibleHere() throws Exception {
        r.createFreeStyleProject("remote");
        ClusterQueue.Record rec = publishElsewhere("remote", 60_000, "erin");
        assertTrue(other.claim(rec.id()));
        other.started(rec.id(), 7);
        awaitMirrored();

        String json = r.createWebClient().goTo("queue/item/" + rec.id() + "/api/json", "application/json").getWebResponse().getContentAsString();
        assertThat(json, containsString("\"executable\":{"));
        assertThat(json, containsString("\"number\":7"));
        assertThat(json, containsString("\"cancelled\":false"));
    }

    @Test
    void aStoreSlowToPublishDoesNotHoldUpTheQueue() throws Exception {
        FreeStyleProject slow = r.createFreeStyleProject("slow");
        FreeStyleProject quick = r.createFreeStyleProject("quick");
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        try {
            long before = System.nanoTime();
            QueueTaskFuture<FreeStyleBuild> s = slow.scheduleBuild2(0);
            assertTrue(System.nanoTime() - before < java.util.concurrent.TimeUnit.SECONDS.toNanos(2), "scheduling does not wait for the store");
            r.assertBuildStatusSuccess(quick.scheduleBuild2(0)); // the queue goes on meanwhile
            assertNull(slow.getLastBuild(), "not started before it is shared");
            slowPublish.countDown();
            r.assertBuildStatusSuccess(s);
        } finally {
            slowPublish.countDown();
            slowPublish = null;
        }
    }

    @Test
    void foldsMadeAtOnceAreBothKept() throws Exception {
        ClusterQueue.Record rec = publishElsewhere("folds", 60_000, "eve");
        ClusterQueue cq = STORE.clusterQueue();
        String read = cq.get(rec.id()).actions();
        // two controllers merged their causes into what they both read: the second must merge again
        assertTrue(cq.update(rec.id(), read, read.replace("eve", "first"), rec.dueAt()));
        assertFalse(cq.update(rec.id(), read, read.replace("eve", "second"), rec.dueAt()));
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void cancellingHereCancelsEverywhere() throws Exception {
        r.createFreeStyleProject("cancelled");
        ClusterQueue.Record rec = publishElsewhere("cancelled", 60_000, "frank");

        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl("queue/cancelItem?id=" + rec.id()), HttpMethod.POST));

        // shared after the queue lock is released
        for (int i = 0; i < 500 && STORE.clusterQueue().get(rec.id()).state() != ClusterQueue.State.CANCELLED; i++) {
            Thread.sleep(10);
        }
        assertEquals(ClusterQueue.State.CANCELLED, STORE.clusterQueue().get(rec.id()).state());
        assertTrue(Queue.getInstance().isEmpty());
    }

    @Test
    void cancellingAnItemClaimedHereBeforeItStartsCancelsItEverywhere() throws Exception {
        r.createFreeStyleProject("claimed-here");
        ClusterQueue.Record rec = publishElsewhere("claimed-here", 60_000, "olga");
        assertTrue(STORE.clusterQueue().claim(rec.id()), "claimed by this controller, which has not started it yet");
        awaitMirrored();

        assertTrue(Queue.getInstance().cancel(Queue.getInstance().getItem(rec.id())));
        ClusterQueue.Record now = awaitShared(rec.id(), x -> x.state() == ClusterQueue.State.CANCELLED);
        assertEquals(ClusterQueue.State.CANCELLED, now.state(), "else it would stay claimed, and be put back once this controller is gone");
    }

    @Test
    void cancellingAnItemWhileItIsSharedCancelsItEverywhere() throws Exception {
        FreeStyleProject slow = r.createFreeStyleProject("slow-cancelled");
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        try {
            slow.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("pat")));
            Queue.Item item = Queue.getInstance().getItems()[0];
            assertTrue(Queue.getInstance().cancel(item), "cancelled before the store has it");
            Thread.sleep(1_000); // as far as the cancellation could be shared now, it is
            slowPublish.countDown();
            ClusterQueue.Record rec = awaitShared(item.getId(), x -> x.state() == ClusterQueue.State.CANCELLED);
            assertEquals(ClusterQueue.State.CANCELLED, rec.state(), "else the other controllers would run it");
        } finally {
            slowPublish.countDown();
            slowPublish = null;
        }
    }

    @Test
    void aRequestFoldedIntoAnItemFromElsewhereIsDroppedWhereverItIs() throws Exception {
        foldIntoAnItemFromElsewhereWhilePublishing("slow-folded");
    }

    @Test
    void aRequestFoldedIntoAnItemFromElsewhereLeavesThatItemOnASecuredController() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, hudson.model.Item.READ).everywhere().to("bob"));
        foldIntoAnItemFromElsewhereWhilePublishing("slow-folded-secured");
    }

    /**
     * Schedules a job with no quiet period while another controller publishes the same, so that the item here is
     * buildable by the time it folds into the other: it must not start, and only the other's item is left.
     */
    private void foldIntoAnItemFromElsewhereWhilePublishing(String name) throws Exception {
        FreeStyleProject slow = r.createFreeStyleProject(name);
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        ClusterQueue.Record rec;
        try {
            slow.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("here")));
            long here = Queue.getInstance().getItems()[0].getId();
            rec = publishElsewhere(name, 60_000, "there");
            for (int i = 0; i < 100 && !(Queue.getInstance().getItem(here) instanceof Queue.BuildableItem); i++) {
                Thread.sleep(100);
            }
            assertThat(Queue.getInstance().getItem(here), instanceOf(Queue.BuildableItem.class));
            slowPublish.countDown();
            for (int i = 0; i < 100 && Queue.getInstance().getLocalItem(rec.id()) == null; i++) {
                Thread.sleep(100);
            }
            Thread.sleep(2_000); // it would have started by now
        } finally {
            slowPublish.countDown();
            slowPublish = null;
        }
        assertNull(slow.getLastBuild(), "folded into the item from elsewhere, which is not due yet");
        Queue.Item[] items = Queue.getInstance().getItems();
        assertEquals(1, items.length, "only the item from elsewhere");
        assertEquals(rec.id(), items[0].getId());
        assertThat(causes(STORE.clusterQueue().get(rec.id())), containsString("here"));
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void anItemRestoredFromBeforeARestartDoesNotStartWithoutItsRecord() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("restored");
        // started elsewhere while this controller was down
        long id = other.nextId();
        other.publish(new ClusterQueue.Record(id, "restored", "restored", SharedQueue.toXml(List.of(new CauseAction(new Cause.UserIdCause("quinn")))),
                System.currentTimeMillis(), ClusterQueue.State.STARTED, "other-controller", null, "other-controller", 1));
        awaitMirrored();
        // the item as Queue.load restores it: in the queue here, with nothing known of its record
        SharedQueue.Ids.MIRRORED.set(id);
        try {
            PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().schedule2(p, 0));
        } finally {
            SharedQueue.Ids.MIRRORED.remove();
        }
        for (int i = 0; i < 50 && !Queue.getInstance().isEmpty(); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(1_000);
        assertNull(p.getLastBuild(), "it started elsewhere");
        assertTrue(Queue.getInstance().isEmpty(), "dropped");
    }

    @Test
    void anItemWhosePublicationFailedAfterTheStoreHadItIsClaimedBeforeItStarts() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject(FLAKY);
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        ClusterQueue.Record rec = awaitShared(p.getLastBuild().getQueueId(), x -> x.state() == ClusterQueue.State.STARTED);
        assertEquals(ClusterQueue.State.STARTED, rec.state(), "else the others would start it too");
        assertEquals(STORE.getOrigin(), rec.claimedBy());
    }

    @Test
    void anItemPutBackAfterItsClaimerWentAwayIsMirroredAgain() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 60_000;
        try {
            FreeStyleProject p = r.createFreeStyleProject("requeued");
            ClusterQueue.Record rec = publishElsewhere("requeued", 0, "rita");
            assertTrue(other.claim(rec.id()));
            awaitMirrored();
            for (int i = 0; i < 50 && Queue.getInstance().getItem(rec.id()) instanceof Queue.WaitingItem; i++) {
                Thread.sleep(100);
            }
            assertTrue(Queue.getInstance().isEmpty(), "dropped here");

            SharedQueue.ORIGIN_GRACE_MILLIS = 0;
            STORE.requeue(rec.id()); // the other controller went away before starting it
            awaitMirrored();
            for (int i = 0; i < 100 && p.getLastBuild() == null; i++) {
                Thread.sleep(100);
            }
            assertNotNull(p.getLastBuild(), "built here");
            assertEquals(rec.id(), p.getLastBuild().getQueueId());
        } finally {
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void anItemOfAJobCreatedAfterItIsMirroredOnceTheJobIsHere() throws Exception {
        ClusterQueue.Record rec = publishElsewhere("late", 60_000, "sam");
        assertNull(Queue.getInstance().getLocalItem(rec.id()), "no such job yet");
        r.createFreeStyleProject("late");
        for (int i = 0; i < 100 && Queue.getInstance().getLocalItem(rec.id()) == null; i++) {
            Thread.sleep(100);
        }
        assertNotNull(Queue.getInstance().getLocalItem(rec.id()), "mirrored once the job is here");
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void anItemWithALocalIdBuildsHereUnshared() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("local-id");
        // as when the cluster sequence was unreachable: an id from the local range, which another controller may have too
        SharedQueue.Ids.MIRRORED.set(SharedQueue.Ids.LOCAL_RANGE + 7);
        QueueTaskFuture<FreeStyleBuild> f;
        try {
            f = p.scheduleBuild2(0);
        } finally {
            SharedQueue.Ids.MIRRORED.remove();
        }
        r.assertBuildStatusSuccess(f);
        assertEquals(SharedQueue.Ids.LOCAL_RANGE + 7, p.getLastBuild().getQueueId());
        assertNull(STORE.clusterQueue().get(SharedQueue.Ids.LOCAL_RANGE + 7), "never shared");
    }

    /** Waits for the queue item of a job to be blocked for a reason, and checks that it has not started. */
    private static void awaitBlocked(FreeStyleProject p, String why) throws Exception {
        Queue.Item item = Queue.getInstance().getItem(p);
        for (int i = 0; i < 300 && (item == null || !why.equals(item.getWhy())); i++) {
            Thread.sleep(50);
            item = Queue.getInstance().getItem(p);
        }
        assertNotNull(item, "still queued");
        assertEquals(why, item.getWhy());
        assertNull(p.getLastBuild(), "not started");
    }

    @Test
    void anItemWithALocalIdWaitsWhileTheStoreIsUnreachable() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("local-id-outage");
        // its build number would be taken from the store once it has left the queue, and fail: the request lost
        failReads = k -> k.equals("jobs/local-id-outage/nextBuildNumber");
        SharedQueue.Ids.MIRRORED.set(SharedQueue.Ids.LOCAL_RANGE + 11);
        QueueTaskFuture<FreeStyleBuild> f;
        try {
            f = p.scheduleBuild2(0);
        } finally {
            SharedQueue.Ids.MIRRORED.remove();
        }
        awaitBlocked(p, "Waiting for the persistence store");
        failReads = null;
        r.assertBuildStatusSuccess(f);
        assertEquals(1, p.getBuilds().size());
    }

    @Test
    void anItemWithALocalIdWaitsWhileItsJobBuildsElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("local-id-serial");
        String lease = "jobs/local-id-serial/running/exclusive";
        PersistenceStore elsewhere = STORE.as("other-controller");
        long epoch = elsewhere.leases().acquire(lease); // a build of the job runs there
        SharedQueue.Ids.MIRRORED.set(SharedQueue.Ids.LOCAL_RANGE + 12);
        QueueTaskFuture<FreeStyleBuild> f;
        try {
            f = p.scheduleBuild2(0);
        } finally {
            SharedQueue.Ids.MIRRORED.remove();
        }
        awaitBlocked(p, "A build is already in progress on another controller");
        elsewhere.leases().release(lease, epoch);
        r.assertBuildStatusSuccess(f);
    }

    @Test
    void anItemOneMayNotSeeIsNotFound() throws Exception {
        r.createFreeStyleProject("hidden");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("bob"));
        ClusterQueue.Record rec = publishElsewhere("hidden", 60_000, "carol");

        JenkinsRule.WebClient wc = r.createWebClient().login("bob");
        wc.setThrowExceptionOnFailingStatusCode(false);
        int status = wc.getPage(new WebRequest(wc.createCrumbedUrl("queue/cancelItem?id=" + rec.id()), HttpMethod.POST)).getWebResponse().getStatusCode();
        assertEquals(404, status, "as for an item of this controller's queue");
        assertEquals(ClusterQueue.State.WAITING, STORE.clusterQueue().get(rec.id()).state());
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void aRequestWithANullParameterIsSharedAndStarts() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("null-parameter");
        // while queued, a ParametersAction keeps the nulls it was given
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0, new ParametersAction(java.util.Arrays.asList(new StringParameterValue("a", "x"), null)));
        // it starts (how the build then copes with the null is up to it, as without a shared queue)
        r.waitForCompletion(f.getStartCondition().get(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(STORE.getOrigin(), awaitShared(p.getLastBuild().getQueueId(), x -> x.state() == ClusterQueue.State.STARTED).claimedBy());
    }

    @Test
    void aRequestWhoseItemWasTakenAsItFoldedIsSharedOnItsOwn() throws Exception {
        String name = "slow-taken-meanwhile";
        FreeStyleProject slow = r.createFreeStyleProject(name);
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        ClusterQueue.Record rec;
        long here;
        try {
            slow.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("here")));
            here = Queue.getInstance().getItems()[0].getId();
            ClusterQueue.Record there = publishElsewhere(name, 60_000, "there");
            rec = there;
            // the other controller claims its item just as this one's request is folded into it
            beforeUpdate = id -> {
                if (id == there.id()) {
                    beforeUpdate = null;
                    try {
                        assertTrue(other.claim(there.id()));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            };
            slowPublish.countDown();
            for (int i = 0; i < 100 && slow.getLastBuild() == null; i++) {
                Thread.sleep(100);
            }
        } finally {
            beforeUpdate = null;
            slowPublish.countDown();
            slowPublish = null;
        }
        assertNotNull(slow.getLastBuild(), "the request is not lost");
        r.waitForCompletion(slow.getLastBuild());
        assertEquals(here, slow.getLastBuild().getQueueId(), "shared as an item of its own");
        assertNotNull(slow.getLastBuild().getCause(Cause.UserIdCause.class));
        assertEquals("here", slow.getLastBuild().getCause(Cause.UserIdCause.class).getUserId());
        other.cancel(rec.id()); // the store outlives this test (claimed by the other controller, which never starts it)
    }

    @Test
    void aRequestFoldedHereWhileItsItemFoldsIntoOneFromElsewhereFollowsIt() throws Exception {
        String name = "slow-folded-follows";
        FreeStyleProject slow = r.createFreeStyleProject(name);
        slowPublish = new java.util.concurrent.CountDownLatch(1);
        ClusterQueue.Record rec;
        try {
            slow.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("uma")));
            // folded locally (as a build step's request: its action is added to the item), shared after
            slow.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("vic")), new Folds("follows"));
            rec = publishElsewhere(name, 60_000, "wes"); // while this one's publication waits
            slowPublish.countDown();
            ClusterQueue.Record now = awaitShared(rec.id(), x -> x.actions().contains("follows"));
            assertThat(now.actions(), containsString("follows"));
            assertThat(causes(now), containsString("wes"));
            assertThat(causes(now), containsString("uma"));
            assertThat(causes(now), containsString("vic"));
        } finally {
            slowPublish.countDown();
            slowPublish = null;
        }
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void anItemStartedHereAlreadyDoesNotStartAgain() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("started-here");
        // started here, but back in the queue (e.g. restored by a reload from a queue saved before it started)
        long id = other.nextId();
        STORE.clusterQueue().publish(new ClusterQueue.Record(id, "started-here", "started-here", SharedQueue.toXml(List.of(new CauseAction(new Cause.UserIdCause("xia")))),
                System.currentTimeMillis(), ClusterQueue.State.STARTED, STORE.getOrigin(), null, STORE.getOrigin(), 1));
        awaitMirrored();
        SharedQueue.Ids.MIRRORED.set(id);
        try {
            PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().schedule2(p, 0));
        } finally {
            SharedQueue.Ids.MIRRORED.remove();
        }
        for (int i = 0; i < 50 && !Queue.getInstance().isEmpty(); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(1_000);
        assertNull(p.getLastBuild(), "it started already");
        assertTrue(Queue.getInstance().isEmpty(), "dropped");
    }

    @Test
    void anItemClaimedHereStartsWithWhatWasFoldedIntoItElsewhere() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 0;
        try {
            FreeStyleProject p = r.createFreeStyleProject("claimed-with-fold");
            ClusterQueue.Record rec = publishElsewhere("claimed-with-fold", 3_000, "yan");
            heldChanges = new ArrayList<>(); // the fold's change reaches this controller late
            List<Action> merged = List.of(new CauseAction(new Cause.UserIdCause("yan"), new Cause.UserIdCause("zoe")));
            assertTrue(other.update(rec.id(), rec.actions(), SharedQueue.toXml(merged), rec.dueAt()));
            for (int i = 0; i < 100 && p.getLastBuild() == null; i++) {
                Thread.sleep(100);
            }
            assertNotNull(p.getLastBuild(), "claimed and started here");
            r.waitForCompletion(p.getLastBuild());
            StringBuilder users = new StringBuilder();
            for (Cause c : p.getLastBuild().getCauses()) {
                if (c instanceof Cause.UserIdCause u) {
                    users.append(u.getUserId()).append(' ');
                }
            }
            assertThat(users.toString(), containsString("zoe"));
        } finally {
            deliverHeldChanges();
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void anItemPinnedElsewhereAfterTheGateLookedIsNotClaimedHere() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 0;
        try {
            FreeStyleProject p = r.createFreeStyleProject("pinned-meanwhile");
            ClusterQueue.Record rec = publishElsewhere("pinned-meanwhile", 2_000, "abe");
            heldChanges = new ArrayList<>(); // this controller does not learn of the pin before it claims
            // a build step there folded a request into it: it must run there
            assertTrue(other.update(rec.id(), rec.actions(), rec.actions(), rec.dueAt(), "other-controller"));
            Thread.sleep(5_000); // past its quiet period
            assertNull(p.getLastBuild(), "not started here");
            assertNull(STORE.clusterQueue().get(rec.id()).claimedBy(), "not claimed here");
            other.cancel(rec.id()); // the store outlives this test
        } finally {
            deliverHeldChanges();
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    @Test
    void anItemWaitingElsewhereAtStartupIsMirroredOnceTheQueueIsLoaded() throws Throwable {
        r.createFreeStyleProject("waiting-at-startup");
        ClusterQueue.Record rec = publishElsewhere("waiting-at-startup", 600_000, "bea");
        // not in the queue this controller saves: only the mirror can bring it back after the restart
        assertTrue(PersistenceStores.applyingRemoteChange(() -> Queue.getInstance().cancelLocal(rec.id())));
        r.restart(); // loading the queue clears it first: the mirror starts after that
        awaitMirrored();
        assertNotNull(Queue.getInstance().getLocalItem(rec.id()), "mirrored at startup, and kept");
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void recordsKeptApartInTheStoreAreMirroredApart() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("mirrored-apart");
        long[] ids = new long[2];
        String[] what = {"a", "b"};
        for (int i = 0; i < 2; i++) {
            // e.g. two controllers' build steps scheduled it at once
            List<Action> actions = List.of(new CauseAction(new Cause.UserIdCause("step-" + what[i])), new Folds(what[i]));
            ids[i] = other.nextId();
            other.publish(new ClusterQueue.Record(ids[i], "mirrored-apart", SharedQueue.dedupKey(p, actions), SharedQueue.toXml(actions),
                    System.currentTimeMillis() + 60_000, ClusterQueue.State.WAITING, "other-controller", null, null, 0));
        }
        awaitMirrored();
        for (int i = 0; i < 2; i++) {
            Queue.Item local = Queue.getInstance().getLocalItem(ids[i]);
            assertNotNull(local, "mirrored as an item of its own");
            assertEquals(1, local.getActions(Folds.class).size());
            assertEquals(what[i], local.getActions(Folds.class).get(0).what);
            other.cancel(ids[i]); // the store outlives this test
        }
    }

    @Test
    void dedupKeysAreUnambiguousAndKeepSensitiveValuesOut() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("keys");
        String one = SharedQueue.dedupKey(p, List.of(new ParametersAction(new StringParameterValue("a", "x\nb=y"))));
        String two = SharedQueue.dedupKey(p, List.of(new ParametersAction(new StringParameterValue("a", "x"), new StringParameterValue("b", "y"))));
        assertNotEquals(one, two);

        String secret = SharedQueue.dedupKey(p, List.of(new ParametersAction(new PasswordParameterValue("pw", "hunter2"))));
        assertEquals(secret, SharedQueue.dedupKey(p, List.of(new ParametersAction(new PasswordParameterValue("pw", "hunter2")))));
        assertNotEquals(secret, SharedQueue.dedupKey(p, List.of(new ParametersAction(new PasswordParameterValue("pw", "hunter3")))));
        // the same value as a plain string gives another key: the sensitive one is keyed, not hashed as is
        assertNotEquals(secret, SharedQueue.dedupKey(p, List.of(new ParametersAction(new StringParameterValue("pw", "hunter2")))));
    }

    @Test
    void requestsTheLocalQueueKeepsApartAreNotFoldedWhenShared() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("apart");
        p.scheduleBuild2(60, new KeptApart("one"));
        p.scheduleBuild2(60, new KeptApart("two"));
        // equal requests too (as replaying a Pipeline twice with the same script): asked, the action keeps them apart
        p.scheduleBuild2(60, new KeptApart("same"));
        p.scheduleBuild2(60, new KeptApart("same"));
        assertEquals(4, Queue.getInstance().getItems().length, "kept apart here");
        for (Queue.Item item : Queue.getInstance().getItems()) {
            assertEquals(item.getId(), awaitShared(item.getId(), x -> true).id(), "shared as an item of its own");
        }
        Thread.sleep(500); // a fold would have cancelled one by now
        assertEquals(4, Queue.getInstance().getItems().length, "all still waiting");
        Queue.getInstance().clear();
    }

    @Test
    void requestsWithActionsFoldedLocallyOnlyFoldIntoEqualOnesWhenShared() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("foldable");
        String plain = SharedQueue.dedupKey(p, List.of(new CauseAction(new Cause.UserIdCause("a"))));
        assertEquals(plain, SharedQueue.dedupKey(p, List.of(new CauseAction(new Cause.UserIdCause("b")))), "causes merge");
        String step = SharedQueue.dedupKey(p, List.of(new CauseAction(new Cause.UserIdCause("a")), new Folds("step-1")));
        assertNotEquals(plain, step);
        assertNotEquals(step, SharedQueue.dedupKey(p, List.of(new Folds("step-2"))));
        assertEquals(step, SharedQueue.dedupKey(p, List.of(new Folds("step-1"), new CauseAction(new Cause.UserIdCause("b")))));
    }

    /** Asks the local queue to keep its request apart from any other, as a replay does. */
    public static class KeptApart extends InvisibleAction implements Queue.QueueAction {
        private final String what;

        public KeptApart(String what) {
            this.what = what;
        }

        @Override
        public boolean shouldSchedule(List<Action> actions) {
            return true;
        }
    }

    /** Folds into the item its request is folded into, as a {@code build} step's action does. */
    public static class Folds extends InvisibleAction implements FoldableAction {
        private final String what;

        public Folds(String what) {
            this.what = what;
        }

        @Override
        public void foldIntoExisting(Queue.Item item, Queue.Task owner, List<Action> otherActions) {
            item.addAction(this);
        }
    }

    @Test
    void aBurstOfItemsTakesItsIdsFromThoseTakenAhead() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("burst");
        Thread.sleep(1_000); // ids are taken ahead at startup, off the queue lock
        singleIds.set(0);
        for (int i = 0; i < 30; i++) {
            p.scheduleBuild2(60, new KeptApart("burst-" + i));
        }
        assertEquals(30, Queue.getInstance().getItems().length);
        assertEquals(0, singleIds.get(), "no id was taken from the store with the queue lock held");
        Queue.getInstance().clear();
    }

    @Test
    void anotherIdStrategyIsUsedWithoutASharedQueue() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unshared");
        noQueue = true;
        try {
            p.scheduleBuild2(60);
            assertEquals(1_000_001, Queue.getInstance().getItems()[0].getId(), "as if the shared queue's strategy were not there");
            Queue.getInstance().clear();
        } finally {
            noQueue = false;
        }
    }

    @TestExtension("anotherIdStrategyIsUsedWithoutASharedQueue")
    public static class OtherIds extends jenkins.model.queue.QueueIdStrategy {
        private long next = 1_000_000;

        @Override
        public synchronized long generateIdFor(Queue.Task project, List<Action> actions) {
            return ++next;
        }
    }

    @Test
    void anUpdateReplacesAllTheActions() throws Exception {
        r.createFreeStyleProject("updated");
        ClusterQueue.Record rec = publishElsewhere("updated", 60_000, "dave");
        Queue.Item local = Queue.getInstance().getLocalItem(rec.id());
        local.addAction(new ParametersAction(new StringParameterValue("gone", "1")));

        List<Action> shared = List.of(new CauseAction(new Cause.UserIdCause("dave")),
                new ParametersAction(new StringParameterValue("a", "1")), new ParametersAction(new StringParameterValue("b", "2")));
        assertTrue(Queue.getInstance().updateShared(rec.id(), Calendar.getInstance(), shared));

        local = Queue.getInstance().getLocalItem(rec.id());
        assertEquals(2, local.getActions(ParametersAction.class).size());
        assertNull(local.getActions(ParametersAction.class).stream().map(pa -> pa.getParameter("gone")).filter(Objects::nonNull).findAny().orElse(null));
        other.cancel(rec.id()); // the store outlives this test
    }

    @Test
    void flyweightWaitingForItsNodeStartsOnlyWhereClaimed() throws Exception {
        long grace = SharedQueue.ORIGIN_GRACE_MILLIS;
        SharedQueue.ORIGIN_GRACE_MILLIS = 60_000;
        try {
            FlyweightProject p = r.jenkins.createProject(FlyweightProject.class, "flyweight");
            p.setAssignedLabel(Label.get("later"));
            ClusterQueue.Record rec = publishElsewhere("flyweight", 0, "judy");
            // no node has its label yet: it waits among the buildables for one to be provisioned
            for (int i = 0; i < 100 && !(Queue.getInstance().getItem(rec.id()) instanceof Queue.BuildableItem); i++) {
                Thread.sleep(100);
            }
            assertThat(Queue.getInstance().getItem(rec.id()), instanceOf(Queue.BuildableItem.class));
            r.createOnlineSlave(Label.get("later"));
            Thread.sleep(3_000);
            assertNull(p.getLastBuild(), "left to the controller it was scheduled on, which has not let it go");
            other.cancel(rec.id()); // the store outlives this test
        } finally {
            SharedQueue.ORIGIN_GRACE_MILLIS = grace;
        }
    }

    /** A job run without an executor of its own, as Pipelines are. */
    public static class FlyweightProject extends Project<FlyweightProject, FlyweightBuild> implements TopLevelItem, Queue.FlyweightTask {
        public FlyweightProject(ItemGroup<?> parent, String name) {
            super(parent, name);
        }

        @Override
        protected Class<FlyweightBuild> getBuildClass() {
            return FlyweightBuild.class;
        }

        @Override
        public TopLevelItemDescriptor getDescriptor() {
            return (TopLevelItemDescriptor) Jenkins.get().getDescriptorOrDie(FlyweightProject.class);
        }

        @TestExtension("flyweightWaitingForItsNodeStartsOnlyWhereClaimed")
        public static class DescriptorImpl extends AbstractProject.AbstractProjectDescriptor {
            @Override
            public TopLevelItem newInstance(ItemGroup parent, String name) {
                return new FlyweightProject(parent, name);
            }
        }
    }

    public static class FlyweightBuild extends Build<FlyweightProject, FlyweightBuild> {
        public FlyweightBuild(FlyweightProject project) throws java.io.IOException {
            super(project);
        }

        public FlyweightBuild(FlyweightProject project, java.io.File dir) throws java.io.IOException {
            super(project, dir);
        }
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
