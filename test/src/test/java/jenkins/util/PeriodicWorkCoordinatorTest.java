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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.Fingerprint;
import hudson.model.FingerprintCleanupThread;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.fingerprints.FingerprintStorage;
import jenkins.fingerprints.FingerprintStorageDescriptor;
import jenkins.fingerprints.GlobalFingerprintConfiguration;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PeriodicWorkCoordinatorTest {

    @Test
    void onlyClusterSingletonWorkIsCoordinated(JenkinsRule r) throws Exception {
        Coordinator.allow = false;
        Coordinator.asked.clear();
        Thread.sleep(2_000);
        assertEquals(0, Singleton.runs.get(), "refused by the coordinator");
        assertThat("ordinary periodic work is not coordinated", PerController.runs.get(), greaterThan(0));
        // other cluster-singleton work of core may be due meanwhile too: only these two are known to be
        assertTrue(Coordinator.asked.contains(Singleton.class.getName()));
        assertFalse(Coordinator.asked.contains(PerController.class.getName()));

        Coordinator.allow = true;
        Thread.sleep(2_000);
        assertThat(Singleton.runs.get(), greaterThan(0));
    }

    @Test
    void cleanupAskedForRunsWhateverTheCoordinatorSays(JenkinsRule r) throws Exception {
        Coordinator.allow = false;
        File log = new File(r.jenkins.getRootDir(), "logs/tasks/Fingerprint cleanup.log");
        Files.deleteIfExists(log.toPath());
        FingerprintCleanupThread.invoke(); // as POST /fingerprintCleanup does
        for (int i = 0; i < 100 && !log.isFile(); i++) {
            Thread.sleep(100);
        }
        assertTrue(log.isFile(), "the cleanup ran");
    }

    @Test
    void localFingerprintsAreCleanedOnEveryController(JenkinsRule r) throws Exception {
        Coordinator.allow = false;
        CountingStorage shared = new CountingStorage();
        GlobalFingerprintConfiguration.get().setStorage(shared);
        assertTrue(PeriodicWorkCoordinator.allows(ExtensionList.lookupSingleton(FingerprintCleanupThread.class)),
                "each controller cleans its own fingerprint files");
        assertFalse(PeriodicWorkCoordinator.allows(ExtensionList.lookupSingleton(FingerprintCleanupThread.Shared.class)));
        new FingerprintCleanupThread().execute(TaskListener.NULL);
        assertEquals(0, shared.cleanups, "the shared storage is cleaned once per cluster, not by every controller");
        new FingerprintCleanupThread.Shared().execute(TaskListener.NULL);
        assertEquals(1, shared.cleanups);
    }

    public static class CountingStorage extends FingerprintStorage {
        int cleanups;

        @Override
        public void save(Fingerprint fp) {}

        @Override
        public Fingerprint load(String id) {
            return null;
        }

        @Override
        public void delete(String id) {}

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public void iterateAndCleanupFingerprints(TaskListener taskListener) {
            cleanups++;
        }

        @TestExtension("localFingerprintsAreCleanedOnEveryController")
        public static class DescriptorImpl extends FingerprintStorageDescriptor {}
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
    public static class PerController extends PeriodicWork {
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
