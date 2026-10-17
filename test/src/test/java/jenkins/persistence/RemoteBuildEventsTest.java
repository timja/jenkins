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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.OneShotEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.PeepholePermalink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Builds completed, edited or deleted by another controller (simulated by a store view with another origin) are applied here.
 */
@WithJenkins
class RemoteBuildEventsTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return !jobKey.equals("jobs/unshared");
        }
    };
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore() {
        @Override
        public InputStream get(String key) {
            CountDownLatch blocked = blockGet;
            if (blocked != null) {
                getStarted.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return super.get(key);
        }
    };
    /** While set, blob reads wait for it. */
    static volatile CountDownLatch blockGet;
    static volatile CountDownLatch getStarted;

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
        BLOBS.clear();
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ItemChangeApplier.isIdle(), "changes applied");
    }

    private static String record(String job, int number) {
        return new String(STORE.read("jobs/" + job + "/builds/" + number + "/build.xml").content(), StandardCharsets.UTF_8);
    }

    /** Publishes a copy of a local build as build {@code number} made by the other controller. */
    private void publishElsewhere(String job, int from, int number, String recordXml) throws Exception {
        try (InputStream snapshot = BLOBS.get("jobs/" + job + "/builds/" + from + "/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/" + job + "/builds/" + number + "/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
        }
        other.write("jobs/" + job + "/builds/" + number + "/build.xml", recordXml.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        // as the controller completing a build does, it then updates the shared permalinks (StoredPermalinks)
        boolean ok = recordXml.contains("<result>SUCCESS</result>");
        String key = "jobs/" + job + "/builds/permalinks";
        PersistenceStore.Entry e = other.read(key);
        java.util.Map<String, String> links = new java.util.TreeMap<>();
        if (e != null) {
            for (String line : new String(e.content(), StandardCharsets.UTF_8).split("\n")) {
                if (line.contains(" ")) {
                    links.put(line.substring(0, line.indexOf(' ')), line.substring(line.indexOf(' ') + 1));
                }
            }
        }
        for (String id : ok ? List.of("lastCompletedBuild", "lastSuccessfulBuild", "lastStableBuild")
                : List.of("lastCompletedBuild", "lastFailedBuild", "lastUnsuccessfulBuild")) {
            links.put(id, String.valueOf(number));
        }
        StringBuilder sb = new StringBuilder();
        links.forEach((id, n) -> sb.append(id).append(' ').append(n).append('\n'));
        other.write(key, sb.toString().getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();
    }

    private static List<Integer> numbers(FreeStyleProject p) {
        return p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList();
    }

    @Test
    void buildCompletedElsewhereAppears() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("appears");
        r.buildAndAssertSuccess(p);
        assertEquals(1, p.getLastSuccessfulBuild().getNumber()); // cache the permalink

        publishElsewhere("appears", 1, 2, record("appears", 1));

        File dir2 = new File(p.getBuildDir(), "2");
        assertFalse(new File(dir2, "build.xml").exists(), "not materialized until opened");
        assertEquals(List.of(2, 1), numbers(p));
        assertEquals(2, p.getLastBuild().getNumber());
        assertEquals(2, p.getLastSuccessfulBuild().getNumber());
        assertNull(p.getLastFailedBuild());

        publishElsewhere("appears", 1, 3, record("appears", 1).replace("<result>SUCCESS</result>", "<result>FAILURE</result>"));
        assertEquals(3, p.getLastFailedBuild().getNumber());
        assertEquals(2, p.getLastSuccessfulBuild().getNumber());

        // the other controller took 2 and 3 from the shared sequence
        other.write("jobs/appears/nextBuildNumber", "4\n".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        assertEquals(4, r.buildAndAssertSuccess(p).getNumber());
        assertEquals(List.of(4, 3, 2, 1), numbers(p));
    }

    @Test
    void recordEditedElsewhereIsReloaded() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("edited");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(p);
        assertNull(b1.getDescription());

        other.write("jobs/edited/builds/1/build.xml", record("edited", 1).replaceFirst("<build>", "<build>\n  <description>set elsewhere</description>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();

        assertEquals("set elsewhere", p.getBuildByNumber(1).getDescription());
        assertThat(new String(Files.readAllBytes(new File(b1.getRootDir(), "build.xml").toPath()), StandardCharsets.UTF_8),
                containsString("set elsewhere"));
        assertEquals("set elsewhere", ((FreeStyleBuild) r.jenkins.getItemByFullName("edited", FreeStyleProject.class).getLastBuild()).getDescription());
    }

    @Test
    void buildDeletedElsewhereDisappearsSilently() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleted");
        r.buildAndAssertSuccess(p);
        publishElsewhere("deleted", 1, 2, record("deleted", 1));
        assertNotNull(p.getBuildByNumber(2)); // materialize it
        assertEquals(2, p.getLastSuccessfulBuild().getNumber());
        int deletesBefore = RemoteDeletes.count.get();

        other.delete("jobs/deleted/builds/2/build.xml", PersistenceStore.ANY);
        awaitApplied();

        assertEquals(List.of(1), numbers(p));
        assertFalse(new File(p.getBuildDir(), "2").exists(), "local directory removed");
        assertEquals(1, p.getLastSuccessfulBuild().getNumber());
        assertEquals(1, p.getLastBuild().getNumber());
        assertEquals(deletesBefore, RemoteDeletes.count.get(), "RunListener.onDeleted is not fired for a remote deletion");
    }

    @Test
    void staleDeletionLeavesABuildStoredAgainAlone() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("stale-deletion");
        r.buildAndAssertSuccess(p);
        // a deletion of an earlier job's #1, applied late after this name was reused here
        BuildRecords.deletedElsewhere(p, 1);
        assertNotNull(p.getBuildByNumber(1));
        assertTrue(new File(p.getBuildDir(), "1/build.xml").isFile());
    }

    @Test
    void staleDeletionLeavesABuildRunningHereAlone() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("running-deletion");
        OneShotEvent release = new OneShotEvent();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                release.block();
                return true;
            }
        });
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        FreeStyleBuild b = f.waitForStart();
        BuildRecords.deletedElsewhere(p, b.getNumber());
        assertTrue(b.getRootDir().isDirectory(), "a running build's directory is kept");
        assertEquals(b, p.getBuildByNumber(b.getNumber()));
        release.signal();
        r.assertBuildStatusSuccess(f);
    }

    @Test
    void editMadeElsewhereBeforeARenameIsApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("edited-before");
        r.buildAndAssertSuccess(p);
        synchronized (p._getRuns()) {
            // the edit is applied here only after the rename that follows it elsewhere is stored
            other.write("jobs/edited-before/builds/1/build.xml", record("edited-before", 1).replaceFirst("<build>", "<build>\n  <description>set elsewhere</description>")
                    .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            BLOBS.move("jobs/edited-before", "jobs/edited-after");
            other.move("jobs/edited-before", "jobs/edited-after");
        }
        awaitApplied();
        assertEquals("edited-after", p.getName());
        assertEquals("set elsewhere", p.getBuildByNumber(1).getDescription());
    }

    @Test
    void buildNotMaterializedOnceIsRetriedWhenChangedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("retried");
        r.buildAndAssertSuccess(p);
        publishElsewhere("retried", 1, 2, record("retried", 1));
        // its snapshot is not there (moving, say) when first opened
        BLOBS.delete("jobs/retried/builds/2");
        assertNull(p.getBuildByNumber(2));
        publishElsewhere("retried", 1, 2, record("retried", 1).replaceFirst("<build>", "<build>\n  <description>again</description>"));
        assertNotNull(p.getBuildByNumber(2), "the build is looked for again");
        assertEquals("again", p.getBuildByNumber(2).getDescription());
    }

    @Test
    void eventsForBuildsNotSharedChangeNothing() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unshared");
        r.buildAndAssertSuccess(p);
        // a record under a job whose builds the store does not share, written and deleted elsewhere
        other.write("jobs/unshared/builds/1/build.xml", Files.readAllBytes(new File(p.getBuildDir(), "1/build.xml").toPath()), PersistenceStore.ANY);
        other.delete("jobs/unshared/builds/1/build.xml", PersistenceStore.ANY);
        awaitApplied();

        assertEquals(List.of(1), numbers(p));
        assertTrue(new File(p.getBuildDir(), "1/build.xml").isFile(), "the local build is untouched");
    }

    @Test
    void jobDeletedElsewhereLeavesNoRecordBehind() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("gone");
        r.buildAndAssertSuccess(p);
        String late = record("gone", 1);
        // the other controller deletes the job and cleans up; this controller's build #2 then completes and publishes,
        // before it has applied the deletion
        other.delete("jobs/gone/builds/1/build.xml", PersistenceStore.ANY);
        awaitApplied();
        STORE.write("jobs/gone/builds/2/build.xml", late.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        other.delete("jobs/gone/config.xml", PersistenceStore.ANY);
        awaitApplied();

        assertNull(r.jenkins.getItem("gone"));
        assertNull(STORE.read("jobs/gone/builds/2/build.xml"), "the late record is cleaned up here too");
    }

    @Test
    void ownChangesAreNotApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("own");
        r.buildAndAssertSuccess(p);
        long applied = ItemChangeApplier.getAppliedCount();
        p.getBuildByNumber(1).setDescription("local edit");
        awaitApplied();
        assertEquals(applied, ItemChangeApplier.getAppliedCount());
        assertThat(record("own", 1), containsString("local edit"));
    }

    @Test
    void recordChangedElsewhereWhileBeingMaterializedIsRefreshed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("racing");
        r.buildAndAssertSuccess(p);
        publishElsewhere("racing", 1, 2, record("racing", 1));
        blockGet = new CountDownLatch(1);
        getStarted = new CountDownLatch(1);
        AtomicReference<FreeStyleBuild> opened = new AtomicReference<>();
        Thread opener = new Thread(() -> opened.set(p.getBuildByNumber(2))); // first materialization, holding the RunMap
        try {
            opener.start();
            assertTrue(getStarted.await(30, TimeUnit.SECONDS));
            // the record is changed elsewhere after the materialization read it, before it wrote it out
            other.write("jobs/racing/builds/2/build.xml", record("racing", 2).replaceFirst("<build>", "<build>\n  <description>set elsewhere</description>")
                    .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            long deadline = System.currentTimeMillis() + 30_000;
            while (!applierWaitsForBuilds() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(applierWaitsForBuilds(), "the change is being applied");
        } finally {
            blockGet.countDown();
            blockGet = null;
        }
        opener.join();
        assertNotNull(opened.get());
        awaitApplied();
        assertEquals("set elsewhere", p.getBuildByNumber(2).getDescription());
        assertThat(Files.readString(new File(p.getBuildDir(), "2/build.xml").toPath()), containsString("set elsewhere"));
    }

    /** Whether the thread applying changes waits on a job's builds, held by a materialization. */
    private static boolean applierWaitsForBuilds() {
        return Thread.getAllStackTraces().entrySet().stream().anyMatch(e -> e.getKey().getState() == Thread.State.BLOCKED
                && Arrays.stream(e.getValue()).anyMatch(f -> f.getClassName().equals(BuildRecords.class.getName())));
    }

    @Test
    void changesElsewhereDoNotNarrowAnUnresolvedPermalink() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unresolved");
        r.buildAndAssertSuccess(p);
        publishElsewhere("unresolved", 1, 3, record("unresolved", 1));
        MemoryCache.targets.clear(); // as on a controller that has not resolved the permalinks yet
        other.write("jobs/unresolved/builds/1/build.xml", record("unresolved", 1).replaceFirst("<build>", "<build>\n  <description>older</description>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();
        assertEquals(3, p.getLastSuccessfulBuild().getNumber());

        MemoryCache.targets.clear();
        other.delete("jobs/unresolved/builds/1/build.xml", PersistenceStore.ANY);
        awaitApplied();
        assertNotNull(p.getLastSuccessfulBuild());
        assertEquals(3, p.getLastSuccessfulBuild().getNumber());
    }

    /** Keeps permalinks in memory only, so that a test can forget them. */
    @TestExtension("changesElsewhereDoNotNarrowAnUnresolvedPermalink")
    public static class MemoryCache implements PeepholePermalink.Cache {
        static final Map<String, Known> targets = new ConcurrentHashMap<>();

        @Override
        public PermalinkTarget get(Job<?, ?> job, String id) {
            Known t = targets.get(job.getFullName() + "/" + id);
            return t != null ? t : UNKNOWN;
        }

        @Override
        public void put(Job<?, ?> job, String id, Known target) {
            targets.put(job.getFullName() + "/" + id, target);
        }
    }

    @TestExtension
    public static class RemoteDeletes extends RunListener<Run<?, ?>> {
        static final AtomicInteger count = new AtomicInteger();

        @Override
        public void onDeleted(Run<?, ?> run) {
            count.incrementAndGet();
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
