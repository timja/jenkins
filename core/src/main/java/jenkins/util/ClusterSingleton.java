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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Marks periodic work (a {@link hudson.model.PeriodicWork}) which should run on one controller of a cluster sharing
 * state, not on each, for example because it acts on shared data such as builds of every job.
 * Each scheduled run is subject to {@link PeriodicWorkCoordinator}: with one installed, at most one controller attempts
 * each period, and a period may be skipped (a refusal, a failing coordinator, a controller dying after claiming it).
 * Without one, every controller runs it, as usual. Such work should therefore be idempotent and tolerate a missed period;
 * it is not run exactly once.
 *
 * <p>Periods are windows of the current time divided by {@link hudson.model.PeriodicWork#getRecurrencePeriod}, on each
 * controller's clock: two runs either side of a window's end are in different periods, and may run on different
 * controllers minutes apart. Nor is a run exclusive: one that outlasts its period may overlap the next period's run
 * elsewhere.
 *
 * @since TODO
 */
@Restricted(Beta.class)
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ClusterSingleton {}
