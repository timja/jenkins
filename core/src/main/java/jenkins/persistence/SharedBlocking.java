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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.List;
import java.util.Locale;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Extends "block build when upstream/downstream project is building" to builds running on other controllers
 * ({@linkplain RunningBuilds placeholders}); builds queued anywhere are already in this controller's queue (see {@link SharedQueue}).
 */
@Restricted(NoExternalUse.class)
@Extension
public final class SharedBlocking extends QueueTaskDispatcher {

    @CheckForNull
    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        if (!(item.task instanceof AbstractProject<?, ?> p) || PersistenceStores.get() == null) {
            return null;
        }
        if (p.blockBuildWhenUpstreamBuilding()) {
            CauseOfBlockage cause = runningElsewhere(p, p.getTransitiveUpstreamProjects(), "Upstream");
            if (cause != null) {
                return cause;
            }
        }
        if (p.blockBuildWhenDownstreamBuilding()) {
            return runningElsewhere(p, p.getTransitiveDownstreamProjects(), "Downstream");
        }
        return null;
    }

    @CheckForNull
    @SuppressWarnings("rawtypes")
    private static CauseOfBlockage runningElsewhere(AbstractProject<?, ?> queued, Iterable<AbstractProject> jobs, String which) {
        for (Job<?, ?> job : (Iterable<Job<?, ?>>) (Iterable) jobs) {
            if (job == queued) {
                continue; // in a cycle: as here, its own builds do not block it (one at a time is the exclusive lease's job)
            }
            // under the queue lock: from what is cached only (a slow store would hold up the whole queue)
            List<RunningBuilds.Placeholder> running = RunningBuilds.elsewhereCached(job);
            if (running == null) {
                return new SharedQueue.Blocked("Checking whether " + which.toLowerCase(Locale.ENGLISH) + " project " + job.getFullDisplayName()
                        + " is building on another controller");
            }
            if (!running.isEmpty()) {
                RunningBuilds.Placeholder b = running.get(0);
                return new SharedQueue.Blocked(which + " project " + job.getFullDisplayName() + " is building on " + b.getController()
                        + " (" + b.getDisplayName() + ")");
            }
        }
        return null;
    }
}
