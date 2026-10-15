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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    };
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

    private JenkinsRule r;
    private PersistenceStore dead;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        dead = STORE.as("dead-controller");
        live = null;
        Adopter.prepared.clear();
        Adopter.resumed.clear();
        Adopter.abandoned.set(0);
        Adopter.interfere = null;
        Adopter.accept = true;
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

    @TestExtension
    public static class Adopter implements BuildAdopter {
        static final List<String> prepared = new CopyOnWriteArrayList<>();
        static final List<Long> resumed = new CopyOnWriteArrayList<>();
        static final AtomicInteger abandoned = new AtomicInteger();
        static volatile ThrowingRunnable interfere;
        static volatile boolean accept = true;

        interface ThrowingRunnable {
            void run() throws Exception;
        }

        @Override
        public Adoption prepare(Job<?, ?> job, RunningBuilds.Placeholder placeholder) {
            if (!accept) {
                return null;
            }
            prepared.add(job.getFullName() + "#" + placeholder.getNumber() + "@savePoint=" + placeholder.getProperties().get("savePoint"));
            ThrowingRunnable i = interfere;
            if (i != null) {
                try {
                    i.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            return new Adoption() {
                @Override
                public void resume(long epoch) {
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
