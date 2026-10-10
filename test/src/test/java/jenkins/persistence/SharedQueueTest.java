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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import java.util.List;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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
            return clusterQueue();
        }
    };

    private JenkinsRule r;
    private ClusterQueue other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller").clusterQueue();
    }

    private static void awaitMirrored() throws InterruptedException {
        for (int i = 0; i < 500 && !SharedQueue.Mirror.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(SharedQueue.Mirror.isIdle());
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
        ClusterQueue.Record rec = STORE.clusterQueue().get(id);
        assertNotNull(rec);
        assertEquals("local", rec.task());
        assertEquals(ClusterQueue.State.STARTED, rec.state());
        assertEquals(STORE.getOrigin(), rec.claimedBy());
        assertEquals(1, rec.buildNumber());
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
    void schedulingHereFoldsIntoItemFromElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("folded");
        ClusterQueue.Record rec = publishElsewhere("folded", 60_000, "carol");

        p.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("dave")));

        assertEquals(1, Queue.getInstance().getItems().length, "one item");
        ClusterQueue.Record now = STORE.clusterQueue().get(rec.id());
        assertThat(causes(now), containsString("carol"));
        assertThat(causes(now), containsString("dave"));
    }

    @Test
    void foldingElsewhereIntoAnItemFromHereUpdatesIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("ours");
        p.scheduleBuild2(60, new CauseAction(new Cause.UserIdCause("grace")));
        Queue.Item local = Queue.getInstance().getItems()[0];
        ClusterQueue.Record rec = STORE.clusterQueue().get(local.getId());
        // another controller folds a request into it
        List<Action> merged = List.of(new CauseAction(new Cause.UserIdCause("grace"), new Cause.UserIdCause("heidi")));
        other.update(rec.id(), SharedQueue.toXml(merged), rec.dueAt());
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
    void cancellingHereCancelsEverywhere() throws Exception {
        r.createFreeStyleProject("cancelled");
        ClusterQueue.Record rec = publishElsewhere("cancelled", 60_000, "frank");

        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl("queue/cancelItem?id=" + rec.id()), HttpMethod.POST));

        assertEquals(ClusterQueue.State.CANCELLED, STORE.clusterQueue().get(rec.id()).state());
        assertTrue(Queue.getInstance().isEmpty());
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
