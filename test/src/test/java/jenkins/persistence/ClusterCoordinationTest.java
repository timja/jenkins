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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.BuildTrigger;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.queue.QueueStartGate;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Stopping builds from any controller, and blocking queued builds by builds running on other controllers.
 */
@WithJenkins
class ClusterCoordinationTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return true;
        }

        @Override
        public ClusterQueue queue() {
            ClusterQueue cq = clusterQueue();
            return (ClusterQueue) Proxy.newProxyInstance(ClusterQueue.class.getClassLoader(), new Class<?>[] {ClusterQueue.class}, (proxy, m, args) -> {
                if (m.getName().equals("get") && failingReads.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IOException("the store cannot be read");
                }
                Object result;
                try {
                    result = m.invoke(cq, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (m.getName().equals("claim") && lostClaimAnswers.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    failingReads.set(1);
                    throw new IOException("connection reset after claiming");
                }
                return result;
            });
        }

        @Override
        public Set<String> liveOrigins() {
            return live;
        }

        @Override
        public BuildLeases leases() {
            BuildLeases leases = super.leases();
            return (BuildLeases) Proxy.newProxyInstance(BuildLeases.class.getClassLoader(), new Class<?>[] {BuildLeases.class}, (proxy, m, args) -> {
                if (m.getName().equals("acquire") && ((String) args[0]).endsWith("/exclusive") && failingExclusive.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IOException("the store is unreachable, this time");
                }
                if (m.getName().equals("release") && ((String) args[0]).endsWith("/exclusive") && failingRelease.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IOException("the store is unreachable, this time");
                }
                try {
                    return m.invoke(leases, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        @Override
        public Entry read(String key) {
            if (key.endsWith(".stop")) {
                stopReads.incrementAndGet();
                if (failingStopReads.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new UncheckedIOException(new IOException("the store cannot be read, this time"));
                }
            }
            return super.read(key);
        }

        @Override
        public Set<String> children(String parentKey) {
            CountDownLatch l = slowListing;
            if (l != null && parentKey.startsWith("jobs/slow")) {
                try {
                    l.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.children(parentKey);
        }
    };

    /** While set, listing below a job named "slow…" waits for it: a store answering slowly. */
    static volatile CountDownLatch slowListing;

    /** How many of the next claims go through but fail to answer, as does the read that follows each. */
    static final AtomicInteger lostClaimAnswers = new AtomicInteger();
    static final AtomicInteger failingReads = new AtomicInteger();
    /** How many of the next acquisitions of a job's exclusive lease here fail. */
    static final AtomicInteger failingExclusive = new AtomicInteger();
    /** How many of the next releases of a job's exclusive lease here fail. */
    static final AtomicInteger failingRelease = new AtomicInteger();
    /** Reads of stop requests, and how many of the next ones fail. */
    static final AtomicInteger stopReads = new AtomicInteger();
    static final AtomicInteger failingStopReads = new AtomicInteger();
    /** Origins the store reports as alive; null means everyone. */
    static volatile Set<String> live;

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
        live = null;
        lostClaimAnswers.set(0);
        failingReads.set(0);
        failingExclusive.set(0);
        failingRelease.set(0);
        failingStopReads.set(0);
        Adopter.resuming = null;
        Adopter.resume = null;
        Adopter.abandoned.set(0);
        HoldClaimed.job = null;
        Adopter.job = null;
        Adopter.resumed.clear();
        slowListing = null;
    }

    /** Waits for the lease to be free, taking it for the other controller; returns its epoch. */
    private long awaitFree(String lease) throws Exception {
        long epoch = 0;
        for (int i = 0; i < 100 && epoch == 0; i++) {
            Thread.sleep(100);
            epoch = other.leases().acquire(lease);
        }
        return epoch;
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
    }

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

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void stopRequestedElsewhereStopsTheBuildHere() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "stopme", started, new CountDownLatch(1));
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));

        other.write("jobs/stopme/running/1.stop", bytes("controller=jenkins-b\nby=alice\n"), PersistenceStore.ANY);
        awaitApplied();

        FreeStyleBuild b = f.get(60, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, b.getResult());
        assertThat(b.getLog(), containsString("Stopped from jenkins-b by alice"));
        assertNull(STORE.read("jobs/stopme/running/1.stop"), "request consumed");
    }

    @Test
    void stopRequestedBeforeTheBuildIsRegisteredStopsIt() throws Exception {
        // requested once another controller saw the placeholder, before this one had registered the build as its own
        other.write("jobs/early/running/1.stop", bytes("controller=jenkins-b\nby=alice\n"), PersistenceStore.ANY);
        awaitApplied();
        CountDownLatch started = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "early", started, new CountDownLatch(1));

        FreeStyleBuild b = p.scheduleBuild2(0).get(60, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, b.getResult());
        assertThat(b.getLog(), containsString("Stopped from jenkins-b by alice"));
    }

    @Test
    void stopOfABuildRunningHereIsImmediate() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "local", started, new CountDownLatch(1));
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));

        RunningBuilds.requestStop(p, 1, "bob");

        FreeStyleBuild b = f.get(60, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, b.getResult());
        assertThat(b.getLog(), containsString("by bob"));
        assertNull(STORE.read("jobs/local/running/1.stop"));
    }

    @Test
    void buildRunningElsewhereCanBeStoppedFromHere() throws Exception {
        r.createFreeStyleProject("remote");
        other.write("jobs/remote/running/3.xml", bytes("origin=other-controller\ncontroller=jenkins-b\ndisplayName=#3\nstartTime=1\n"), PersistenceStore.ABSENT);
        awaitApplied();

        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl("job/remote/running-elsewhere/stop?number=3"), HttpMethod.POST));

        PersistenceStore.Entry request = STORE.read("jobs/remote/running/3.stop");
        assertNotNull(request, "a stop request for the owner");
        assertThat(new String(request.content(), StandardCharsets.UTF_8), containsString("by="));
    }

    @Test
    void stopForABuildThatIsNotRunningIsIgnored() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("not-yet");
        RunningBuilds.requestStop(p, 1, "bob");
        assertNull(STORE.read("jobs/not-yet/running/1.stop"), "no request left for the build that later gets that number");
        r.buildAndAssertSuccess(p);
    }

    @Test
    void stopRequestedByAUserWithABackslashInTheirName() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("domain");
        other.write("jobs/domain/running/3.xml", bytes("origin=other-controller\ncontroller=jenkins-b\ndisplayName=#3\nstartTime=1\n"), PersistenceStore.ABSENT);
        awaitApplied();

        RunningBuilds.requestStop(p, 3, "ACME\\user");

        PersistenceStore.Entry request = STORE.read("jobs/domain/running/3.stop");
        assertNotNull(request);
        Properties read = new Properties(); // as its owner reads it
        read.load(new StringReader(new String(request.content(), StandardCharsets.UTF_8)));
        assertEquals("ACME\\user", read.getProperty("by"));
    }

    @Test
    void nonConcurrentJobWaitsForTheBuildOnAnotherController() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("serial");
        String key = "jobs/serial/running/exclusive";
        long theirs = other.leases().acquire(key);
        assertThat(theirs, greaterThan(0L));

        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        Thread.sleep(6_000); // several maintenance cycles
        assertNull(p.getLastBuild(), "blocked while the other controller builds it");
        assertEquals(1, Queue.getInstance().getItems().length);

        other.leases().release(key, theirs);
        r.assertBuildStatusSuccess(f);
        assertThat("released after the build", other.leases().acquire(key), greaterThan(0L));
    }

    @Test
    void upstreamBuildingElsewhereBlocksDownstream() throws Exception {
        FreeStyleProject up = r.createFreeStyleProject("up");
        FreeStyleProject down = r.createFreeStyleProject("down");
        down.setBlockBuildWhenUpstreamBuilding(true);
        up.getPublishersList().add(new BuildTrigger("down", Result.SUCCESS));
        r.jenkins.rebuildDependencyGraph();
        other.write("jobs/up/running/1.xml", bytes("origin=other-controller\ncontroller=jenkins-b\ndisplayName=#1\nstartTime=1\n"), PersistenceStore.ABSENT);
        awaitApplied();

        QueueTaskFuture<FreeStyleBuild> f = down.scheduleBuild2(0);
        Thread.sleep(6_000);
        assertNull(down.getLastBuild());
        Queue.Item item = Queue.getInstance().getItems()[0];
        // core's own check now sees the build running elsewhere (Job.isBuilding), before SharedBlocking's
        assertThat(item.getWhy(), org.hamcrest.Matchers.anyOf(containsString("Upstream project up is building on jenkins-b"),
                containsString("Upstream project ‘up’ is already building")));

        other.delete("jobs/up/running/1.xml", PersistenceStore.ANY);
        awaitApplied();
        r.assertBuildStatusSuccess(f);
    }

    @Test
    void upstreamBlockingDoesNotReadTheStoreUnderTheQueueLock() throws Exception {
        FreeStyleProject up = r.createFreeStyleProject("slowup");
        FreeStyleProject down = r.createFreeStyleProject("down3");
        down.setBlockBuildWhenUpstreamBuilding(true);
        up.getPublishersList().add(new BuildTrigger("down3", Result.SUCCESS));
        r.jenkins.rebuildDependencyGraph();
        other.write("jobs/slowup/running/1.xml", bytes("origin=other-controller\ncontroller=jenkins-b\ndisplayName=#1\nstartTime=1\n"), PersistenceStore.ABSENT);
        awaitApplied();
        RunningBuilds.invalidate("jobs/slowup");
        CountDownLatch slow = new CountDownLatch(1);
        slowListing = slow;
        try {

            QueueTaskFuture<FreeStyleBuild> f = down.scheduleBuild2(0);
            Future<?> maintained = Executors.newSingleThreadExecutor().submit(() -> Queue.getInstance().maintain());
            maintained.get(10, TimeUnit.SECONDS); // not waiting for the store's answer
            assertNull(down.getLastBuild());
            assertThat(Queue.getInstance().getItems()[0].getWhy(), containsString("Checking whether upstream project slowup is building"));

            slow.countDown();
            Queue.Item item = Queue.getInstance().getItems()[0];
            for (int i = 0; i < 100 && item.getWhy().contains("Checking"); i++) {
                Thread.sleep(100);
                item = Queue.getInstance().getItems()[0];
            }
            // said by AbstractProject (a job building elsewhere is building) or SharedBlocking, whichever looks first
            assertThat(item.getWhy(), containsString("slowup"));
            assertThat(item.getWhy(), not(containsString("Checking")));
            assertNull(down.getLastBuild());

            other.delete("jobs/slowup/running/1.xml", PersistenceStore.ANY);
            awaitApplied();
            r.assertBuildStatusSuccess(f);
        } finally {
            slow.countDown();
        }
    }

    @Test
    void itemsChangedElsewhereUpdateTheDependencyGraph() throws Exception {
        FreeStyleProject down = r.createFreeStyleProject("down2");
        other.write("jobs/up2/config.xml", bytes("<?xml version='1.1' encoding='UTF-8'?><project><publishers><hudson.tasks.BuildTrigger>"
                + "<childProjects>down2</childProjects><threshold><name>SUCCESS</name><ordinal>0</ordinal><color>BLUE</color>"
                + "<completeBuild>true</completeBuild></threshold></hudson.tasks.BuildTrigger></publishers></project>"), PersistenceStore.ABSENT);
        awaitApplied();
        FreeStyleProject up = r.jenkins.getItemByFullName("up2", FreeStyleProject.class);
        assertNotNull(up);
        for (int i = 0; i < 100 && !up.getDownstreamProjects().contains(down); i++) {
            Thread.sleep(100);
        }
        assertTrue(up.getDownstreamProjects().contains(down), "the trigger defined elsewhere links up2 to down2 here");
    }

    @Test
    void cancellingAClaimedItemReleasesTheJobsExclusiveLease() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("claimed-then-cancelled");
        String lease = "jobs/claimed-then-cancelled/running/exclusive";
        HoldClaimed.job = p.getFullName(); // claimed, then held from starting
        p.scheduleBuild2(0);
        Queue.Item item = null;
        for (int i = 0; i < 600 && item == null; i++) {
            Thread.sleep(100);
            Queue.Item it = Queue.getInstance().getItem(p);
            ClusterQueue.Record rec = it == null ? null : STORE.clusterQueue().get(it.getId());
            item = rec != null && STORE.getOrigin().equals(rec.claimedBy()) ? it : null;
        }
        assertNotNull(item, "claimed here");
        assertEquals(0, other.leases().acquire(lease), "held for the item claimed here");

        Queue.getInstance().cancel(item);

        long epoch = awaitFree(lease);
        assertThat("released: no build of it runs here", epoch, greaterThan(0L));
        other.leases().release(lease, epoch);
    }

    @Test
    void claimWithUnknownOutcomeKeepsTheLease() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "unsure", started, release);
        String lease = "jobs/unsure/running/exclusive";
        lostClaimAnswers.set(1);

        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));
        assertEquals(0, lostClaimAnswers.get(), "the claim's answer was lost");
        assertEquals(0, other.leases().acquire(lease), "the lease is kept for the build its claim started here");

        release.countDown();
        r.assertBuildStatusSuccess(f);
        long epoch = awaitFree(lease);
        assertThat("released after the build", epoch, greaterThan(0L));
        other.leases().release(lease, epoch);
    }

    @Test
    void finalizingAnOlderBuildKeepsTheLeaseOfTheNextOne() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = r.createFreeStyleProject("successive");
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                if (build.getNumber() == 1) {
                    return true;
                }
                secondStarted.countDown();
                return release.await(60, TimeUnit.SECONDS);
            }
        });
        String lease = "jobs/successive/running/exclusive";
        // #1 is completed (so #2 may start), but its finalization is slow (a listener before the shared queue's)
        CountDownLatch finalizing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        RunListener<Run<?, ?>> slow = new RunListener<>() {
            @Override
            public void onFinalized(Run<?, ?> run) {
                if (run.getParent() == p && run.getNumber() == 1) {
                    finalizing.countDown();
                    try {
                        finish.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        ExtensionList.lookup(RunListener.class).add(0, slow);
        try {
            QueueTaskFuture<FreeStyleBuild> first = p.scheduleBuild2(0);
            assertTrue(finalizing.await(60, TimeUnit.SECONDS));
            QueueTaskFuture<FreeStyleBuild> second = p.scheduleBuild2(0);
            assertTrue(secondStarted.await(60, TimeUnit.SECONDS));

            finish.countDown();
            r.assertBuildStatusSuccess(first);
            Thread.sleep(1_000);
            assertEquals(0, other.leases().acquire(lease), "still held, for #2, which runs");

            release.countDown();
            r.assertBuildStatusSuccess(second);
            long epoch = awaitFree(lease);
            assertThat(epoch, greaterThan(0L));
            other.leases().release(lease, epoch);
        } finally {
            finish.countDown();
            release.countDown();
            ExtensionList.lookup(RunListener.class).remove(slow);
        }
    }

    @Test
    void orphanIsNotAdoptedWhileAnotherBuildOfTheJobRunsHere() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FreeStyleProject p = blocking(r, "running-here", started, release);
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS)); // holding the job's exclusive lease (this controller's)
        // a build of it whose controller died
        PersistenceStore dead = STORE.as("dead-controller");
        String key = "jobs/running-here/running/7.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, bytes("origin=dead-controller\ncontroller=jenkins-x\ndisplayName=#7\nstartTime=1\nepoch=" + deadEpoch + "\n"), PersistenceStore.ABSENT);
        Adopter.job = p.getFullName();
        live = Set.of(STORE.getOrigin());
        try {
            RunningBuilds.removeStale();
            assertTrue(Adopter.resumed.isEmpty(), "not resumed beside the build running here");
            assertNotNull(STORE.read(key), "left for a later sweep");
        } finally {
            live = null;
            release.countDown();
        }
        r.assertBuildStatusSuccess(f);
        STORE.delete(key, PersistenceStore.ANY);
    }

    @Test
    void adoptionWhoseExclusiveLeaseCannotBeTakenIsAbandoned() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unreachable-lease");
        PersistenceStore dead = STORE.as("dead-controller");
        String key = "jobs/unreachable-lease/running/3.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, bytes("origin=dead-controller\ncontroller=jenkins-x\ndisplayName=#3\nstartTime=1\nepoch=" + deadEpoch + "\n"), PersistenceStore.ABSENT);
        Adopter.job = p.getFullName();
        live = Set.of(STORE.getOrigin());
        failingExclusive.set(1);
        try {
            RunningBuilds.removeStale();
            assertEquals(1, Adopter.abandoned.get(), "what was prepared for it is let go");
            assertTrue(Adopter.resumed.isEmpty());
            assertNotNull(STORE.read(key), "left for a later sweep");
        } finally {
            live = null;
        }
        STORE.delete(key, PersistenceStore.ANY);
    }

    @Test
    void itemIsNotClaimedWhileABuildOfItsJobIsBeingAdoptedHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("adopting");
        PersistenceStore dead = STORE.as("dead-controller");
        String key = "jobs/adopting/running/5.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, bytes("origin=dead-controller\ncontroller=jenkins-x\ndisplayName=#5\nstartTime=1\nepoch=" + deadEpoch + "\n"), PersistenceStore.ABSENT);
        Adopter.job = p.getFullName();
        Adopter.resuming = new CountDownLatch(1);
        Adopter.resume = new CountDownLatch(1);
        live = Set.of(STORE.getOrigin());
        Future<Integer> sweep = Executors.newSingleThreadExecutor().submit(RunningBuilds::removeStale);
        try {
            assertTrue(Adopter.resuming.await(60, TimeUnit.SECONDS), "being resumed: nothing shows it running here yet");
            QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
            Thread.sleep(4_000); // several maintenance cycles
            assertTrue(p.getBuilds().isEmpty(), "not started beside the build being adopted");
            Queue.Item item = Queue.getInstance().getItem(p);
            assertNotNull(item);
            assertNull(STORE.clusterQueue().get(item.getId()).claimedBy(), "not claimed meanwhile");

            Adopter.resume.countDown();
            sweep.get(60, TimeUnit.SECONDS);
            assertEquals(List.of(5), Adopter.resumed);
            // the test adopter loads nothing, so once it is resumed nothing here holds the item back
            r.assertBuildStatusSuccess(f);
        } finally {
            Adopter.resume.countDown();
            live = null;
            SharedQueue.abandonExclusive(p, 5);
            STORE.delete(key, PersistenceStore.ANY);
        }
    }

    /** A build of a new job, made here but not started, adopted as #1: loaded here, but with no executor (yet). */
    private FreeStyleProject adoptedWithoutAnExecutor(String name) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject(name);
        p.setConcurrentBuild(true); // no exclusive lease to keep from other tests
        assertEquals(1, p.createExecutable().getNumber());
        PersistenceStore dead = STORE.as("dead-controller");
        String key = "jobs/" + name + "/running/1.xml";
        long deadEpoch = dead.leases().acquire(key);
        dead.write(key, bytes("origin=dead-controller\ncontroller=jenkins-x\ndisplayName=#1\nstartTime=1\nepoch=" + deadEpoch + "\n"), PersistenceStore.ABSENT);
        Adopter.job = p.getFullName();
        live = Set.of(STORE.getOrigin());
        try {
            RunningBuilds.removeStale(); // (the store outlives tests: others' placeholders may be swept too)
            assertEquals(List.of(1), Adopter.resumed);
        } finally {
            live = null;
        }
        assertThat(new String(STORE.read(key).content(), StandardCharsets.UTF_8), containsString("origin=" + STORE.getOrigin()));
        return p;
    }

    @Test
    void stopOfABuildOwnedHereWithNoExecutorYetIsKept() throws Exception {
        FreeStyleProject p = adoptedWithoutAnExecutor("stop-before-executor");
        RunningBuilds.requestStop(p, 1, "carl");
        assertNotNull(STORE.read("jobs/stop-before-executor/running/1.stop"), "kept until the build can be stopped");
        int reads = stopReads.get();
        for (int i = 0; i < 50 && stopReads.get() == reads; i++) {
            Thread.sleep(100);
        }
        assertThat("looked at again, for when it has an executor", stopReads.get(), greaterThan(reads));
        STORE.delete("jobs/stop-before-executor/running/1.stop", PersistenceStore.ANY);
    }

    @Test
    void stopOfABuildWithNoExecutorYetIsRetriedWhenTheStoreFails() throws Exception {
        FreeStyleProject p = adoptedWithoutAnExecutor("stop-retried");
        other.write("jobs/stop-retried/running/1.stop", bytes("controller=jenkins-b\nby=dora\n"), PersistenceStore.ANY);
        awaitApplied(); // taken here: no executor, so looked at again later
        int reads = stopReads.get();
        failingStopReads.set(1); // when it is
        for (int i = 0; i < 100 && stopReads.get() < reads + 2; i++) {
            Thread.sleep(100);
        }
        assertThat("looked at again after the store failed", stopReads.get(), greaterThan(reads + 1));
        STORE.delete("jobs/stop-retried/running/1.stop", PersistenceStore.ANY);
    }

    @Test
    void ownBuildElsewhereDoesNotBlockAJobInACycleWithItself() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("cycle");
        p.setConcurrentBuild(true);
        p.setBlockBuildWhenUpstreamBuilding(true);
        p.setBlockBuildWhenDownstreamBuilding(true);
        p.getBuildersList().add(new FailureBuilder()); // so that it does not trigger itself again
        p.getPublishersList().add(new BuildTrigger("cycle", Result.SUCCESS));
        r.jenkins.rebuildDependencyGraph();
        other.write("jobs/cycle/running/5.xml", bytes("origin=other-controller\ncontroller=jenkins-b\ndisplayName=#5\nstartTime=1\n"), PersistenceStore.ABSENT);
        awaitApplied();

        // as here, where its own builds do not block it either
        FreeStyleBuild b = p.scheduleBuild2(0).get(60, TimeUnit.SECONDS);
        assertEquals(Result.FAILURE, b.getResult());
        other.delete("jobs/cycle/running/5.xml", PersistenceStore.ANY);
    }

    @Test
    void exclusiveLeaseWhoseReleaseFailedIsReleasedLater() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("release-retried");
        String lease = "jobs/release-retried/running/exclusive";
        failingRelease.set(1);
        r.buildAndAssertSuccess(p);
        assertEquals(0, failingRelease.get(), "the release failed");
        long epoch = awaitFree(lease);
        assertThat("released when retried: else it would block the job everywhere", epoch, greaterThan(0L));
        other.leases().release(lease, epoch);
    }

    /**
     * Holds the items of a job from starting once they are claimed here: at the start gate, where the claim is made,
     * as the claim may land between a dispatcher's look at an item and its start.
     */
    @TestExtension
    public static class HoldClaimed implements QueueStartGate {
        static volatile String job;

        @Override
        public CauseOfBlockage mayStart(@NonNull Queue.BuildableItem item) {
            try {
                ClusterQueue.Record rec = item.task instanceof Job<?, ?> j && j.getFullName().equals(job) ? STORE.clusterQueue().get(item.getId()) : null;
                return rec != null && STORE.getOrigin().equals(rec.claimedBy()) ? new SharedQueue.Blocked("Held by the test") : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Adopts the builds of one job (resuming nothing). */
    @TestExtension
    public static class Adopter implements BuildAdopter {
        static volatile String job;
        static final List<Integer> resumed = new CopyOnWriteArrayList<>();
        static final AtomicInteger abandoned = new AtomicInteger();
        /** If set, resuming counts {@link #resuming} down, then waits for {@link #resume}. */
        static volatile CountDownLatch resuming;
        static volatile CountDownLatch resume;

        @Override
        public Adoption prepare(Job<?, ?> j, RunningBuilds.Placeholder placeholder) {
            if (!j.getFullName().equals(job)) {
                return null;
            }
            return new Adoption() {
                @Override
                public void resume(long epoch) {
                    CountDownLatch l = resume;
                    if (l != null) {
                        resuming.countDown();
                        try {
                            l.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    resumed.add(placeholder.getNumber());
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
}
