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

package jenkins.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.PeriodicWork;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Decides which controller runs each period of {@link ClusterSingleton} periodic work.
 * With no coordinator, such work runs on every controller, as it always has.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface PeriodicWorkCoordinator extends ExtensionPoint {

    /**
     * @param work cluster-singleton work due to run
     * @param period the period being run: the current time divided by its recurrence period
     * @return whether this controller should run it for this period
     */
    boolean shouldRun(@NonNull PeriodicWork work, long period);

    /**
     * Whether periodic work should run here now.
     * Work which is not {@link ClusterSingleton} always may; a coordinator that fails counts as refusing.
     */
    static boolean allows(@NonNull PeriodicWork work) {
        if (!work.getClass().isAnnotationPresent(ClusterSingleton.class)) {
            return true;
        }
        long period = System.currentTimeMillis() / Math.max(1, work.getRecurrencePeriod());
        for (PeriodicWorkCoordinator c : ExtensionList.lookup(PeriodicWorkCoordinator.class)) {
            try {
                if (!c.shouldRun(work, period)) {
                    Logger.getLogger(PeriodicWorkCoordinator.class.getName()).fine(() -> work + " runs elsewhere for period " + period);
                    return false;
                }
            } catch (RuntimeException e) {
                Logger.getLogger(PeriodicWorkCoordinator.class.getName()).log(Level.WARNING, c + " failed; skipping " + work, e);
                return false;
            }
        }
        return true;
    }
}
