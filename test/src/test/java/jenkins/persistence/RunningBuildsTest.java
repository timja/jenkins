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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
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

        other.write("jobs/remote/running/2.xml", placeholder("other-controller", "jenkins-a", 2), PersistenceStore.ABSENT);
        awaitApplied();

        assertEquals(List.of(2), elsewhere(p));
        JenkinsRule.WebClient wc = r.createWebClient();
        assertThat(wc.goTo("job/remote/api/json?tree=actions[builds[number,controller]]", "application/json").getWebResponse().getContentAsString(),
                containsString("{\"controller\":\"jenkins-a\",\"number\":2}"));
        assertThat(wc.goTo("job/remote/").asNormalizedText(), containsString("#2 on jenkins-a"));

        // the other controller completes it: record first, then the placeholder goes
        try (InputStream snapshot = BLOBS.get("jobs/remote/builds/1/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/remote/builds/2/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
        }
        other.write("jobs/remote/builds/2/build.xml", STORE.read("jobs/remote/builds/1/build.xml").content(), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(List.of(), elsewhere(p), "hidden once the completed record is known");
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
