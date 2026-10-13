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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Running builds are leased by the controller running them; builds of dead controllers are offered for adoption;
 * a controller which lost a build stops it and does not publish it.
 */
@WithJenkins
class BuildLeasesTest {

    /** Origins the store reports as alive; null means everyone. */
    static volatile Set<String> live;

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return true;
        }

        @Override
        public Set<String> liveOrigins() {
            return live;
        }

        @Override
        public Entry read(String key) {
            if (key.equals(failRead)) {
                failRead = null;
                throw new IllegalStateException("the store is unreachable, this time");
            }
            Runnable hook = onRead;
            if (hook != null && key.equals(onReadKey) && Thread.currentThread() == onReadThread) {
                onRead = null;
                hook.run();
            }
            return super.read(key);
        }

        @Override
        public long write(String key, byte[] content, long expectedVersion) throws ConflictException {
            failIfAsked(key);
            return super.write(key, content, expectedVersion);
        }

        @Override
        public long writeIfHeld(String leaseKey, long epoch, String key, byte[] content, long expectedVersion) throws java.io.IOException {
            failIfAsked(key);
            if (key.equals(failWriteOf)) {
                failWriteOf = null;
                throw new java.io.IOException("store unreachable, this time");
            }
            long version = super.writeIfHeld(leaseKey, epoch, key, content, expectedVersion);
            if (key.equals(commitThenFail)) {
                commitThenFail = null;
                throw new java.io.IOException("the connection broke before the answer came");
            }
            return version;
        }
    };

    /** A key whose next lease-fenced write fails. */
    static volatile String failWriteOf;
    /** While set, this controller's writes of the keys it matches fail, as when the store is unreachable. */
    static volatile java.util.function.Predicate<String> failWrites;
    /** A key whose next write with a lease is committed, then fails as if its answer was lost. */
    static volatile String commitThenFail;
    /** Run, once, as the thread {@link #onReadThread} reads {@link #onReadKey}. */
    static volatile Runnable onRead;
    static volatile String onReadKey;
    static volatile Thread onReadThread;

    private static void failIfAsked(String key) {
        java.util.function.Predicate<String> fail = failWrites;
        if (fail != null && fail.test(key)) {
            throw new IllegalStateException("the store is unreachable, for now");
        }
    }

    /** A key whose next read fails. */
    static volatile String failRead;
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

    private JenkinsRule r;
    private PersistenceStore dead;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        dead = STORE.as("dead-controller");
        live = null;
        failRead = null;
        failWrites = null;
        commitThenFail = null;
        onRead = null;
        Adopter.prepared.clear();
        Adopter.resumed.clear();
        Adopter.abandoned.set(0);
        Adopter.interfere = null;
        Adopter.accept = true;
        Adopter.failPrepare = 0;
        Adopter.failResume = 0;
        Adopter.declineAfterInterfering = false;
        failWriteOf = null;
    }

    private static byte[] placeholder(String origin, int number, long epoch, String extra) {
        return ("origin=" + origin + "\ncontroller=c-" + origin + "\ndisplayName=#" + number + "\nstartTime=1700000000000\nepoch=" + epoch + "\n" + extra)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String text(String key) {
        PersistenceStore.Entry e = STORE.read(key);
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8);
    }

    /** A build that waits until released. */
    private static FreeStyleProject blocking(JenkinsRule r, String name, CountDownLatch started, CountDownLatch release) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject(name);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                started.countDown();
                return release.await(60, TimeUnit.SECONDS);
            }
        });
        return p;
    }

    @Test
    void leaseHeldWhileRunningAndReleasedAfter() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "leased", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        String key = "jobs/leased/running/1.xml";

        RunningBuilds.Placeholder mine = RunningBuilds.ownedPlaceholder(p.getBuildByNumber(1));
        assertNotNull(mine);
        assertThat(mine.getEpoch(), greaterThan(0L));
        assertThat(text(key), containsString("epoch=" + mine.getEpoch()));
        assertEquals(0, dead.leases().acquire(key), "a live owner's lease cannot be taken");
        assertTrue(RunningBuilds.owns(p.getBuildByNumber(1)));

        RunningBuilds.update(p.getBuildByNumber(1), Map.of("savePoint", "7"));
        assertThat(text(key), containsString("x.savePoint=7"));

        release.countDown();
        r.assertBuildStatusSuccess(f);
        assertNull(text(key));
        assertThat(dead.leases().acquire(key), greaterThan(0L)); // released
    }

    @Test
    void buildWhoseLeaseIsHeldElsewhereIsStopped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("contested");
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                Thread.sleep(30_000); // interrupted
                return true;
            }
        });
        // another live controller already holds the lease of #1
        assertThat(STORE.as("live-elsewhere").leases().acquire("jobs/contested/running/1.xml"), greaterThan(0L));
        FreeStyleBuild b = p.scheduleBuild2(0).get();
        assertEquals(Result.ABORTED, b.getResult());
        assertNull(text("jobs/contested/running/1.xml"), "no placeholder of its own");
        assertNull(text("jobs/contested/builds/1/build.xml"), "not published from here");
    }

    @Test
    void aBuildTakenOverWhilePublishingIsNotPublishedFromHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("fenced");
        FreeStyleBuild b = r.buildAndAssertSuccess(p); // published while TakeOverWhilePublishing takes it over
        assertNull(text("jobs/fenced/builds/1/build.xml"), "the record is not written: the lease was lost");
        assertNull(BLOBS.get("jobs/fenced/builds/1/files.tgz"), "nor its snapshot put in place");
        assertNotNull(text("jobs/fenced/running/1.xml"), "the new owner's placeholder is left alone");
        assertTrue(RunningBuilds.wasLost(b));
        // the store outlives this test
        STORE.delete("jobs/fenced/running/1.xml", PersistenceStore.ANY);
        STORE.delete("jobs/fenced/config.xml", PersistenceStore.ANY);
    }

    /** Takes over the lease of a build of "fenced" as it is being published, after the check made before publishing. */
    @TestExtension("aBuildTakenOverWhilePublishingIsNotPublishedFromHere")
    public static class TakeOverWhilePublishing implements BuildRecords.Exclusion {
        @Override
        public java.util.Collection<String> excludes(Run<?, ?> run) {
            if (run.getParent().getName().equals("fenced")) {
                try {
                    live = Set.of("new-owner"); // this controller's membership lapsed
                    PersistenceStore owner = STORE.as("new-owner");
                    long epoch = owner.leases().acquire("jobs/fenced/running/1.xml");
                    owner.write("jobs/fenced/running/1.xml", placeholder("new-owner", 1, epoch, ""), PersistenceStore.ANY);
                    live = null;
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }
            return List.of();
        }
    }

    @Test
    void leaseLapsesWithItsHoldersLiveness() throws Exception {
        PersistenceStore a = STORE.as("a");
        long epoch = a.leases().acquire("jobs/x/running/1.xml");
        assertTrue(a.leases().holds("jobs/x/running/1.xml", epoch));
        live = Set.of("someone-else");
        assertFalse(a.leases().holds("jobs/x/running/1.xml", epoch));
    }

    @Test
    void adoptionWaitsWhileAnotherBuildOfTheJobRunsElsewhere() throws Exception {
        r.createFreeStyleProject("serial"); // one build at a time
        String key = "jobs/serial/running/1.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, deadEpoch, "x.savePoint=3\n"), PersistenceStore.ABSENT);
        // a third controller started a newer build of it meanwhile
        PersistenceStore third = STORE.as("third");
        long exclusive = third.leases().acquire("jobs/serial/running/exclusive");
        assertThat(exclusive, greaterThan(0L));
        live = Set.of(STORE.getOrigin(), "third");

        RunningBuilds.removeStale();

        assertTrue(Adopter.resumed.isEmpty(), "not resumed while the other build runs");
        assertNotNull(text(key), "left for a later sweep");
        assertThat(Adopter.abandoned.get(), greaterThan(0));
        // the store outlives this test
        third.leases().release("jobs/serial/running/exclusive", exclusive);
        STORE.delete(key, PersistenceStore.ANY);
        STORE.delete("jobs/serial/config.xml", PersistenceStore.ANY);
    }

    @Test
    void buildOfDeadControllerIsAdopted() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("orphan");
        String key = "jobs/orphan/running/1.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, deadEpoch, "x.savePoint=3\n"), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin());

        assertEquals(1, RunningBuilds.removeStale());

        assertEquals(List.of("orphan#1@savePoint=3"), Adopter.prepared);
        assertEquals(1, Adopter.resumed.size());
        long epoch = Adopter.resumed.get(0);
        assertThat(epoch, greaterThan(deadEpoch));
        String now = text(key);
        assertThat(now, containsString("origin=" + STORE.getOrigin()));
        assertThat(now, containsString("epoch=" + epoch));
        assertThat("the owner's properties are kept", now, containsString("x.savePoint=3"));
        assertTrue(RunningBuilds.elsewhere(p).isEmpty(), "running here now, so not a placeholder");
        assertFalse(dead.leases().holds(key, deadEpoch));
    }

    @Test
    void adoptionRetriesWhenOwnerMovesOn() throws Exception {
        r.createFreeStyleProject("racy");
        String key = "jobs/racy/running/1.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, deadEpoch, "x.savePoint=1\n"), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin());
        // while the first adoption attempt is prepared, the old owner records a newer save point
        Adopter.interfere = () -> {
            Adopter.interfere = null;
            dead.write(key, placeholder("dead-controller", 1, deadEpoch, "x.savePoint=2\n"), PersistenceStore.ANY);
        };

        assertEquals(1, RunningBuilds.removeStale());

        assertEquals(List.of("racy#1@savePoint=1", "racy#1@savePoint=2"), Adopter.prepared);
        assertEquals(1, Adopter.abandoned.get());
        assertEquals(1, Adopter.resumed.size());
        assertThat(text(key), containsString("x.savePoint=2"));
    }

    @Test
    void buildNobodyCanAdoptIsRemoved() throws Exception {
        r.createFreeStyleProject("unadoptable");
        String key = "jobs/unadoptable/running/1.xml";
        dead.write(key, placeholder("dead-controller", 1, dead.leases().acquire(key), ""), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin());
        Adopter.accept = false;

        assertEquals(1, RunningBuilds.removeStale());
        assertNull(text(key));
        assertTrue(Adopter.resumed.isEmpty());
    }

    @Test
    void completedBuildOfDeadControllerIsClosedWithItsResultNotResumed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("finished");
        String key = "jobs/finished/running/1.xml";
        // its owner recorded the result on completion, then died before publishing the record (ET17)
        dead.write(key, placeholder("dead-controller", 1, dead.leases().acquire(key), "x.savePoint=3\nx.result=SUCCESS\n"), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin());

        assertEquals(1, RunningBuilds.removeStale());

        assertTrue(Adopter.prepared.isEmpty(), "not offered for adoption");
        assertTrue(Adopter.resumed.isEmpty());
        assertNull(text(key));
        assertThat(text("jobs/finished/builds/1/build.xml"), containsString("<result>SUCCESS</result>"));
        assertEquals(1, p.getLastSuccessfulBuild().getNumber(), "the permalinks are updated as its owner would have");
        assertThat(text("jobs/finished/builds/permalinks"), containsString("lastSuccessfulBuild 1"));
    }

    @Test
    void placeholderUpdatesKeepEarlierProperties() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "merged", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        String key = "jobs/merged/running/1.xml";

        RunningBuilds.update(p.getBuildByNumber(1), Map.of("savePoint", "7"));
        RunningBuilds.update(p.getBuildByNumber(1), Map.of("other", "x"));
        assertThat(text(key), containsString("x.savePoint=7"));
        assertThat(text(key), containsString("x.other=x"));

        release.countDown();
        r.assertBuildStatusSuccess(f);
    }

    @Test
    void concurrentPlaceholderUpdatesAllApply() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "concurrent", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        FreeStyleBuild b = p.getBuildByNumber(1);
        String key = "jobs/concurrent/running/1.xml";

        // e.g. a Pipeline's last save point, shipped while it completes and its result is recorded
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> updates = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String name = "p" + i;
                updates.add(pool.submit(() -> {
                    RunningBuilds.update(b, Map.of(name, "v"));
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> u : updates) {
                u.get(60, TimeUnit.SECONDS); // none conflicts with another update from here
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(RunningBuilds.owns(b));
        for (int i = 0; i < 40; i++) {
            assertThat(text(key), containsString("x.p" + i + "=v"));
        }

        release.countDown();
        r.assertBuildStatusSuccess(f);
    }

    @Test
    void lostBuildIsStoppedAndNotPublished() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "partitioned", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        FreeStyleBuild b = p.getBuildByNumber(1);
        String key = "jobs/partitioned/running/1.xml";

        // this controller looked dead to the others, and one of them adopted the build
        live = Set.of("dead-controller");
        long theirs = dead.leases().acquire(key);
        assertThat(theirs, greaterThan(0L));
        dead.write(key, placeholder("dead-controller", 1, theirs, ""), PersistenceStore.ANY);
        live = null;

        assertFalse(RunningBuilds.owns(b));
        assertThrows(ConflictException.class, () -> RunningBuilds.update(b, Map.of("savePoint", "9")));
        RunningBuilds.checkOwnership();
        assertThrows(ConflictException.class, () -> RunningBuilds.update(b, Map.of("savePoint", "9")), "still refused once lost");

        FreeStyleBuild done = f.get(60, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, done.getResult());
        assertThat(done.getLog(), containsString("Another controller took over this build"));
        assertNull(STORE.read("jobs/partitioned/builds/1/build.xml"), "not published from here");
        assertThat("the new owner's placeholder is untouched", text(key), containsString("origin=dead-controller"));
        File dir = done.getRootDir();
        for (int i = 0; i < 100 && dir.exists(); i++) {
            Thread.sleep(100);
        }
        assertFalse(dir.exists(), "local copy discarded");
        assertNull(p.getBuildByNumber(1));
        forget("partitioned");
    }

    /** Removes what a test left in the store, which outlives it, so that later tests neither load nor sweep it. */
    private static void forget(String... jobs) throws java.io.IOException {
        for (String job : jobs) {
            STORE.delete("jobs/" + job + "/running/1.xml", PersistenceStore.ANY);
            STORE.delete("jobs/" + job + "/config.xml", PersistenceStore.ANY);
        }
    }

    private static void awaitDiscarded(Run<?, ?> b) throws InterruptedException {
        for (int i = 0; i < 300 && RunningBuilds.wasLost(b); i++) {
            Thread.sleep(100);
        }
        assertFalse(RunningBuilds.wasLost(b), "discarded");
    }

    /** A build of a dead controller, offered for adoption. */
    private String orphan(String name) throws Exception {
        r.createFreeStyleProject(name);
        String key = "jobs/" + name + "/running/1.xml";
        dead.write(key, placeholder("dead-controller", 1, dead.leases().acquire(key), "x.savePoint=3\n"), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin());
        return key;
    }

    @Test
    void adoptionWhoseLeaseLapsedWhilePreparingIsAbandoned() throws Exception {
        String key = orphan("slow-adoption");
        // while the save point is fetched, this controller looks gone for long enough that another one takes the lease
        Adopter.interfere = () -> {
            Adopter.interfere = null;
            live = Set.of("third-controller");
            assertThat(STORE.as("third-controller").leases().acquire(key), greaterThan(0L));
            live = Set.of(STORE.getOrigin(), "third-controller");
        };

        assertEquals(0, RunningBuilds.removeStale());

        assertTrue(Adopter.resumed.isEmpty(), "not resumed without the lease");
        assertEquals(1, Adopter.abandoned.get());
        assertThat("the placeholder is left to the lease's holder", text(key), containsString("origin=dead-controller"));
        forget("slow-adoption");
    }

    @Test
    void failedResumptionIsUndoneAndRetried() throws Exception {
        String key = orphan("unresumable");
        Adopter.failResume = 1;

        assertEquals(0, RunningBuilds.removeStale());
        assertThat("it names its gone owner again, not this live controller", text(key), containsString("origin=dead-controller"));
        assertTrue(Adopter.resumed.isEmpty());
        assertThat("nor does this controller keep the job's exclusive lease", STORE.as("third-controller").leases().acquire("jobs/unresumable/running/exclusive"),
                greaterThan(0L));

        assertEquals(1, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size());
        assertThat(text(key), containsString("origin=" + STORE.getOrigin()));
        forget("unresumable");
    }

    @Test
    void failedPreparationKeepsThePlaceholder() throws Exception {
        String key = orphan("not-fetched");
        Adopter.failPrepare = 1;

        assertEquals(0, RunningBuilds.removeStale());
        assertThat("not taken for a build nobody can adopt", text(key), containsString("origin=dead-controller"));

        assertEquals(1, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size());
        assertThat(text(key), containsString("x.savePoint=3"));
        forget("not-fetched");
    }

    @Test
    void completedCopyOfABuildLostMeanwhileIsNotRepublished() throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("seed-lapsed"));
        byte[] record = STORE.read("jobs/seed-lapsed/builds/1/build.xml").content();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "lapsed", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        String key = "jobs/lapsed/running/1.xml";
        // taken over while this controller looked gone; no sweep here noticed before the build completed
        live = Set.of("dead-controller");
        long theirs = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, theirs, ""), PersistenceStore.ANY);
        live = null;
        release.countDown();
        FreeStyleBuild b = f.get(60, TimeUnit.SECONDS);

        // the new owner publishes it; then this controller saves its completed copy (keep forever, a plugin...)
        byte[] theirRecord = (new String(record, StandardCharsets.UTF_8) + "<!-- by the new owner -->\n").getBytes(StandardCharsets.UTF_8);
        synchronized (p.getLazyBuildMixIn()._getRuns()) { // the copy here is refreshed (or dropped) only after the save
            dead.write("jobs/lapsed/builds/1/build.xml", theirRecord, PersistenceStore.ABSENT);
            b.setDescription("changed here");
        }

        assertThat(text("jobs/lapsed/builds/1/build.xml"), containsString("by the new owner"));
        awaitDiscarded(b);
        forget("seed-lapsed", "lapsed");
    }

    @Test
    void buildPublishedByItsNewOwnerBeforeTheLostCopyIsDiscardedIsKnown() throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("seed-handed"));
        byte[] record = STORE.read("jobs/seed-handed/builds/1/build.xml").content();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "handed", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        FreeStyleBuild b = p.getBuildByNumber(1);
        String key = "jobs/handed/running/1.xml";
        live = Set.of("dead-controller");
        long theirs = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, theirs, ""), PersistenceStore.ANY);
        live = null;
        RunningBuilds.checkOwnership();
        assertEquals(Result.ABORTED, f.get(60, TimeUnit.SECONDS).getResult());

        // the new owner completes it quickly, before the stopped copy here is discarded
        try (java.io.InputStream snapshot = BLOBS.get("jobs/seed-handed/builds/1/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/handed/builds/1/files.tgz", new java.io.ByteArrayInputStream(bytes), bytes.length);
        }
        dead.write("jobs/handed/builds/1/build.xml", record, PersistenceStore.ABSENT);
        dead.delete(key, PersistenceStore.ANY);
        awaitDiscarded(b);

        FreeStyleBuild published = p.getBuildByNumber(1);
        assertNotNull(published, "its record is known here");
        assertEquals(Result.SUCCESS, published.getResult());
        forget("seed-handed", "handed");
    }

    @Test
    void discardedCopySavedLaterIsNotPublished() throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("seed-kept"));
        byte[] record = STORE.read("jobs/seed-kept/builds/1/build.xml").content();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "kept", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        FreeStyleBuild b = p.getBuildByNumber(1);
        String key = "jobs/kept/running/1.xml";
        live = Set.of("dead-controller");
        long theirs = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, theirs, ""), PersistenceStore.ANY);
        live = null;
        RunningBuilds.checkOwnership();
        assertEquals(Result.ABORTED, f.get(60, TimeUnit.SECONDS).getResult());
        try (java.io.InputStream snapshot = BLOBS.get("jobs/seed-kept/builds/1/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/kept/builds/1/files.tgz", new java.io.ByteArrayInputStream(bytes), bytes.length);
        }
        dead.write("jobs/kept/builds/1/build.xml", record, PersistenceStore.ABSENT);
        dead.delete(key, PersistenceStore.ANY);
        awaitDiscarded(b);
        FreeStyleBuild published = p.getBuildByNumber(1);
        assertNotNull(published);

        // something kept the discarded copy, and saves it now
        b.setDescription("stale");
        assertThat("the new owner's record stands", text("jobs/kept/builds/1/build.xml"), not(containsString("stale")));
        FreeStyleBuild now = null;
        for (int i = 0; i < 300; i++) {
            now = p.getBuildByNumber(1);
            if (now != null && now != b && now != published) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(now);
        assertNotSame(b, now, "the copy the stale save wrote was discarded again");
        assertNull(now.getDescription(), "the new owner's record here, not the stale save");
        now.setDescription("edited here");
        assertThat("an edit of the build as it is now is published", text("jobs/kept/builds/1/build.xml"), containsString("edited here"));
        forget("seed-kept", "kept");
    }

    @Test
    void placeholderOfABuildWhoseRecordIsStoredIsRemovedNotAdopted() throws Exception {
        r.buildAndAssertSuccess(r.createFreeStyleProject("seed-done"));
        byte[] record = STORE.read("jobs/seed-done/builds/1/build.xml").content();
        String key = orphan("done");
        // its controller published its record, and was gone before removing its placeholder
        dead.write("jobs/done/builds/1/build.xml", record, PersistenceStore.ABSENT);

        RunningBuilds.removeStale();
        assertTrue(Adopter.prepared.stream().noneMatch(a -> a.startsWith("done#")), "a completed build is not offered for adoption");
        assertNull(text(key));
        assertArrayEquals(record, STORE.read("jobs/done/builds/1/build.xml").content());
        forget("seed-done", "done");
    }

    @Test
    void placeholderWrittenByAnAdoptionElsewhereWhilePreparingHereIsKept() throws Exception {
        String key = orphan("adopted-elsewhere");
        // while the save point is fetched, this controller looks gone long enough for another one to adopt the build;
        // then the adopters here find they cannot take it after all
        Adopter.declineAfterInterfering = true;
        Adopter.interfere = () -> {
            Adopter.interfere = null;
            live = Set.of("third-controller");
            PersistenceStore third = STORE.as("third-controller");
            long epoch = third.leases().acquire(key);
            third.writeIfHeld(key, epoch, key, placeholder("third-controller", 1, epoch, "x.savePoint=3\n"), PersistenceStore.ANY);
            live = Set.of(STORE.getOrigin(), "third-controller");
        };

        assertEquals(0, RunningBuilds.removeStale());
        assertThat("the other controller's adoption stands", text(key), containsString("origin=third-controller"));
        forget("adopted-elsewhere");
    }

    @Test
    void adoptionWhoseWriteFailsIsAbandonedAndRetried() throws Exception {
        String key = orphan("unwritten");
        failWriteOf = key;

        assertEquals(0, RunningBuilds.removeStale());
        assertEquals(1, Adopter.abandoned.get(), "what was prepared for it is let go");
        assertTrue(Adopter.resumed.isEmpty());

        assertEquals(1, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size());
        assertEquals(1, Adopter.abandoned.get());
        forget("unwritten");
    }

    @Test
    void adoptionFailingOnARetryKeepsNoExclusiveLease() throws Exception {
        String key = orphan("retried-adoption");
        // the first attempt conflicts with a newer save point (with the job's exclusive lease taken); the second fails
        Adopter.interfere = () -> {
            Adopter.interfere = null;
            Adopter.failPrepare = 1;
            dead.write(key, placeholder("dead-controller", 1, 1, "x.savePoint=4\n"), PersistenceStore.ANY);
        };

        RunningBuilds.removeStale();

        assertThat(text(key), containsString("origin=dead-controller"));
        PersistenceStore third = STORE.as("third-controller");
        long epoch = third.leases().acquire("jobs/retried-adoption/running/exclusive");
        assertThat("this controller, which runs none of its builds, does not keep the job's exclusive lease", epoch, greaterThan(0L));
        third.leases().release("jobs/retried-adoption/running/exclusive", epoch);
        forget("retried-adoption");
    }

    @Test
    void adoptedBuildReleasesTheExclusiveLeaseWithoutASharedQueue() throws Exception {
        String key = orphan("adopted-alone"); // this store has leases, but no queue
        RunningBuilds.removeStale();
        assertThat(text(key), containsString("origin=" + STORE.getOrigin()));
        String exclusive = "jobs/adopted-alone/running/exclusive";
        PersistenceStore third = STORE.as("third-controller");
        assertEquals(0, third.leases().acquire(exclusive), "held for the adopted build");

        // the test adopter loads no build: one of that number, run here, stands for the adopted build completing
        FreeStyleProject p = r.jenkins.getItemByFullName("adopted-alone", FreeStyleProject.class);
        r.buildAndAssertSuccess(p);

        long epoch = third.leases().acquire(exclusive);
        assertThat("released once the adopted build was finalized", epoch, greaterThan(0L));
        third.leases().release(exclusive, epoch);
        assertNull(text(key));
        forget("adopted-alone");
    }

    @Test
    void stopRequestedWhileTheOwnerWasGoneStopsTheAdoptedBuild() throws Exception {
        String key = orphan("stopped-adopted");
        String stop = "jobs/stopped-adopted/running/1.stop";
        dead.write(stop, "controller=jenkins-b\nby=alice\n".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);

        RunningBuilds.removeStale();
        assertThat(text(key), containsString("origin=" + STORE.getOrigin()));
        assertNotNull(text(stop), "kept until the adopted build can be stopped (the test adopter loads none)");

        // a build of that number, run here, stands for the adopted build getting its executor
        FreeStyleProject p = r.jenkins.getItemByFullName("stopped-adopted", FreeStyleProject.class);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                Thread.sleep(30_000);
                return true;
            }
        });
        FreeStyleBuild b = p.scheduleBuild2(0).get(60, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, b.getResult());
        assertThat(b.getLog(), containsString("Stopped from jenkins-b by alice"));
        assertNull(text(stop));
        forget("stopped-adopted");
    }

    @Test
    void anAdoptionWhoseStopRequestCannotBeReadKeepsTheBuild() throws Exception {
        String key = orphan("stop-unreadable");
        failRead = "jobs/stop-unreadable/running/1.stop"; // read once resumed

        assertEquals(1, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size());
        assertThat(text(key), containsString("origin=" + STORE.getOrigin()));
        live = Set.of(STORE.getOrigin(), "third-controller");
        assertEquals(0, STORE.as("third-controller").leases().acquire(key), "the build resumed here keeps its lease");
        SharedQueue.abandonExclusive(r.jenkins.getItemByFullName("stop-unreadable", Job.class), 1);
        forget("stop-unreadable");
    }

    /** What this controller's previous run (another origin, the same name) left: a build it was running. */
    private FreeStyleProject runningBeforeARestart(String name, int number) throws Exception {
        FreeStyleProject p = r.jenkins.getItemByFullName(name, FreeStyleProject.class);
        if (p == null) {
            p = r.createFreeStyleProject(name);
        }
        File dir = new File(p.getBuildDir(), String.valueOf(number));
        assertTrue(dir.mkdirs());
        java.nio.file.Files.writeString(new File(dir, "build.xml").toPath(), "<?xml version='1.1' encoding='UTF-8'?>\n<build><number>" + number
                + "</number><startTime>1700000000000</startTime></build>");
        STORE.as("previous-run").write("jobs/" + name + "/running/" + number + ".xml", ("origin=previous-run\ncontroller=" + RunningBuilds.CONTROLLER_NAME
                + "\ndisplayName=#" + number + "\nstartTime=1700000000000\nepoch=1\n").getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        RunningBuilds.invalidate("jobs/" + name);
        return p;
    }

    @Test
    void aBuildReclaimedAfterARestartWaitsWhileAnotherBuildOfTheJobRunsElsewhere() throws Exception {
        FreeStyleProject p = runningBeforeARestart("reclaimed-serial", 1); // one build at a time
        String key = "jobs/reclaimed-serial/running/1.xml";
        String exclusive = "jobs/reclaimed-serial/running/exclusive";
        // while this controller restarted, its lease on the job lapsed, and another controller started a newer build
        PersistenceStore third = STORE.as("third-controller");
        long epoch = third.leases().acquire(exclusive);
        assertThat(epoch, greaterThan(0L));
        live = Set.of(STORE.getOrigin(), "third-controller");

        assertThrows(RunningBuilds.NotYet.class, () -> RunningBuilds.reclaim(p, 1), "not resumed while the other build runs");
        assertThat(text(key), containsString("origin=previous-run"));

        third.leases().release(exclusive, epoch);
        assertTrue(RunningBuilds.reclaim(p, 1));
        assertThat(text(key), containsString("origin=" + STORE.getOrigin()));
        assertEquals(0, third.leases().acquire(exclusive), "the job's lease is this controller's again, for the build");
        SharedQueue.abandonExclusive(p, 1);
        forget("reclaimed-serial");
    }

    @Test
    void aBuildNotReclaimedIsTriedAgainWithoutReloadingTheOthers() throws Exception {
        long delay = RunningBuilds.Listener.RECLAIM_RETRY_SECONDS;
        RunningBuilds.Listener.RECLAIM_RETRY_SECONDS = 1;
        try {
            FreeStyleProject p = r.createFreeStyleProject("reclaimed-later");
            p.setConcurrentBuild(true);
            FreeStyleBuild first = r.buildAndAssertSuccess(p);
            runningBeforeARestart("reclaimed-later", 2);
            failRead = "jobs/reclaimed-later/running/2.xml"; // the store cannot be read as it is loaded
            p._getRuns().recognizeNumber(2);
            assertNull(p.getBuildByNumber(2), "not loaded without its lease");
            for (int i = 0; i < 100 && !p._getRuns().getLoadedBuilds().containsKey(2); i++) {
                Thread.sleep(100);
            }
            assertNotNull(p._getRuns().getLoadedBuilds().get(2), "loaded once reclaimed");
            assertTrue(first == p.getBuildByNumber(1), "the other builds keep their instances (one may be running)");
        } finally {
            RunningBuilds.Listener.RECLAIM_RETRY_SECONDS = delay;
        }
        STORE.delete("jobs/reclaimed-later/running/2.xml", PersistenceStore.ANY);
        forget("reclaimed-later");
    }

    @Test
    void anAdoptionWrittenAlthoughItsWriteFailedIsResumedByTheNextSweep() throws Exception {
        String key = orphan("unsure");
        commitThenFail = key;

        assertEquals(0, RunningBuilds.removeStale());
        assertThat("written all the same", text(key), containsString("origin=" + STORE.getOrigin()));
        assertTrue(Adopter.resumed.isEmpty());

        assertEquals(1, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size(), "resumed, although it named this controller already");
        assertEquals(0, RunningBuilds.removeStale());
        assertEquals(1, Adopter.resumed.size(), "and only once");
        forget("unsure");
    }

    /** Waits for a completed build's publication to fail, so that it is retried. */
    private static void awaitUnpublished(Job<?, ?> job) throws InterruptedException {
        for (int i = 0; i < 300 && !BuildRecords.hasUnpublished(job); i++) {
            Thread.sleep(100);
        }
        assertTrue(BuildRecords.hasUnpublished(job), "publication failed, and is retried");
    }

    @Test
    void aJobWithABuildNotPublishedYetIsNotRenamed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unpublished");
        failWrites = k -> k.startsWith("jobs/unpublished/builds/");
        r.buildAndAssertSuccess(p);
        awaitUnpublished(p);
        assertTrue(p.isBuilding(), "running, for the other controllers");
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        assertEquals(400, wc.getPage(wc.addCrumb(new org.htmlunit.WebRequest(new java.net.URL(r.getURL(), "job/unpublished/confirmRename?newName=renamed"),
                org.htmlunit.HttpMethod.POST))).getWebResponse().getStatusCode(), "no rename meanwhile: its retry finds it by the job's name");

        failWrites = null;
        new BuildRecords.Unpublished().execute(hudson.model.TaskListener.NULL);
        assertNotNull(text("jobs/unpublished/builds/1/build.xml"), "published");
        assertNull(text("jobs/unpublished/running/1.xml"));
        assertFalse(p.isBuilding());
        p.renameTo("renamed");
        assertNotNull(text("jobs/renamed/builds/1/build.xml"));
    }

    @Test
    void aBuildDeletedBeforeItIsPublishedLeavesNothingRunning() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleted-unpublished");
        String key = "jobs/deleted-unpublished/running/1.xml";
        failWrites = k -> k.startsWith("jobs/deleted-unpublished/builds/");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        awaitUnpublished(p);
        assertNotNull(text(key), "running, for the other controllers");

        b.delete();
        failWrites = null;

        assertNull(text(key), "nor once deleted");
        long epoch = dead.leases().acquire(key);
        assertThat("its lease is released", epoch, greaterThan(0L));
        dead.leases().release(key, epoch);
        assertFalse(p.isBuilding());
        new BuildRecords.Unpublished().execute(hudson.model.TaskListener.NULL);
        assertNull(text("jobs/deleted-unpublished/builds/1/build.xml"), "nor published later");
    }

    @Test
    void aSavePointRecordedBeforeARegistrationIsRetriedIsKept() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "save-points", started, release);
        String key = "jobs/save-points/running/1.xml";
        failRead = "jobs/save-points/running/1.stop"; // its registration fails after the placeholder is written
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        assertNull(failRead);
        FreeStyleBuild b = p.getBuildByNumber(1);
        RunningBuilds.update(b, Map.of("savePoint", "9"));
        assertThat(text(key), containsString("x.savePoint=9"));

        RunningBuilds.registerPending();

        assertThat("not written again without it", text(key), containsString("x.savePoint=9"));
        release.countDown();
        r.assertBuildStatusSuccess(f);
    }

    static volatile CountDownLatch publishing;
    static volatile CountDownLatch registered;

    @Test
    void aBuildRegisteredAsItCompletesIsPublishedUnderThatLease() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "late", started, release);
        String key = "jobs/late/running/1.xml";
        failWrites = k -> k.equals(key); // not registered as it starts
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        failWrites = null;
        FreeStyleBuild b = p.getBuildByNumber(1);
        assertNull(RunningBuilds.ownedPlaceholder(b), "not registered");

        // registered again: its placeholder is written; it then completes and is published under that lease, while
        // the registration finds it completed
        long delay = BuildRecords.COMPLETE_AGAIN_SECONDS;
        BuildRecords.COMPLETE_AGAIN_SECONDS = 1;
        CountDownLatch registering = new CountDownLatch(1);
        publishing = new CountDownLatch(1);
        registered = new CountDownLatch(1);
        try {
            Thread registration = new Thread(() -> {
                RunningBuilds.registerPending();
                registered.countDown();
            });
            onReadKey = "jobs/late/running/1.stop";
            onReadThread = registration;
            onRead = () -> {
                registering.countDown();
                try {
                    assertTrue(publishing.await(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            };
            registration.start();
            assertTrue(registering.await(60, TimeUnit.SECONDS));
            release.countDown();
            r.assertBuildStatusSuccess(f);
            registration.join(60_000);
            for (int i = 0; i < 100 && (text("jobs/late/builds/1/build.xml") == null || text(key) != null); i++) {
                Thread.sleep(100);
            }
            assertFalse(RunningBuilds.wasLost(b), "not taken for lost");
            assertNotNull(text("jobs/late/builds/1/build.xml"), "published");
            assertNull(text(key), "and no longer running");
            Thread.sleep(2_000); // completed again
            assertNotNull(text("jobs/late/builds/1/build.xml"));
            assertNull(text(key));
            assertThat(b.getRootDir().toString(), b.getRootDir().isDirectory());
        } finally {
            BuildRecords.COMPLETE_AGAIN_SECONDS = delay;
        }
    }

    @Test
    void aBuildLostBeforeItIsCompletedAgainIsNotPublishedFromHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("relost");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = "jobs/relost/running/1.xml";
        String record = "jobs/relost/builds/1/build.xml";
        long published = STORE.read(record).version();
        // taken over by another controller (its registration here having raced its completion), whose placeholder is
        // now stored; this controller is to discard its copy
        live = Set.of("dead-controller");
        long theirs = dead.leases().acquire(key);
        dead.write(key, placeholder("dead-controller", 1, theirs, ""), PersistenceStore.ANY);
        live = null;
        RunningBuilds.lost(b);

        BuildRecords.complete(b); // as completeLater does, the registration having found it completed
        assertEquals(published, STORE.read(record).version(), "not published again from here");
        assertNotNull(text(key), "the new owner's placeholder is left alone");
        assertEquals(0, BuildRecords.unpublishedCount());

        RunningBuilds.discard(b);
        awaitDiscarded(b);
        BuildRecords.complete(b);
        assertNotNull(text(key));
        forget("relost");
    }

    /** As "late" is published, after its lease is read: lets the registration go on, and waits for it. */
    @TestExtension("aBuildRegisteredAsItCompletesIsPublishedUnderThatLease")
    public static class RegisteredWhilePublishing implements BuildRecords.Exclusion {
        @Override
        public java.util.Collection<String> excludes(Run<?, ?> run) {
            CountDownLatch p = publishing;
            if (run.getParent().getName().equals("late") && p != null && p.getCount() > 0) {
                p.countDown();
                try {
                    assertTrue(registered.await(60, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return List.of();
        }
    }

    @TestExtension
    public static class Adopter implements BuildAdopter {
        static final List<String> prepared = new CopyOnWriteArrayList<>();
        static final List<Long> resumed = new CopyOnWriteArrayList<>();
        static final AtomicInteger abandoned = new AtomicInteger();
        static volatile ThrowingRunnable interfere;
        static volatile boolean accept = true;
        /** How many of the next preparations, and resumptions, fail. */
        static volatile int failPrepare;
        static volatile int failResume;
        /** Whether to answer that nobody can adopt it, once {@link #interfere} ran. */
        static volatile boolean declineAfterInterfering;

        interface ThrowingRunnable {
            void run() throws Exception;
        }

        @Override
        public Adoption prepare(Job<?, ?> job, RunningBuilds.Placeholder placeholder) throws java.io.IOException {
            if (!accept) {
                return null;
            }
            if (failPrepare > 0) {
                failPrepare--;
                throw new java.io.IOException("save point not fetched, for now");
            }
            prepared.add(job.getFullName() + "#" + placeholder.getNumber() + "@savePoint=" + placeholder.getProperties().get("savePoint"));
            ThrowingRunnable i = interfere;
            if (i != null) {
                try {
                    i.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                if (declineAfterInterfering) {
                    return null;
                }
            }
            return new Adoption() {
                @Override
                public void resume(long epoch) {
                    if (failResume > 0) {
                        failResume--;
                        throw new IllegalStateException("could not resume, this time");
                    }
                    resumed.add(epoch);
                }

                @Override
                public void abandon() {
                    abandoned.incrementAndGet();
                }
            };
        }
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }

    @TestExtension
    public static class Blobs implements BlobStoreProvider {
        @Override
        public BlobStore create() {
            return BLOBS;
        }
    }
}
