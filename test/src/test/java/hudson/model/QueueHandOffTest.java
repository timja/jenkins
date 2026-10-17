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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.cli.CLICommandInvoker;
import hudson.model.queue.QueueListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.SubTask;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import jenkins.model.ExternalBuild;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class QueueHandOffTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void takenElsewhereLeavesTheFuturePending() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild record = r.buildAndAssertSuccess(p);
        Listener listener = r.jenkins.getExtensionList(QueueListener.class).get(Listener.class);
        listener.left.clear();
        Queue q = r.jenkins.getQueue();
        Queue.Item item = q.schedule2(p, 1000).getItem();
        QueueTaskFuture<Queue.Executable> future = item.getFuture();

        Queue.HandOff handOff = q.handOff(item.getId());
        assertThat(handOff, not(nullValue()));
        assertThat(q.handOff(item.getId()), nullValue());
        assertTrue(q.isEmpty());
        Queue.Item left = q.getItem(item.getId());
        assertThat(left, instanceOf(Queue.LeftItem.class));
        Queue.LeftItem li = (Queue.LeftItem) left;
        assertTrue(li.isTakenElsewhere());
        assertFalse(li.isCancelled());
        assertThat(li.getExecutable(), nullValue());
        assertThat(listener.left, is(List.of(item.getId() + " taken elsewhere")));
        assertFalse(future.getStartCondition().isDone());
        assertFalse(future.isDone());
        // nothing here to cancel
        assertFalse(future.cancel(true));
        assertFalse(future.isCancelled());

        View view = new View(p, 7, "");
        handOff.started(view);
        assertThat(future.getStartCondition().get(5, TimeUnit.SECONDS), sameInstance(view));
        assertThat(li.getExecutable(), sameInstance(view));
        assertFalse(future.isDone());
        assertThrows(TimeoutException.class, () -> future.get(10, TimeUnit.MILLISECONDS));

        handOff.completed(record);
        assertThat(future.get(5, TimeUnit.SECONDS), sameInstance(record));
        assertThat(li.getExecutable(), sameInstance(record));
        // later calls are ignored
        handOff.cancelled();
        handOff.completed(view);
        assertThat(future.get(), sameInstance(record));
        assertFalse(future.isCancelled());

        // as for any left item, but no new field in the REST API
        JSONObject json = r.getJSON("queue/item/" + item.getId() + "/api/json").getJSONObject();
        assertFalse(json.getBoolean("cancelled"));
        assertFalse(json.has("takenElsewhere"));
    }

    @Test
    void completionAlsoCompletesTheStart() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild record = r.buildAndAssertSuccess(p);
        Queue q = r.jenkins.getQueue();
        Queue.Item item = q.schedule2(p, 1000).getItem();
        Queue.HandOff handOff = q.handOff(item.getId());
        handOff.completed(record);
        assertThat(item.getFuture().getStartCondition().get(5, TimeUnit.SECONDS), sameInstance(record));
        assertThat(item.getFuture().get(5, TimeUnit.SECONDS), sameInstance(record));
    }

    @Test
    void cancelledElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Queue q = r.jenkins.getQueue();
        Queue.Item item = q.schedule2(p, 1000).getItem();
        Queue.HandOff handOff = q.handOff(item.getId());
        handOff.cancelled();
        assertTrue(item.getFuture().isCancelled());
        assertThrows(CancellationException.class, () -> item.getFuture().getStartCondition().get());
        assertTrue(((Queue.LeftItem) q.getItem(item.getId())).isTakenElsewhere());
        // too late to start
        handOff.started(new View(p, 3, ""));
        assertTrue(item.getFuture().isCancelled());
    }

    @Test
    void onlyQueuedItems() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Queue q = r.jenkins.getQueue();
        assertThat(q.handOff(12345), nullValue());
        Queue.Item item = q.schedule2(p, 1000).getItem();
        assertTrue(q.cancel(item));
        assertThat(q.handOff(item.getId()), nullValue());
        Queue.LeftItem li = (Queue.LeftItem) q.getItem(item.getId());
        assertTrue(li.isCancelled());
        assertFalse(li.isTakenElsewhere());
        Listener listener = r.jenkins.getExtensionList(QueueListener.class).get(Listener.class);
        assertThat(listener.left, is(List.of(item.getId() + " cancelled")));
    }

    @Test
    void handedOffFutureDoesNotCancelAnotherItemOfTheTask() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setConcurrentBuild(true);
        Queue q = r.jenkins.getQueue();
        Queue.Item first = q.schedule2(p, 1000).getItem();
        q.handOff(first.getId());
        Queue.Item second = q.schedule2(p, 1000).getItem();
        assertThat(second, not(nullValue()));
        assertFalse(first.getFuture().cancel(true));
        assertThat(q.getItem(second.getId()), instanceOf(Queue.WaitingItem.class));
    }

    @Test
    void scheduleSharedNeverFolds() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Queue q = r.jenkins.getQueue();
        Queue.Item local = q.schedule2(p, 1000).getItem();
        Queue.WaitingItem shared = q.scheduleShared(p, 1000, List.of(new CauseAction(new Cause.UserIdCause("alice"))));
        assertThat(shared.getId(), not(is(local.getId())));
        assertEquals(2, q.getItems().length);
        assertThat(shared.getAction(CauseAction.class).getCauses().get(0), instanceOf(Cause.UserIdCause.class));
    }

    @Test
    void updateSharedReplacesActionsAndShortensTheQuietPeriod() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Queue q = r.jenkins.getQueue();
        Queue.WaitingItem shared = q.scheduleShared(p, 1000, List.of(
                new ParametersAction(new StringParameterValue("A", "1")), new ParametersAction(new StringParameterValue("B", "2"))));
        assertFalse(q.updateShared(12345, new GregorianCalendar(), List.of()));

        Calendar later = new GregorianCalendar();
        later.add(Calendar.SECOND, 5000);
        assertTrue(q.updateShared(shared.getId(), later, List.of(new CauseAction(new Cause.UserIdCause("bob")))));
        Queue.Item item = q.getItem(shared.getId());
        assertThat(item, instanceOf(Queue.WaitingItem.class));
        assertTrue(item.getActions(ParametersAction.class).isEmpty());
        assertThat(item.getAction(CauseAction.class), not(nullValue()));
        // a later due time is not taken
        assertTrue(((Queue.WaitingItem) item).timestamp.before(later));

        List<Action> two = new ArrayList<>(List.of(
                new ParametersAction(new StringParameterValue("A", "1")), new ParametersAction(new StringParameterValue("B", "2"))));
        assertTrue(q.updateShared(shared.getId(), new GregorianCalendar(), two));
        FreeStyleBuild b = (FreeStyleBuild) shared.getFuture().get(60, TimeUnit.SECONDS);
        assertEquals(2, b.getActions(ParametersAction.class).size());
    }

    @Test
    void buildCommandFollowsABuildStartedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setAssignedLabel(Label.get("nowhere")); // so it stays in the queue here
        Queue q = r.jenkins.getQueue();
        CompletableFuture<CLICommandInvoker.Result> cli =
                CompletableFuture.supplyAsync(() -> new CLICommandInvoker(r, "build").invokeWithArgs("p", "-s", "-v"));
        await(() -> q.getItems().length == 1);
        Queue.HandOff handOff = q.handOff(q.getItems()[0].getId());
        View view = new View(p, 7, "remote line 1\n");
        handOff.started(view);
        await(() -> view.reads > 1);
        view.append("remote line 2\n");
        view.finish(Result.UNSTABLE);
        handOff.completed(view);
        CLICommandInvoker.Result result = cli.get(60, TimeUnit.SECONDS);
        assertThat(result.stdout(), containsString("Started p #7 on other"));
        assertThat(result.stdout(), containsString("remote line 1\nremote line 2\n"));
        assertThat(result.stdout(), containsString("Completed p #7 : UNSTABLE"));
        assertEquals(Result.UNSTABLE.ordinal, result.returnCode());
    }

    @Test
    void buildCommandWaitsForTheRecordOfABuildStartedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild record = r.buildAndAssertSuccess(p);
        p.setAssignedLabel(Label.get("nowhere"));
        Queue q = r.jenkins.getQueue();
        CompletableFuture<CLICommandInvoker.Result> cli =
                CompletableFuture.supplyAsync(() -> new CLICommandInvoker(r, "build").invokeWithArgs("p", "-s"));
        await(() -> q.getItems().length == 1);
        Queue.HandOff handOff = q.handOff(q.getItems()[0].getId());
        handOff.started(new View(p, 1, ""));
        handOff.completed(record);
        CLICommandInvoker.Result result = cli.get(60, TimeUnit.SECONDS);
        assertThat(result.stdout(), containsString("Completed p #1 : SUCCESS"));
        assertEquals(0, result.returnCode());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    @TestExtension({"takenElsewhereLeavesTheFuturePending", "onlyQueuedItems"})
    public static final class Listener extends QueueListener {
        final List<String> left = new CopyOnWriteArrayList<>();

        @Override
        public void onLeft(Queue.LeftItem li) {
            left.add(li.getId() + (li.isTakenElsewhere() ? " taken elsewhere" : li.isCancelled() ? " cancelled" : " started"));
        }
    }

    /** A read-only view of a build on another controller. */
    public static final class View implements ExternalBuild, Queue.Executable {
        private final Job<?, ?> job;
        private final int number;
        private final StringBuilder log = new StringBuilder();
        private volatile Result result;
        volatile int reads;

        View(Job<?, ?> job, int number, String log) {
            this.job = job;
            this.number = number;
            this.log.append(log);
        }

        synchronized void append(String s) {
            log.append(s);
        }

        void finish(Result r) {
            result = r;
        }

        @Override
        public int getNumber() {
            return number;
        }

        @Override
        public long getStartTimeInMillis() {
            return 0;
        }

        @Override
        public long getEstimatedDuration() {
            return -1;
        }

        @NonNull
        @Override
        public String getLocation() {
            return "other";
        }

        @Override
        public boolean isBuilding() {
            return result == null;
        }

        @CheckForNull
        @Override
        public Result getResult() {
            return result;
        }

        @NonNull
        @Override
        public String getUrl() {
            return job.getUrl() + number + "/";
        }

        @Override
        public long writeLogTo(long offset, @NonNull OutputStream out) throws IOException {
            reads++;
            boolean done = result != null;
            byte[] bytes;
            synchronized (this) {
                bytes = log.toString().getBytes(StandardCharsets.UTF_8);
            }
            if (offset < bytes.length) {
                out.write(bytes, (int) offset, bytes.length - (int) offset);
            }
            return done ? -1 : bytes.length;
        }

        @Override
        public void requestStop(@NonNull String by) {
            result = Result.ABORTED;
        }

        @Override
        public String getDisplayName() {
            return "#" + number;
        }

        @NonNull
        @Override
        public SubTask getParent() {
            return (SubTask) job;
        }

        @Override
        public void run() {
            throw new UnsupportedOperationException("not run here");
        }

        @Override
        public String toString() {
            return job.getFullDisplayName() + " #" + number;
        }
    }
}
