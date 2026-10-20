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

package hudson.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.triggers.TimerTrigger;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A trigger or property added or removed while the project is reloaded (its configuration unmarshalled over it, under
 * its lock, which replaces its lists) goes into the lists the project has once reloaded, not into those it replaced.
 */
@WithJenkins
class ChangeWhileReloadingTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void triggerAddedWhileReloadingIsKept() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        TimerTrigger trigger = new TimerTrigger("@daily");
        whileReloading(p, () -> p.addTrigger(trigger));
        assertNotNull(p.getTrigger(TimerTrigger.class));
        assertNotNull(reloaded(p).getTrigger(TimerTrigger.class));
    }

    @Test
    void triggerRemovedWhileReloadingStaysRemoved() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.addTrigger(new TimerTrigger("@daily"));
        whileReloading(p, () -> p.removeTrigger(j.jenkins.getDescriptorByType(TimerTrigger.DescriptorImpl.class)));
        assertNull(p.getTrigger(TimerTrigger.class));
        assertNull(reloaded(p).getTrigger(TimerTrigger.class));
    }

    @Test
    void propertyAddedWhileReloadingIsKept() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        whileReloading(p, () -> p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("x", "1"))));
        assertNotNull(p.getProperty(ParametersDefinitionProperty.class));
        assertNotNull(reloaded(p).getProperty(ParametersDefinitionProperty.class));
    }

    @Test
    void propertyRemovedWhileReloadingStaysRemoved() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("x", "1")));
        whileReloading(p, () -> assertNotNull(p.removeProperty(ParametersDefinitionProperty.class)));
        assertNull(p.getProperty(ParametersDefinitionProperty.class));
        assertNull(reloaded(p).getProperty(ParametersDefinitionProperty.class));
    }

    private interface Change {
        void run() throws Exception;
    }

    /**
     * Runs a change on another thread while this one holds the project's lock; once that thread waits for the lock,
     * reloads the project's configuration over it, as a reload does, then lets the change go on.
     */
    private static void whileReloading(Job<?, ?> p, Change change) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread changer = new Thread(() -> {
            try {
                change.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "change while reloading");
        synchronized (p) {
            changer.start();
            awaitBlockedOn(changer, p);
            p.getConfigFile().unmarshalNullingOut(p);
        }
        changer.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(changer.isAlive(), "the change finished");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void awaitBlockedOn(Thread t, Object monitor) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(t.getId());
            LockInfo lock = info == null ? null : info.getLockInfo();
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && lock != null
                    && lock.getIdentityHashCode() == System.identityHashCode(monitor)) {
                return;
            }
            assertTrue(t.isAlive() && System.nanoTime() < deadline, "waiting for " + t + " to wait for the lock of " + monitor);
            Thread.onSpinWait();
        }
    }

    /** The project as loaded afresh from what it saved. */
    private static FreeStyleProject reloaded(FreeStyleProject p) throws Exception {
        return (FreeStyleProject) p.getConfigFile().read();
    }
}
