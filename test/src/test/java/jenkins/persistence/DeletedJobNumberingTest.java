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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Util;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Deleting a job removes its stored history but keeps its build number sequence,
 * so a job recreated (or renamed) to the same name never reuses a number.
 */
@WithJenkins
class DeletedJobNumberingTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return true;
        }
    };
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        BLOBS.clear();
    }

    private static String stored(String key) {
        PersistenceStore.Entry e = STORE.read(key);
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8).trim();
    }

    private static List<Integer> numbers(FreeStyleProject p) {
        return p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList();
    }

    @Test
    void recreatedJobContinuesNumberingWithEmptyHistory() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.delete();

        assertEquals(List.of(), List.copyOf(STORE.children("jobs/p/builds")), "records deleted");
        assertTrue(BLOBS.keys().isEmpty(), "snapshots deleted");
        assertNull(stored("jobs/p/config.xml"));
        assertEquals("3", stored("jobs/p/nextBuildNumber"), "sequence kept");

        p = r.createFreeStyleProject("p");
        assertEquals(List.of(), numbers(p));
        assertEquals(3, r.buildAndAssertSuccess(p).getNumber());

        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));
        r.jenkins.reload();
        assertEquals(List.of(3), numbers(r.jenkins.getItemByFullName("p", FreeStyleProject.class)));
    }

    @Test
    void deletingFolderRemovesNestedHistory() throws Exception {
        MockFolder f = r.createFolder("f");
        FreeStyleProject x = f.createProject(FreeStyleProject.class, "x");
        r.buildAndAssertSuccess(x);
        f.delete();

        assertEquals(List.of(), List.copyOf(STORE.children("jobs/f/jobs/x/builds")));
        assertEquals("2", stored("jobs/f/jobs/x/nextBuildNumber"));

        f = r.createFolder("f");
        x = f.createProject(FreeStyleProject.class, "x");
        assertEquals(List.of(), numbers(x));
        assertEquals(2, r.buildAndAssertSuccess(x).getNumber());
    }

    @Test
    void renameOntoDeletedJobKeepsHigherNumber() throws Exception {
        FreeStyleProject old = r.createFreeStyleProject("target");
        for (int i = 0; i < 3; i++) {
            r.buildAndAssertSuccess(old);
        }
        old.delete();
        assertEquals("4", stored("jobs/target/nextBuildNumber"));

        FreeStyleProject p = r.createFreeStyleProject("source");
        r.buildAndAssertSuccess(p);
        p.renameTo("target"); // would conflict with the leftover nextBuildNumber

        assertEquals("4", stored("jobs/target/nextBuildNumber"));
        assertNull(stored("jobs/source/nextBuildNumber"));
        assertEquals(List.of(1), numbers(p), "history of the renamed job");
        assertEquals(4, r.buildAndAssertSuccess(p).getNumber());
    }

    @Test
    void renameOntoExistingItemStillFails() throws Exception {
        // something other than a leftover number at the destination
        STORE.write("jobs/occupied/config.xml", "<project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        FreeStyleProject p = r.createFreeStyleProject("mover");
        assertThrows(ConflictException.class, () -> PersistenceStores.move(p.getRootDir(), new File(r.jenkins.getRootDir(), "jobs/occupied")));
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }

    @TestExtension
    public static class Blobs implements BlobStoreProvider {
        @Override
        public BlobStore create() {
            return BLOBS;
        }
    }
}
