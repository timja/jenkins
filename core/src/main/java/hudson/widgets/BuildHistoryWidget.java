/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

package hudson.widgets;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import jenkins.model.ExternalBuild;
import jenkins.model.ExternalBuilds;
import jenkins.model.HistoricalBuild;
import jenkins.model.Jenkins;
import jenkins.model.queue.QueueItem;
import jenkins.widgets.HistoryPageFilter;
import jenkins.widgets.WidgetFactory;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

/**
 * Displays the build history on the side panel.
 *
 * <p>
 * This widget enhances {@link HistoryWidget} by groking the notion
 * that {@link #owner} can be in the queue toward the next build.
 * @param <T> typically {@link HistoricalBuild}
 * @author Kohsuke Kawaguchi
 */
public class BuildHistoryWidget<T> extends HistoryWidget<Task, T> {
    /**
     * @param owner
     *      The parent model object that owns this widget.
     */
    public BuildHistoryWidget(Task owner, Iterable<T> baseList, Adapter<? super T> adapter) {
        super(owner, baseList, adapter);
    }

    /**
     * Returns the first queue item if the owner is scheduled for execution in the queue.
     */
    public QueueItem getQueuedItem() {
        return Jenkins.get().getQueue().getItem(owner);
    }

    /**
     * Returns the queue item if the owner is scheduled for execution in the queue, in REVERSE ORDER
     */
    public List<QueueItem> getQueuedItems() {
        LinkedList<QueueItem> list = new LinkedList<>();
        for (QueueItem item : Jenkins.get().getQueue().getItems()) {
            if (item.getTask() == owner) {
                list.addFirst(item);
            }
        }
        return list;
    }

    @Override
    public HistoryPageFilter getHistoryPageFilter() {
        final HistoryPageFilter<T> historyPageFilter = newPageFilter();

        historyPageFilter.add(baseList, getQueuedItems());

        return updateFirstTransientBuildKey(historyPageFilter);
    }

    @Extension
    @Restricted(DoNotUse.class)
    @Symbol("buildHistory")
    public static final class FactoryImpl extends WidgetFactory<Job, BuildHistoryWidget> {
        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Class<BuildHistoryWidget> widgetType() {
            return BuildHistoryWidget.class;
        }

        @NonNull
        @Override
        public Collection<BuildHistoryWidget> createFor(@NonNull Job target) {
            if (target instanceof Queue.Task) {
                return List.of(new BuildHistoryWidget<>((Queue.Task) target, withBuildsElsewhere(target), Job.HISTORY_ADAPTER));
            }
            return Collections.emptySet();
        }
    }

    /**
     * The job's builds, with those running elsewhere ({@link ExternalBuilds#running}) among them, newest first. A build
     * here wins over one elsewhere with the same number.
     */
    private static Iterable<? extends HistoricalBuild> withBuildsElsewhere(Job<?, ?> job) {
        Iterable<? extends HistoricalBuild> here = job.getBuilds();
        List<? extends ExternalBuild> elsewhere = ExternalBuilds.get().running(job);
        if (elsewhere.isEmpty()) {
            return here;
        }
        List<? extends ExternalBuild> newestFirst = elsewhere.stream()
                .sorted(Comparator.comparingInt(ExternalBuild::getNumber).reversed())
                .toList();
        return () -> new Iterator<HistoricalBuild>() {
            private final PeekingIterator<HistoricalBuild> h = new PeekingIterator<>(here.iterator());
            private final PeekingIterator<HistoricalBuild> e = new PeekingIterator<>(newestFirst.iterator());

            @Override
            public boolean hasNext() {
                return h.hasNext() || e.hasNext();
            }

            @Override
            public HistoricalBuild next() {
                if (!h.hasNext()) {
                    return e.next();
                }
                if (!e.hasNext()) {
                    return h.next();
                }
                int ours = h.peek().getNumber();
                int theirs = e.peek().getNumber();
                if (theirs == ours) {
                    e.next(); // the build here wins
                }
                return theirs > ours ? e.next() : h.next();
            }
        };
    }

    /** An iterator that can look at its next element without taking it. */
    private static final class PeekingIterator<T> implements Iterator<T> {
        private final Iterator<? extends T> delegate;
        private T next;
        private boolean peeked;

        PeekingIterator(Iterator<? extends T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return peeked || delegate.hasNext();
        }

        T peek() {
            if (!peeked) {
                next = delegate.next();
                peeked = true;
            }
            return next;
        }

        @Override
        public T next() {
            if (peeked) {
                peeked = false;
                T t = next;
                next = null;
                return t;
            }
            return delegate.next();
        }
    }
}
