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

package jenkins.persistence;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.model.FreeStyleProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Build numbers come from one sequence in the store, shared with other controllers.
 */
@WithJenkins
class StoredBuildNumberAssignerTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/"));

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private static String stored(String key) {
        return new String(STORE.read(key).content(), StandardCharsets.UTF_8).trim();
    }

    @Test
    void numbersComeFromStore() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        assertEquals("3", stored("jobs/p/nextBuildNumber"));
        assertFalse(new File(p.getRootDir(), "nextBuildNumber").exists(), "not written locally");
    }

    @Test
    void otherControllersNumbersAreSkipped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("q");
        r.buildAndAssertSuccess(p);
        // another controller ran builds 2..9 of the same job
        STORE.as("other").write("jobs/q/nextBuildNumber", "10\n".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        assertEquals(10, r.buildAndAssertSuccess(p).getNumber());
        assertEquals("11", stored("jobs/q/nextBuildNumber"));
    }

    @Test
    void concurrentAssignmentsAreUnique() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("c");
        PersistenceStore other = STORE.as("other");
        Set<Integer> numbers = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(pool.submit(() -> {
                int n = p.assignBuildNumber();
                if (!numbers.add(n)) {
                    throw new AssertionError("duplicate " + n);
                }
                return null;
            }));
            if (i % 10 == 0) {
                // another controller takes a number with the same CAS protocol
                futures.add(pool.submit(() -> {
                    for (;;) {
                        PersistenceStore.Entry e = other.read("jobs/c/nextBuildNumber");
                        int n = e == null ? 1 : Integer.parseInt(new String(e.content(), StandardCharsets.UTF_8).trim());
                        try {
                            other.write("jobs/c/nextBuildNumber", ((n + 1) + "\n").getBytes(StandardCharsets.UTF_8),
                                    e == null ? PersistenceStore.ABSENT : e.version());
                            if (!numbers.add(n)) {
                                throw new AssertionError("duplicate " + n);
                            }
                            return null;
                        } catch (ConflictException x) {
                            // retry
                        }
                    }
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(futures.size(), numbers.size());
        assertEquals(futures.size() + 1, Integer.parseInt(stored("jobs/c/nextBuildNumber")));
    }

    @Test
    void sequenceFollowsRename() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("before");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.renameTo("after");
        assertThat(STORE.versions(), hasKey("jobs/after/nextBuildNumber"));
        assertEquals(3, r.buildAndAssertSuccess(p).getNumber());
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
