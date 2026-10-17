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
import hudson.model.ModelObject;
import hudson.model.Result;
import java.io.IOException;
import java.io.OutputStream;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * A read-only view of a build of a job held by another controller (see {@link ExternalBuilds}).
 * A Stapler object: core dispatches {@code job/<name>/<N>/} to it and includes its {@code historyEntry.jelly} view
 * (optional) in the build history widget; the implementation supplies its own views ({@code index}, {@code console}).
 * An implementation that is also a {@link hudson.model.Queue.Executable} (whose {@code run()} throws) may be the
 * start value of a handed-off queue item (the {@code started} call of a queue hand-off).
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface ExternalBuild extends ModelObject {

    int getNumber();

    long getStartTimeInMillis();

    long getEstimatedDuration();

    /** The display name of the controller holding the build. */
    @NonNull
    String getLocation();

    boolean isBuilding();

    /** The result, or null while building (or if unknown). */
    @CheckForNull
    Result getResult();

    /** The URL of the build, relative to the context path, under the job, as {@link hudson.model.Run#getUrl}. */
    @NonNull
    String getUrl();

    /**
     * Copies the build log from an offset. May block on remote I/O (not called under locks).
     *
     * @return the new offset; -1 once the build is complete and the log fully copied
     */
    long writeLogTo(long offset, @NonNull OutputStream out) throws IOException;

    /**
     * Asks the controller holding the build to stop it. The caller has checked {@link hudson.model.Item#CANCEL}.
     *
     * @param by the name of the user asking, for the build's log
     */
    void requestStop(@NonNull String by) throws IOException;
}
