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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.queue.QueueTaskFuture;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Builds running on another controller appear as placeholders; placeholders of dead controllers are removed.
 */
@WithJenkins
class RunningBuildsTest {

    /** Origins the store reports as alive; null means membership is not tracked. */
    static volatile Set<String> live;

    /** If set, answers {@link PersistenceStore#liveOrigins} instead of {@link #live} on the test's thread, one answer per call (the last one from then on). */
    static volatile Iterator<Set<String>> liveAnswers;

    static volatile Set<String> lastAnswer;

    static volatile Thread testThread;

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return true;
        }

        @Override
        public Set<String> liveOrigins() {
            Iterator<Set<String>> answers = liveAnswers;
            if (answers == null || Thread.currentThread() != testThread) {
                return live;
            }
            if (answers.hasNext()) {
                lastAnswer = answers.next();
            }
            return lastAnswer;
        }
    };
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
        BLOBS.clear();
        live = null;
        liveAnswers = null;
        testThread = Thread.currentThread();
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ItemChangeApplier.isIdle(), "changes applied");
    }

    private static byte[] placeholder(String origin, String controller, int number) {
        return ("origin=" + origin + "\ncontroller=" + controller + "\ndisplayName=#" + number + "\nstartTime=1700000000000\n").getBytes(StandardCharsets.UTF_8);
    }

    private static List<Integer> elsewhere(FreeStyleProject p) {
        return RunningBuilds.elsewhere(p).stream().map(RunningBuilds.Placeholder::getNumber).toList();
    }

    @Test
    void aCopyStillRunningOfABuildCompletedElsewhereLoadsItsRecord() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("finished-elsewhere");
        r.buildAndAssertSuccess(p);
        // this home's copy says running: the build was adopted and completed elsewhere while this controller was down
        java.nio.file.Path local = new java.io.File(p.getBuildDir(), "1/build.xml").toPath();
        String record = java.nio.file.Files.readString(local);
        java.nio.file.Files.writeString(local, record.replaceAll("<result>[A-Z]+</result>", ""));

        assertTrue(RunningBuilds.reclaim(p, 1));

        assertTrue(java.nio.file.Files.readString(local).contains("<result>SUCCESS</result>"), "the record, not the stale copy");
    }

    @Test
    void runningBuildIsRecordedUntilPublished() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("local");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                started.countDown();
                return release.await(60, TimeUnit.SECONDS);
            }
        });
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0);
        assertTrue(started.await(60, TimeUnit.SECONDS));

        PersistenceStore.Entry entry = STORE.read("jobs/local/running/1.xml");
        assertNotNull(entry);
        assertThat(new String(entry.content(), StandardCharsets.UTF_8), containsString("origin=" + STORE.getOrigin()));
        assertEquals(List.of(), elsewhere(p), "a build running here is not a placeholder here");

        release.countDown();
        r.assertBuildStatusSuccess(f);
        assertNull(STORE.read("jobs/local/running/1.xml"), "removed on completion");
        assertNotNull(STORE.read("jobs/local/builds/1/build.xml"), "record published first");
    }

    @Test
    void buildRunningElsewhereIsShown() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("remote");
        r.buildAndAssertSuccess(p);
        assertEquals(List.of(), elsewhere(p));
        assertThat("none elsewhere: not in the REST API", r.createWebClient().goTo("job/remote/api/json", "application/json").getWebResponse().getContentAsString(),
                not(containsString("buildsElsewhere")));

        other.write("jobs/remote/running/2.xml", placeholder("other-controller", "jenkins-a", 2), PersistenceStore.ABSENT);
        awaitApplied();

        assertEquals(List.of(2), elsewhere(p));
        JenkinsRule.WebClient wc = r.createWebClient();
        assertThat(wc.goTo("job/remote/api/json?tree=actions[builds[number,controller]]", "application/json").getWebResponse().getContentAsString(),
                containsString("{\"controller\":\"jenkins-a\",\"number\":2}"));
        assertThat(wc.goTo("job/remote/").asNormalizedText(), containsString("#2 on jenkins-a"));
        assertEquals(hudson.model.BallColor.BLUE_ANIME, p.getIconColor(), "the job shows as building");
        assertTrue(p.isBuilding(), "building elsewhere");
        org.jvnet.hudson.test.JenkinsRule.WebClient rc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        assertEquals(400, rc.getPage(rc.addCrumb(new org.htmlunit.WebRequest(new java.net.URL(r.getURL(), "job/remote/confirmRename?newName=renamed"), org.htmlunit.HttpMethod.POST))).getWebResponse().getStatusCode(),
                "no rename while it builds on another controller");
        String history = wc.goTo("job/remote/buildHistory/ajax").getWebResponse().getContentAsString();
        assertThat("the build list shows it", history, containsString("Running on jenkins-a"));
        assertThat(history, containsString("running-elsewhere/stop?number=2"));
        // its links answer here too (no owner routing in core): the build, running there
        assertThat(wc.goTo("job/remote/2/").asNormalizedText(), containsString("Running on controller jenkins-a"));
        assertThat(wc.goTo("job/remote/2/console").asNormalizedText(), containsString("This build runs on controller jenkins-a"));
        String api = wc.goTo("job/remote/api/json?tree=lastBuild[number,building],builds[number],buildsElsewhere[number,building,result,url]", "application/json")
                .getWebResponse().getContentAsString();
        assertThat("builds and lastBuild are this controller's, typed as ever", api,
                containsString("\"builds\":[{\"_class\":\"hudson.model.FreeStyleBuild\",\"number\":1}]"));
        assertThat(api, containsString("\"lastBuild\":{\"_class\":\"hudson.model.FreeStyleBuild\",\"building\":false,\"number\":1}"));
        assertThat("the build running elsewhere has a property of its own", api, containsString("\"buildsElsewhere\":[{"
                + "\"building\":true,\"number\":2,\"result\":null,\"url\":\"" + r.getURL() + "job/remote/2/\"}]"));
        assertThat("the schema keeps the build types", wc.goTo("job/remote/api/schema", "application/xml").getWebResponse().getContentAsString(),
                containsString("name=\"lastBuild\" type=\"hudson.model.Run\""));

        // the other controller completes it: record first, then the placeholder goes
        try (InputStream snapshot = BLOBS.get("jobs/remote/builds/1/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/remote/builds/2/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
        }
        other.write("jobs/remote/builds/2/build.xml", STORE.read("jobs/remote/builds/1/build.xml").content(), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(List.of(), elsewhere(p), "hidden once the completed record is known");
        assertEquals(hudson.model.BallColor.BLUE, p.getIconColor());
        assertThat("once completed, the real build", wc.goTo("job/remote/api/json?tree=lastBuild[number,building]", "application/json").getWebResponse().getContentAsString(),
                containsString("\"lastBuild\":{\"_class\":\"hudson.model.FreeStyleBuild\",\"building\":false,\"number\":2}"));
        assertEquals(2, p.getLastBuild().getNumber());

        other.delete("jobs/remote/running/2.xml", PersistenceStore.ANY);
        awaitApplied();
        assertEquals(List.of(), elsewhere(p));
        assertTrue(r.jenkins.getItemByFullName("remote", FreeStyleProject.class).getActions(RunningBuilds.RunningElsewhereAction.class).isEmpty());
    }

    @Test
    void placeholdersOfDeadControllersAreRemoved() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("stale");
        other.write("jobs/stale/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        other.write("jobs/stale/running/2.xml", placeholder("dead-controller", "jenkins-b", 2), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(List.of(2, 1), elsewhere(p));

        assertEquals(0, RunningBuilds.removeStale(), "membership unknown: nothing removed");

        live = ConcurrentHashMap.newKeySet();
        live.add(STORE.getOrigin());
        live.add("other-controller");
        assertEquals(1, RunningBuilds.removeStale());
        assertNull(STORE.read("jobs/stale/running/2.xml"));
        assertNotNull(STORE.read("jobs/stale/running/1.xml"));
        assertEquals(List.of(1), elsewhere(p));
    }

    @Test
    void placeholderOfControllerJoiningDuringTheSweepIsKept() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("joining");
        other.write("jobs/joining/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(List.of(1), elsewhere(p));

        // the other controller (re)started while the sweep listed placeholders: alive, although the first answer missed it
        liveAnswers = List.of(Set.of(STORE.getOrigin()), Set.of(STORE.getOrigin(), "other-controller")).iterator();
        assertEquals(0, RunningBuilds.removeStale());
        assertNotNull(STORE.read("jobs/joining/running/1.xml"));
        assertEquals(List.of(1), elsewhere(p));
    }

    @Test
    void controllerFoundGoneForOneJobIsAskedAboutAgainForTheNext() throws Exception {
        FreeStyleProject a = r.createFreeStyleProject("a-first");
        FreeStyleProject b = r.createFreeStyleProject("b-later");
        other.write("jobs/a-first/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        other.write("jobs/b-later/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        awaitApplied();

        // the other controller comes back (after a pause, say) once the sweep has looked at the first job
        liveAnswers = List.of(Set.of(STORE.getOrigin()), Set.of(STORE.getOrigin()), Set.of(STORE.getOrigin(), "other-controller")).iterator();
        assertEquals(1, RunningBuilds.removeStale());
        assertNull(STORE.read("jobs/a-first/running/1.xml"));
        assertNotNull(STORE.read("jobs/b-later/running/1.xml"), "the placeholder of the controller back is kept");
        assertEquals(List.of(1), elsewhere(b));
        assertEquals(List.of(), elsewhere(a));
    }

    @Test
    void placeholdersFollowAnItemRenamedHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("old");
        other.write("jobs/old/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(List.of(1), elsewhere(p));

        p.renameTo("new");
        FreeStyleProject replacement = r.createFreeStyleProject("old");
        awaitApplied();
        assertEquals(List.of(), elsewhere(replacement), "the renamed job's build is not shown for a new job under the old name");
        assertEquals(List.of(1), elsewhere(p));
    }

    @Test
    void followingABuildElsewhereEndsWithTheRestOfItsRecord() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("followed");
        r.buildAndAssertSuccess(p);
        byte[] log;
        try (InputStream in = p.getBuildByNumber(1).getLogInputStream()) {
            log = in.readAllBytes();
        }
        other.write("jobs/followed/running/2.xml", placeholder("other-controller", "jenkins-a", 2), PersistenceStore.ABSENT);
        awaitApplied();
        assertTrue(RunningBuilds.isRunningElsewhere(p, 2));

        // its owner shipped the first 10 bytes; then the build completed: its record published, its shipped log deleted
        ShippedLog.prefix = java.util.Arrays.copyOf(log, 10);
        ShippedLog.completed = () -> {
            try (InputStream snapshot = BLOBS.get("jobs/followed/builds/1/files.tgz")) {
                byte[] bytes = snapshot.readAllBytes();
                BLOBS.put("jobs/followed/builds/2/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
                other.write("jobs/followed/builds/2/build.xml", STORE.read("jobs/followed/builds/1/build.xml").content(), PersistenceStore.ABSENT);
            }
        };
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(RunningBuilds.followElsewhere(p, 2, true, out));
        assertEquals(new String(log, StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8), "what was shipped, then the rest from the record");
    }

    @Test
    void followingABuildStartedElsewhereThatCompletesBeforeItIsSeenHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("quick");
        r.buildAndAssertSuccess(p);
        byte[] log;
        try (InputStream in = p.getBuildByNumber(1).getLogInputStream()) {
            log = in.readAllBytes();
        }
        // the shared queue said #2 started on another controller; its placeholder never reached here, its record does
        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(1000);
                try (InputStream snapshot = BLOBS.get("jobs/quick/builds/1/files.tgz")) {
                    byte[] bytes = snapshot.readAllBytes();
                    BLOBS.put("jobs/quick/builds/2/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
                }
                other.write("jobs/quick/builds/2/build.xml", STORE.read("jobs/quick/builds/1/build.xml").content(), PersistenceStore.ABSENT);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        publisher.start();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(RunningBuilds.followElsewhere(p, 2, true, out), "followed once its record arrived");
        publisher.join();
        assertEquals(new String(log, StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aBuildAdoptedWhileThisControllerRestartedIsNotResumedFromThisHome() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("restarted");
        java.io.File dir = new java.io.File(p.getBuildDir(), "4");
        dir.mkdirs();
        // what this controller's previous run left in its home: a build it was running
        java.nio.file.Files.writeString(new java.io.File(dir, "build.xml").toPath(),
                "<?xml version='1.1' encoding='UTF-8'?>\n<build><number>4</number><startTime>1700000000000</startTime></build>");
        // ... and which another controller adopted while this one restarted
        other.write("jobs/restarted/running/4.xml", placeholder("other-controller", "jenkins-b", 4), PersistenceStore.ABSENT);
        assertTrue(!RunningBuilds.reclaim(p, 4), "the adopter's copy is the build: not loaded here");

        // a completed build in this home, with no placeholder anywhere, is loaded as usual
        java.io.File done = new java.io.File(p.getBuildDir(), "5");
        done.mkdirs();
        java.nio.file.Files.writeString(new java.io.File(done, "build.xml").toPath(),
                "<?xml version='1.1' encoding='UTF-8'?>\n<build><number>5</number><result>SUCCESS</result></build>");
        assertTrue(RunningBuilds.reclaim(p, 5));
    }

    @Test
    void followingABuildElsewhereWaitsForItsLogToBeShipped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("slowship");
        r.buildAndAssertSuccess(p);
        byte[] log;
        try (InputStream in = p.getBuildByNumber(1).getLogInputStream()) {
            log = in.readAllBytes();
        }
        other.write("jobs/slowship/running/2.xml", placeholder("other-controller", "jenkins-a", 2), PersistenceStore.ABSENT);
        awaitApplied();
        LateShippedLog.log = log;
        LateShippedLog.calls.set(0);
        LateShippedLog.completed = () -> {
            try (InputStream snapshot = BLOBS.get("jobs/slowship/builds/1/files.tgz")) {
                byte[] bytes = snapshot.readAllBytes();
                BLOBS.put("jobs/slowship/builds/2/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
                other.write("jobs/slowship/builds/2/build.xml", STORE.read("jobs/slowship/builds/1/build.xml").content(), PersistenceStore.ABSENT);
            }
        };
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(RunningBuilds.followElsewhere(p, 2, true, out), "followed although nothing was shipped at first");
        assertEquals(new String(log, StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
    }

    @TestExtension("followingABuildElsewhereWaitsForItsLogToBeShipped")
    public static class LateShippedLog implements RunningBuilds.RemoteLog {
        static byte[] log;
        static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        static ShippedLog.Completion completed;

        @Override
        public long copy(Job<?, ?> job, int number, long offset, java.io.OutputStream out) throws java.io.IOException {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                return -1; // not shipped yet
            }
            if (offset == 0) {
                out.write(log, 0, 10);
                return 10;
            }
            try {
                if (completed != null) {
                    completed.run();
                    completed = null; // it completes once
                }
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
            return -1; // deleted once the build completed
        }
    }

    @Test
    void theConsoleOfABuildElsewhereShowsWhatWasShipped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("shipped");
        other.write("jobs/shipped/running/1.xml", placeholder("other-controller", "jenkins-a", 1), PersistenceStore.ABSENT);
        awaitApplied();
        JenkinsRule.WebClient wc = r.createWebClient();
        assertThat(wc.goTo("job/shipped/1/console").asNormalizedText(), containsString("hello from jenkins-a"));
        assertEquals("hello from jenkins-a\n", wc.goTo("job/shipped/1/consoleText", "text/plain").getWebResponse().getContentAsString());
        other.delete("jobs/shipped/running/1.xml", PersistenceStore.ANY); // the store outlives this test
    }

    @TestExtension("theConsoleOfABuildElsewhereShowsWhatWasShipped")
    public static class FixedLog implements RunningBuilds.RemoteLog {
        @Override
        public long copy(Job<?, ?> job, int number, long offset, java.io.OutputStream out) throws java.io.IOException {
            byte[] log = "hello from jenkins-a\n".getBytes(StandardCharsets.UTF_8);
            out.write(log, (int) offset, log.length - (int) offset);
            return log.length;
        }
    }

    @TestExtension("followingABuildElsewhereEndsWithTheRestOfItsRecord")
    public static class ShippedLog implements RunningBuilds.RemoteLog {
        static byte[] prefix;
        static Completion completed;

        interface Completion {
            void run() throws Exception;
        }

        @Override
        public long copy(Job<?, ?> job, int number, long offset, java.io.OutputStream out) throws java.io.IOException {
            if (offset == 0) {
                out.write(prefix);
                return prefix.length;
            }
            try {
                if (completed != null) {
                    completed.run();
                    completed = null; // it completes once
                }
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
            return -1; // deleted once the build completed
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
