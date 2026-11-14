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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Job;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Builds of a job that are not on this controller, such as builds running on another controller sharing the job.
 *
 * <p>Core consults it for {@link Job#isBuilding()}, the job's icon, URL dispatch of a build number
 * ({@code job/<name>/<N>/} when there is no such build here), the build history widget, and the {@code console} and
 * {@code build} CLI commands. Only the {@linkplain #get() first registered} implementation is consulted.
 *
 * <p>Methods are called under the {@link hudson.model.Queue} lock and once per job per page render, so they should
 * answer from a cache and refresh it asynchronously. A method whose answer decides something, such as
 * {@link #isBuilding} (which refuses a rename) or a lookup of one build, may read its source once on the caller's
 * thread while nothing is cached for the job, rather than answer that nothing is running elsewhere.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface ExternalBuilds extends ExtensionPoint {

    /** No builds elsewhere: what core uses when no implementation is registered (upstream behaviour). */
    ExternalBuilds NONE = job -> List.of();

    /**
     * Running builds of the job held by other controllers, newest first.
     */
    @NonNull
    List<? extends ExternalBuild> running(@NonNull Job<?, ?> job);

    /**
     * One of them by number, or null. May read its source once while nothing is cached for the job, as described above.
     */
    @CheckForNull
    default ExternalBuild get(@NonNull Job<?, ?> job, int number) {
        for (ExternalBuild b : running(job)) {
            if (b.getNumber() == number) {
                return b;
            }
        }
        return null;
    }

    /**
     * Whether the job is to be considered building because of builds elsewhere, including builds completed elsewhere
     * whose record is not yet published here.
     */
    default boolean isBuilding(@NonNull Job<?, ?> job) {
        return !running(job).isEmpty();
    }

    /** The first registered implementation, or {@link #NONE}. */
    @NonNull
    static ExternalBuilds get() {
        ExtensionList<ExternalBuilds> all = ExtensionList.lookup(ExternalBuilds.class);
        return all.isEmpty() ? NONE : all.get(0);
    }
}
