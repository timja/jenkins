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

package jenkins.triggers;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.triggers.Trigger;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Decides whether this controller runs a cron-style {@link Trigger} at a given tick.
 * When several controllers share the same items, an implementation can make each tick of each trigger
 * run on exactly one of them.
 *
 * <p>Consulted by {@link Trigger#checkTriggers} for every trigger whose schedule matches, just before running it.
 * Without implementations every trigger runs, as on a single controller.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface TriggerCoordinator extends ExtensionPoint {

    /**
     * @param item the item owning the trigger
     * @param trigger a trigger whose schedule matches {@code tick}
     * @param tick the minute being processed
     * @return whether this controller should run the trigger for this tick
     */
    boolean shouldRun(@NonNull TriggeredItem item, @NonNull Trigger<?> trigger, @NonNull Calendar tick);

    /**
     * Whether every registered coordinator allows running the trigger here.
     * A coordinator that fails is treated as refusing: skipping a tick is safer than running it twice.
     */
    static boolean allows(@NonNull TriggeredItem item, @NonNull Trigger<?> trigger, @NonNull Calendar tick) {
        for (TriggerCoordinator c : ExtensionList.lookup(TriggerCoordinator.class)) {
            try {
                if (!c.shouldRun(item, trigger, tick)) {
                    return false;
                }
            } catch (RuntimeException e) {
                Logger.getLogger(TriggerCoordinator.class.getName()).log(Level.WARNING, c + " failed; skipping " + trigger + " of " + item, e);
                return false;
            }
        }
        return true;
    }
}
