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

package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Action;
import hudson.model.InvisibleAction;
import hudson.model.Queue;
import hudson.model.queue.FoldableAction;
import java.util.List;

/**
 * Stands in for pipeline-build-step's action of the same name (a {@code build} step waiting for its downstream build),
 * which the shared queue recognises by its class name.
 */
public class BuildTriggerAction extends InvisibleAction implements FoldableAction {
    private final String step;

    public BuildTriggerAction(String step) {
        this.step = step;
    }

    @Override
    public void foldIntoExisting(Queue.Item item, Queue.Task owner, List<Action> otherActions) {
        item.addAction(this);
    }
}
