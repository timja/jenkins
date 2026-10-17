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

package hudson.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.init.Initializer;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.queue.QueueStartGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Hooks in {@link Queue} for extensions that coordinate it: a start gate, folding events, the loaded milestone, whether
 * the queue lock is held.
 */
@WithJenkins
class QueueHooksTest {

    private static final CauseOfBlockage HELD = new CauseOfBlockage() {
        @Override
        public String getShortDescription() {
            return "held by the gate";
        }
    };

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @AfterEach
    void tearDown() {
        Gate.hold = false;
        Gate.provision = true;
    }

    @Test
    void theGateHoldsABuildableItemUntilItLetsItStart() throws Exception {
        Gate.hold = true;
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.scheduleBuild2(0);
        Queue q = r.jenkins.getQueue();
        q.maintain();
        Queue.Item item = q.getItem(p);
        assertNotNull(item);
        assertTrue(item.isBuildable(), "it waits as a buildable item");
        assertThat(item.getCauseOfBlockage().getShortDescription(), containsString("held by the gate"));
        assertNull(p.getLastBuild());
        Gate.hold = false;
        q.maintain();
        r.waitUntilNoActivity();
        assertNotNull(p.getLastBuild());
    }

    @Test
    void theGateHoldsAFlyweightTask() throws Exception {
        Gate.hold = true;
        AtomicInteger cnt = new AtomicInteger();
        QueueTest.TestFlyweightTask task = new QueueTest.TestFlyweightTask(cnt, null);
        Queue q = r.jenkins.getQueue();
        q.schedule2(task, 0);
        q.maintain();
        q.maintain(); // waiting -> buildable, then buildable -> run
        Queue.Item item = q.getItem(task);
        assertNotNull(item);
        assertTrue(item.isBuildable());
        assertThat(item.getCauseOfBlockage().getShortDescription(), containsString("held by the gate"));
        assertEquals(0, cnt.get());
        Gate.hold = false;
        q.maintain();
        r.waitUntilNoActivity();
        assertEquals(1, cnt.get());
    }

    @Test
    void anItemTheGateLeavesToOthersIsNotProvisionedFor() throws Exception {
        Label cloudy = Label.get("cloudy"); // no node has it: the item waits for one to be provisioned
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setAssignedLabel(cloudy);
        p.scheduleBuild2(0);
        Queue q = r.jenkins.getQueue();
        q.maintain();
        assertEquals(1, cloudy.loadStatistics.computeSnapshot(q.getBuildableItems()).getQueueLength());
        Gate.provision = false;
        assertEquals(0, cloudy.loadStatistics.computeSnapshot(q.getBuildableItems()).getQueueLength());
        q.cancel(q.getItem(p));
    }

    @TestExtension
    public static class Gate implements QueueStartGate {
        static volatile boolean hold;
        static volatile boolean provision = true;

        @Override
        public CauseOfBlockage mayStart(Queue.BuildableItem item) {
            return hold ? HELD : null;
        }

        @Override
        public boolean countsForProvisioning(Queue.BuildableItem item) {
            return provision;
        }
    }

    @Test
    void listenersAreToldOfRequestsFoldedIntoAnItem() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.scheduleBuild2(1000);
        Cause.UserIdCause cause = new Cause.UserIdCause("alice");
        CauseAction request = new CauseAction(cause);
        p.scheduleBuild2(1000, request);
        Queue.Item item = r.jenkins.getQueue().getItem(p);
        assertEquals(1, Folds.seen.size());
        assertSame(item, Folds.seen.get(0).item);
        assertTrue(Folds.seen.get(0).request.contains(request));
        r.jenkins.getQueue().cancel(item);
    }

    record Fold(Queue.Item item, List<Action> request) {}

    @TestExtension("listenersAreToldOfRequestsFoldedIntoAnItem")
    public static class Folds extends QueueListener {
        static final List<Fold> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onFolded(Queue.Item item, List<Action> request) {
            seen.add(new Fold(item, List.copyOf(request)));
        }
    }

    @Test
    void loadingTheQueueIsAMilestone() throws Exception {
        Initializer init = Queue.class.getMethod("init", jenkins.model.Jenkins.class).getAnnotation(Initializer.class);
        assertEquals(List.of(Queue.LOADED), List.of(init.attains()), "initializers may require it");
    }

    @Test
    void tellsWhetherTheCurrentThreadHoldsTheQueueLock() throws Exception {
        Queue q = r.jenkins.getQueue();
        assertFalse(q.isLockHeldByCurrentThread());
        Queue.withLock(() -> {
            assertTrue(q.isLockHeldByCurrentThread());
            // only the holder: another thread asking meanwhile does not hold it
            boolean elsewhere = CompletableFuture.supplyAsync(q::isLockHeldByCurrentThread)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
            assertFalse(elsewhere);
        });
        assertFalse(q.isLockHeldByCurrentThread());
    }
}
