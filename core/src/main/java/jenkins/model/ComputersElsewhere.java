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
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Node;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Tells how an agent that is not connected to this controller is doing on another controller it is connected to, such
 * as another controller sharing this one's configuration and work. Used only to display such an agent: as online, with
 * its executors as they are over there. The agent stays {@linkplain Computer#isOffline offline} here, so nothing is
 * ever scheduled on it by this controller.
 *
 * <p>Asked whenever such an agent is displayed, so implementations must answer from memory, without remote calls.
 * Without an implementation, agents are displayed as before.
 *
 * @see Computer#getConnectedElsewhere
 * @since TODO
 */
@Restricted(Beta.class)
public interface ComputersElsewhere extends ExtensionPoint {

    /**
     * Tells whether the agent of a computer, not connected here, is connected elsewhere.
     *
     * @param computer a computer not connected to this controller, nor marked temporarily offline
     * @return how it is doing where it is connected, or null if it is not known to be connected anywhere else
     */
    @CheckForNull
    Connection connectedElsewhere(@NonNull Computer computer);

    /**
     * Asks every implementation about a computer.
     *
     * @return the first answer, or null if the computer is connected here, marked temporarily offline, or no
     *     implementation knows it to be connected elsewhere
     */
    @CheckForNull
    static Connection of(@NonNull Computer computer) {
        Node node = computer.getNode();
        if (node == null || node.getTemporaryOfflineCause() != null || computer.isConnected()) {
            return null;
        }
        for (ComputersElsewhere e : ExtensionList.lookup(ComputersElsewhere.class)) {
            try {
                Connection c = e.connectedElsewhere(computer);
                if (c != null) {
                    return c;
                }
            } catch (RuntimeException x) {
                Logger.getLogger(ComputersElsewhere.class.getName())
                        .log(Level.WARNING, "Could not tell whether " + computer.getName() + " is connected elsewhere", x);
            }
        }
        return null;
    }

    /** An agent connected elsewhere: where, and what its executors are doing there. */
    final class Connection {
        private final String where;
        private final int executors;
        private final List<Busy> busy;

        /**
         * @param where the name of the controller the agent is connected to, for display
         * @param executors how many executors it has there
         * @param busy those of them that are busy
         */
        public Connection(@NonNull String where, int executors, @NonNull List<Busy> busy) {
            this.where = where;
            this.executors = executors;
            this.busy = List.copyOf(busy);
        }

        /** The name of the controller the agent is connected to. */
        @NonNull
        public String getWhere() {
            return where;
        }

        /** How many executors the agent has where it is connected. */
        public int getExecutors() {
            return executors;
        }

        /** Its busy executors. */
        @NonNull
        public List<Busy> getBusy() {
            return busy;
        }

        /** How many of its executors are busy. */
        public int countBusy() {
            return busy.size();
        }
    }

    /** A busy executor of an agent connected elsewhere, and the build it runs, if known. */
    final class Busy {
        private final String job;
        private final int build;
        private final long startedAt;
        private final long estimatedDuration;

        /**
         * @param job the {@linkplain Job#getFullName full name} of the job whose build it runs, or null if unknown
         * @param build the number of that build, or 0 if it has none yet
         * @param startedAt when it started (epoch milliseconds), or 0 if unknown
         * @param estimatedDuration how long it is expected to take (milliseconds), or -1 if unknown
         */
        public Busy(@CheckForNull String job, int build, long startedAt, long estimatedDuration) {
            this.job = job;
            this.build = build;
            this.startedAt = startedAt;
            this.estimatedDuration = estimatedDuration;
        }

        /** The job whose build it runs, if the current user may see it. */
        @CheckForNull
        public Job<?, ?> getJob() {
            return job == null ? null : Jenkins.get().getItemByFullName(job, Job.class);
        }

        /** The number of the build it runs, or 0. */
        public int getBuild() {
            return build;
        }

        /** How far the build got, in percent, or -1 if that cannot be told. */
        public int getProgress() {
            if (startedAt <= 0 || estimatedDuration <= 0) {
                return -1;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            return (int) Math.max(0, Math.min(99, elapsed * 100 / estimatedDuration));
        }
    }
}
