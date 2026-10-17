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

package jenkins.model;

import static hudson.cli.CLICommandInvoker.Matcher.failedWith;
import static hudson.cli.CLICommandInvoker.Matcher.succeeded;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.cli.CLICommandInvoker;
import hudson.model.BallColor;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.htmlunit.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class ExternalBuildsTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void none() throws Exception {
        assertThat(ExternalBuilds.get(), sameInstance(ExternalBuilds.NONE));
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        assertFalse(p.isBuilding());
        assertThat(p.getIconColor(), is(BallColor.BLUE));
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        assertEquals(404, wc.goTo("job/p/5/", null).getWebResponse().getStatusCode());
        assertThat(history(wc), not(containsString("fake-external-build")));
        assertThat(new CLICommandInvoker(r, "console").invokeWithArgs("p", "5"), failedWith(3));
    }

    @Test
    void jobShowsBuildsElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        Fake fake = ExtensionList.lookupSingleton(Fake.class);
        assertThat(ExternalBuilds.get(), sameInstance(fake));
        assertFalse(p.isBuilding());
        fake.builds.add(new FakeBuild(5, "first line\nsecond line\n"));

        assertTrue(p.isBuilding());
        assertThat(p.getIconColor(), is(BallColor.BLUE_ANIME));

        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.goTo("job/p/5/", null);
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertThat(page.getWebResponse().getContentAsString(), containsString("build #5 elsewhere"));
        assertEquals(404, wc.goTo("job/p/6/", null).getWebResponse().getStatusCode());
        assertEquals(200, wc.goTo("job/p/1/", null).getWebResponse().getStatusCode());

        assertThat(history(wc), containsString("fake-external-build #5 on other"));
    }

    @Test
    void consoleOfBuildElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        Fake fake = ExtensionList.lookupSingleton(Fake.class);
        FakeBuild b5 = new FakeBuild(5, "first line\nsecond line\n");
        fake.builds.add(b5);

        CLICommandInvoker.Result result = new CLICommandInvoker(r, "console").invokeWithArgs("p", "5");
        assertThat(result, succeeded());
        assertThat(result.stdout(), equalTo("first line\nsecond line\n"));

        result = new CLICommandInvoker(r, "console").invokeWithArgs("p", "5", "-n", "1");
        assertThat(result, succeeded());
        assertThat(result.stdout(), equalTo("second line\n"));

        // the last build is the newest, wherever it is
        result = new CLICommandInvoker(r, "console").invokeWithArgs("p");
        assertThat(result, succeeded());
        assertThat(result.stdout(), equalTo("first line\nsecond line\n"));

        // a build here that is newer
        fake.builds.clear();
        fake.builds.add(new FakeBuild(0, "older\n"));
        result = new CLICommandInvoker(r, "console").invokeWithArgs("p");
        assertThat(result, succeeded());
        assertThat(result.stdout(), not(containsString("older")));
    }

    @Test
    void consoleFollowsBuildElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Fake fake = ExtensionList.lookupSingleton(Fake.class);
        FakeBuild b5 = new FakeBuild(5, "first line\n");
        fake.builds.add(b5);

        CompletableFuture<CLICommandInvoker.Result> following =
                CompletableFuture.supplyAsync(() -> new CLICommandInvoker(r, "console").invokeWithArgs("p", "5", "-f"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (b5.reads.get() < 2) {
            assertTrue(System.nanoTime() < deadline, "console -f polls");
            Thread.sleep(10);
        }
        assertFalse(following.isDone());
        b5.append("second line\n");
        b5.complete();
        CLICommandInvoker.Result result = following.get(30, TimeUnit.SECONDS);
        assertThat(result, succeeded());
        assertThat(result.stdout(), equalTo("first line\nsecond line\n"));
    }

    private static String history(JenkinsRule.WebClient wc) throws Exception {
        return wc.goTo("job/p/buildHistory/ajax", null).getWebResponse().getContentAsString();
    }

    @TestExtension({"jobShowsBuildsElsewhere", "consoleOfBuildElsewhere", "consoleFollowsBuildElsewhere"})
    public static final class Fake implements ExternalBuilds {
        final List<FakeBuild> builds = new CopyOnWriteArrayList<>();

        @NonNull
        @Override
        public List<? extends ExternalBuild> running(@NonNull Job<?, ?> job) {
            return job.getFullName().equals("p") ? builds : List.of();
        }
    }

    public static final class FakeBuild implements ExternalBuild {
        private final int number;
        private final StringBuilder log = new StringBuilder();
        private volatile boolean done;
        final AtomicInteger reads = new AtomicInteger();

        FakeBuild(int number, String log) {
            this.number = number;
            this.log.append(log);
        }

        synchronized void append(String s) {
            log.append(s);
        }

        void complete() {
            done = true;
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
            return !done;
        }

        @CheckForNull
        @Override
        public Result getResult() {
            return done ? Result.SUCCESS : null;
        }

        @NonNull
        @Override
        public String getUrl() {
            return "job/p/" + number + "/";
        }

        @Override
        public long writeLogTo(long offset, @NonNull OutputStream out) throws IOException {
            reads.incrementAndGet();
            boolean complete = done;
            byte[] bytes;
            synchronized (this) {
                bytes = log.toString().getBytes(StandardCharsets.UTF_8);
            }
            if (offset < bytes.length) {
                out.write(bytes, (int) offset, bytes.length - (int) offset);
            }
            return complete ? -1 : bytes.length;
        }

        @Override
        public void requestStop(@NonNull String by) {
            done = true;
        }

        @Override
        public String getDisplayName() {
            return "#" + number;
        }

        public void doIndex(StaplerResponse2 rsp) throws IOException {
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getWriter().print("build #" + number + " elsewhere");
        }
    }
}
