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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Changes made by another controller (simulated by a store view with another origin) are applied here.
 */
@WithJenkins
class ItemChangeApplierTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
    }

    private static byte[] project(String description) {
        return ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + description + "</description></project>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitApplied() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!ItemChangeApplier.isIdle()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("changes not applied in time");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void createdElsewhere() throws Exception {
        other.write("jobs/remote/config.xml", project("made elsewhere"), PersistenceStore.ABSENT);
        awaitApplied();
        FreeStyleProject p = r.jenkins.getItemByFullName("remote", FreeStyleProject.class);
        assertNotNull(p);
        assertEquals("made elsewhere", p.getDescription());
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/remote").isDirectory());
    }

    @Test
    void updatedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        long before = ItemChangeApplier.getAppliedCount();
        other.write("jobs/p/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
        awaitApplied();
        assertThat(r.jenkins.getItem("p"), sameInstance(p));
        assertEquals("changed elsewhere", p.getDescription());
        assertEquals(before + 1, ItemChangeApplier.getAppliedCount());
        // and a local save afterwards is not a conflict: we saw the latest version
        long conflicts = PersistenceStores.getConflictCount();
        p.setDescription("then here");
        assertEquals(conflicts, PersistenceStores.getConflictCount());
    }

    @Test
    void ownChangesAreNotReapplied() throws Exception {
        long before = ItemChangeApplier.getAppliedCount();
        r.createFreeStyleProject("mine").setDescription("local");
        awaitApplied();
        assertEquals(before, ItemChangeApplier.getAppliedCount());
    }

    @Test
    void createdInFolderElsewhere() throws Exception {
        r.createFolder("f");
        other.write("jobs/f/jobs/c/config.xml", project("child"), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals("child", r.jenkins.getItemByFullName("f/c", FreeStyleProject.class).getDescription());
    }

    @Test
    void childBeforeParent() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template")).readRawBytes();
        other.write("jobs/g/jobs/c/config.xml", project("early child"), PersistenceStore.ABSENT);
        other.write("jobs/g/config.xml", folderXml, PersistenceStore.ABSENT);
        awaitApplied();
        assertThat(r.jenkins.getItem("g"), instanceOf(MockFolder.class));
        assertEquals("early child", r.jenkins.getItemByFullName("g/c", FreeStyleProject.class).getDescription());
    }

    @Test
    void deletedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("doomed");
        File dir = p.getRootDir();
        other.delete("jobs/doomed/config.xml", PersistenceStore.ANY);
        awaitApplied();
        assertNull(r.jenkins.getItem("doomed"));
        assertFalse(dir.exists());
    }

    @Test
    void renamedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("old-name");
        other.move("jobs/old-name", "jobs/new-name");
        awaitApplied();
        assertNull(r.jenkins.getItem("old-name"));
        assertThat(r.jenkins.getItem("new-name"), sameInstance(p));
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/new-name").isDirectory());
        assertTrue(STORE.versions().containsKey("jobs/new-name/config.xml"), "rename was not re-done locally against the store");
    }

    @Test
    void movedIntoFolderElsewhere() throws Exception {
        r.createFolder("dest");
        FreeStyleProject p = r.createFreeStyleProject("traveller");
        other.move("jobs/traveller", "jobs/dest/jobs/traveller");
        awaitApplied();
        assertNull(r.jenkins.getItem("traveller"));
        assertNotNull(r.jenkins.getItemByFullName("dest/traveller"));
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
