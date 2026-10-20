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

package jenkins.model.lazy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import jenkins.util.ThrowingCallable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Build records that exist outside this controller's disk, such as builds of a job made by another controller
 * sharing it. Consulted by {@link hudson.model.RunMap}: when it indexes the builds of a job
 * ({@link #numbers}), when it loads one ({@link #retrieve}), and when a new build is added ({@link #sharesNumbers}).
 * Only the {@linkplain #get() first registered} source is consulted.
 * With none, builds are only those on disk, as upstream.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface BuildRecordSource extends ExtensionPoint {

    /**
     * Numbers of builds of the job stored elsewhere, added to the lazy index; they may have no local directory yet.
     * Called when the job's build index is loaded; must be quick (no more than one round trip).
     */
    @NonNull
    Set<Integer> numbers(@NonNull Job<?, ?> job);

    /**
     * Loads a build directory, which is always under {@link Job#getBuildDir()} (core checks): may first create it from
     * the stored record, and wraps the load (for example so that saves made while loading are not taken for changes).
     *
     * @param dir the build directory, which may not exist yet
     * @param load loads the build from {@code dir} (and calls {@code onLoad})
     * @return what {@code load} returned
     */
    <R extends Run<?, ?>> R retrieve(@NonNull Job<?, ?> job, @NonNull File dir, @NonNull ThrowingCallable<R, IOException> load)
            throws IOException;

    /**
     * Whether build numbers of this job are assigned from a sequence shared with others: a new build then need not
     * have a number above every known one, only an unused one.
     */
    boolean sharesNumbers(@NonNull Job<?, ?> job);

    /** The first registered source, or null (no source: upstream behaviour). */
    @CheckForNull
    static BuildRecordSource get() {
        ExtensionList<BuildRecordSource> all = ExtensionList.lookup(BuildRecordSource.class);
        return all.isEmpty() ? null : all.get(0);
    }
}
