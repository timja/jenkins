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

package jenkins.model.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Has the last word on whether a buildable queue item may start on this controller,
 * consulted right before it is handed to an executor (including flyweight tasks, which bypass the {@link hudson.model.LoadBalancer}).
 * For example several controllers sharing a queue claim an item here, so that it starts on exactly one of them.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface QueueStartGate extends ExtensionPoint {

    /**
     * @return null to let the item start now, or why it may not (the item then stays in the queue)
     */
    @CheckForNull
    CauseOfBlockage mayStart(@NonNull Queue.BuildableItem item);

    /**
     * Consults every gate.
     */
    @CheckForNull
    static CauseOfBlockage check(@NonNull Queue.BuildableItem item) {
        for (QueueStartGate gate : ExtensionList.lookup(QueueStartGate.class)) {
            CauseOfBlockage cause = gate.mayStart(item);
            if (cause != null) {
                return cause;
            }
        }
        return null;
    }
}
