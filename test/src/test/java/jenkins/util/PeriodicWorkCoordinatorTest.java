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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.PeriodicWork;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import jenkins.model.BackgroundGlobalBuildDiscarder;
import jenkins.telemetry.Telemetry;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PeriodicWorkCoordinatorTest {

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void onlyClusterSingletonWorkIsCoordinated(JenkinsRule r) throws Exception {
        Coordinator.allow = false;
        // the scheduler asked about the singleton (and refused it) after ordinary work had run a few times
        int before = PerInstance.runs.get();
        await("ordinary periodic work ran", () -> PerInstance.runs.get() >= before + 3);
        await("the coordinator was asked", () -> Coordinator.asked.contains(Singleton.class.getName()));
        assertEquals(0, Singleton.runs.get(), "refused by the coordinator");
        assertFalse(Coordinator.asked.contains(PerInstance.class.getName()), "ordinary periodic work is not coordinated");

        Coordinator.allow = true;
        await("the singleton ran once allowed", () -> Singleton.runs.get() > 0);
    }

    @Test
    void aFailingCoordinatorRefuses(JenkinsRule r) {
        assertFalse(PeriodicWorkCoordinator.allows(ExtensionList.lookupSingleton(Singleton.class)));
        assertTrue(PeriodicWorkCoordinator.allows(ExtensionList.lookupSingleton(PerInstance.class)));
    }

    @TestExtension("aFailingCoordinatorRefuses")
    public static class Failing implements PeriodicWorkCoordinator {
        @Override
        public boolean shouldRun(PeriodicWork work, long period) {
            throw new IllegalStateException("store unreachable");
        }
    }

    @Test
    void withoutCoordinatorsEverythingRuns(JenkinsRule r) throws Exception {
        assertTrue(PeriodicWorkCoordinator.allows(ExtensionList.lookupSingleton(Singleton.class)));
        int before = Singleton.runs.get();
        await("the singleton ran", () -> Singleton.runs.get() > before);
    }

    @Test
    void workOnSharedStateIsMarked(JenkinsRule r) {
        assertTrue(BackgroundGlobalBuildDiscarder.class.isAnnotationPresent(ClusterSingleton.class));
        assertTrue(Telemetry.TelemetryReporter.class.isAnnotationPresent(ClusterSingleton.class));
    }

    @ClusterSingleton
    @TestExtension
    public static class Singleton extends PeriodicWork {
        static final AtomicInteger runs = new AtomicInteger();

        @Override
        public long getRecurrencePeriod() {
            return 200;
        }

        @Override
        public long getInitialDelay() {
            return 0;
        }

        @Override
        protected void doRun() {
            runs.incrementAndGet();
        }
    }

    @TestExtension
    public static class PerInstance extends PeriodicWork {
        static final AtomicInteger runs = new AtomicInteger();

        @Override
        public long getRecurrencePeriod() {
            return 200;
        }

        @Override
        public long getInitialDelay() {
            return 0;
        }

        @Override
        protected void doRun() {
            runs.incrementAndGet();
        }
    }

    @TestExtension("onlyClusterSingletonWorkIsCoordinated")
    public static class Coordinator implements PeriodicWorkCoordinator {
        static volatile boolean allow;
        static final Set<String> asked = ConcurrentHashMap.newKeySet();

        @Override
        public boolean shouldRun(PeriodicWork work, long period) {
            asked.add(work.getClass().getName());
            return allow;
        }
    }
}
