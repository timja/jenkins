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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.BuildTrigger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
            return clusterQueue();
        }
    };

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
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
        assertThat(item.getWhy(), containsString("Upstream project up is building on jenkins-b"));

        other.delete("jobs/up/running/1.xml", PersistenceStore.ANY);
        awaitApplied();
        r.assertBuildStatusSuccess(f);
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
