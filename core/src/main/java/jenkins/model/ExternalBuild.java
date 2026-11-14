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
import hudson.Util;
import hudson.model.BallColor;
import hudson.model.BuildBadgeAction;
import hudson.model.ParameterValue;
import hudson.model.Result;
import hudson.model.Run;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.List;
import jenkins.console.WithConsoleUrl;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * A read-only view of a build of a job held by another controller (see {@link ExternalBuilds}).
 * A Stapler object: core dispatches {@code job/<name>/<N>/} to it, and the build history widget lists it among the
 * job's own builds, as any {@link HistoricalBuild}; the implementation supplies its own views ({@code index},
 * {@code console}) and actions (such as {@code stop}, which the widget links to while it is building).
 * An implementation that is also a {@link hudson.model.Queue.Executable} (whose {@code run()} throws) may be the
 * start value of a handed-off queue item (the {@code started} call of a queue hand-off).
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface ExternalBuild extends HistoricalBuild, WithConsoleUrl {

    @Override
    int getNumber();

    long getStartTimeInMillis();

    long getEstimatedDuration();

    /** The display name of the controller holding the build. */
    @NonNull
    String getLocation();

    @Override
    boolean isBuilding();

    /** The result, or null while building (or if unknown). */
    @Override
    @CheckForNull
    Result getResult();

    /** The URL of the build, relative to the context path, under the job, as {@link hudson.model.Run#getUrl}. */
    @Override
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

    /** When the build started, as {@link #getStartTimeInMillis}. */
    @Override
    @NonNull
    default Calendar getTimestamp() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(getStartTimeInMillis());
        return c;
    }

    /** As {@link Run#getIconColor}, from this build's result alone: animated while building. */
    @Override
    @NonNull
    default BallColor getIconColor() {
        Result result = getResult();
        BallColor color = result == null ? BallColor.NOTBUILT : result.color;
        return isBuilding() ? color.anime() : color;
    }

    /** As {@link Run#getDurationString}. */
    @Override
    @NonNull
    default String getDurationString() {
        long elapsed = Math.max(0, System.currentTimeMillis() - getStartTimeInMillis());
        return isBuilding() ? hudson.model.Messages.Run_InProgressDuration(Util.getTimeSpanString(elapsed)) : Util.getTimeSpanString(elapsed);
    }

    /** None by default. */
    @Override
    @CheckForNull
    default String getDescription() {
        return null;
    }

    /** {@link Run#QUEUE_ID_UNKNOWN} by default. */
    @Override
    default long getQueueId() {
        return Run.QUEUE_ID_UNKNOWN;
    }

    /** None by default. */
    @Override
    @NonNull
    default List<ParameterValue> getParameterValues() {
        return List.of();
    }

    /** None by default. */
    @Override
    @NonNull
    default List<BuildBadgeAction> getBadgeActions() {
        return List.of();
    }

    /** The implementation's {@code console} view, as for a {@link Run}. */
    @Override
    @CheckForNull
    default String getConsoleUrl() {
        return getUrl() + "console";
    }
}
