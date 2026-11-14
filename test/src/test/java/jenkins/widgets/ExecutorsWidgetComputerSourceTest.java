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

package jenkins.widgets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.ComputerSet;
import hudson.model.FreeStyleProject;
import hudson.model.ListView;
import hudson.model.Queue;
import hudson.model.queue.SubTask;
import hudson.model.queue.WorkUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import jenkins.agents.IOfflineCause;
import jenkins.model.IComputer;
import jenkins.model.IDisplayExecutor;
import jenkins.model.IExecutor;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The executors widget of a view shows the computers of {@link ComputerSet.ComputerSource}, as the computer list does.
 */
@WithJenkins
class ExecutorsWidgetComputerSourceTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void viewShowsComputersOfTheSource() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Source source = ExtensionList.lookupSingleton(Source.class);
        source.remote = new Remote(p, 7);
        JenkinsRule.WebClient wc = r.createWebClient();

        String dashboard = wc.goTo("").getWebResponse().getContentAsString();
        assertThat(dashboard, containsString("computer/remote/"));
        assertThat(dashboard, containsString("1/2"));
        assertThat(dashboard, containsString("job/p/7/"));

        assertThat(wc.goTo("computer/").getWebResponse().getContentAsString(), containsString("computer/remote/"));
    }

    @Test
    void viewFilteringExecutorsShowsOnlyRelevantComputers() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setAssignedNode(r.jenkins);
        Source source = ExtensionList.lookupSingleton(Source.class);
        source.remote = new Remote(p, 7);
        ListView view = new ListView("filtered", r.jenkins);
        view.add(p);
        view.setFilterExecutors(true);
        r.jenkins.addView(view);
        r.jenkins.setNumExecutors(1);

        String page = r.createWebClient().goTo("view/filtered/").getWebResponse().getContentAsString();
        assertThat(page, not(containsString("computer/remote/")));
        assertThat(r.createWebClient().goTo("").getWebResponse().getContentAsString(), containsString("computer/remote/"));
    }

    @TestExtension
    public static final class Source implements ComputerSet.ComputerSource {
        volatile Remote remote;

        @Override
        public Collection<? extends IComputer> get() {
            List<IComputer> all = new ArrayList<>(Jenkins.get().getComputersCollection());
            if (remote != null) {
                all.add(remote);
            }
            return all;
        }
    }

    /** A computer that is not one of this controller's, online, with two executors, one of them running a build. */
    static final class Remote implements IComputer {
        private final List<IDisplayExecutor> executors;

        Remote(FreeStyleProject p, int build) {
            executors = List.of(new Display(0, new Running(p, build)), new Display(1, null));
        }

        @NonNull
        @Override
        public String getName() {
            return "remote";
        }

        @Override
        public String getDisplayName() {
            return "remote";
        }

        @NonNull
        @Override
        public List<? extends IDisplayExecutor> getDisplayExecutors() {
            return executors;
        }

        @Override
        public boolean isOffline() {
            return false;
        }

        @Override
        public boolean isAcceptingTasks() {
            return true;
        }

        @NonNull
        @Override
        public String getUrl() {
            return "computer/remote/";
        }

        @Override
        public IOfflineCause getOfflineCause() {
            return null;
        }

        @Override
        public boolean isConnecting() {
            return false;
        }

        @Override
        public int countBusy() {
            return 1;
        }

        @Override
        public int countExecutors() {
            return 2;
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public int countIdle() {
            return 1;
        }

        @Override
        public boolean isLaunchSupported() {
            return false;
        }

        @Override
        public Future<?> connect(boolean forceReconnect) {
            throw new UnsupportedOperationException();
        }

        private final class Display implements IDisplayExecutor {
            private final IExecutor executor;

            Display(int number, @CheckForNull Running running) {
                executor = new Executor(number, running);
            }

            @Override
            public String getDisplayName() {
                return "#" + executor.getNumber();
            }

            @NonNull
            @Override
            public String getUrl() {
                return "executors/" + executor.getNumber();
            }

            @NonNull
            @Override
            public IExecutor getExecutor() {
                return executor;
            }
        }

        private final class Executor implements IExecutor {
            private final int number;
            private final Running running;

            Executor(int number, @CheckForNull Running running) {
                this.number = number;
                this.running = running;
            }

            @Override
            public boolean isIdle() {
                return running == null;
            }

            @Override
            public IComputer getOwner() {
                return Remote.this;
            }

            @CheckForNull
            @Override
            public Queue.Executable getCurrentExecutable() {
                return running;
            }

            @CheckForNull
            @Override
            public WorkUnit getCurrentWorkUnit() {
                return null;
            }

            @Override
            public String getDisplayName() {
                return "Executor #" + number;
            }

            @Override
            public boolean hasStopPermission() {
                return false;
            }

            @Override
            public int getNumber() {
                return number;
            }

            @Override
            public long getElapsedTime() {
                return 0;
            }

            @Override
            public String getEstimatedRemainingTime() {
                return "N/A";
            }

            @Override
            public boolean isLikelyStuck() {
                return false;
            }

            @Override
            public int getProgress() {
                return -1;
            }
        }
    }

    /** A build running on the remote computer, of a job also here. */
    public static final class Running implements Queue.Executable {
        private final FreeStyleProject parent;
        private final int number;

        Running(FreeStyleProject parent, int number) {
            this.parent = parent;
            this.number = number;
        }

        @NonNull
        @Override
        public SubTask getParent() {
            return parent;
        }

        @Override
        public void run() {
            throw new UnsupportedOperationException();
        }

        public String getUrl() {
            return parent.getUrl() + number + "/";
        }

        public String getDisplayName() {
            return "#" + number;
        }
    }
}
