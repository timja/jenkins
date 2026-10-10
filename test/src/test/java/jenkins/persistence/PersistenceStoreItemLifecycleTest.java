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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.Util;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Renames and moves carry the stored configuration along.
 */
@WithJenkins
class PersistenceStoreItemLifecycleTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private void freshHome() throws Exception {
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));
        r.jenkins.reload();
    }

    @Test
    void rename() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("before");
        p.setDescription("renamed");
        p.renameTo("after");
        assertThat(STORE.versions(), not(hasKey("jobs/before/config.xml")));
        assertThat(STORE.versions(), hasKey("jobs/after/config.xml"));
        freshHome();
        assertNull(r.jenkins.getItem("before"));
        assertEquals("renamed", r.jenkins.getItemByFullName("after", FreeStyleProject.class).getDescription());
    }

    @Test
    void renameOntoStoredItemFails() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("mine");
        // another controller already created "theirs"; this controller has not loaded it yet
        STORE.write("jobs/theirs/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> p.renameTo("theirs"));
        assertEquals("mine", p.getName());
        assertThat(STORE.versions(), hasKey("jobs/mine/config.xml"));
    }

    @Test
    void moveIntoFolder() throws Exception {
        MockFolder f = r.createFolder("f");
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("moved");
        Items.move(p, f);
        assertThat(STORE.versions(), not(hasKey("jobs/p/config.xml")));
        assertThat(STORE.versions(), hasKey("jobs/f/jobs/p/config.xml"));
        freshHome();
        assertEquals("moved", r.jenkins.getItemByFullName("f/p", FreeStyleProject.class).getDescription());
    }

    @Test
    void renameFolderMovesChildren() throws Exception {
        MockFolder f = r.createFolder("old");
        f.createProject(FreeStyleProject.class, "child").setDescription("inside");
        f.renameTo("new");
        assertThat(STORE.versions(), hasKey("jobs/new/config.xml"));
        assertThat(STORE.versions(), hasKey("jobs/new/jobs/child/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/old/jobs/child/config.xml")));
        freshHome();
        assertEquals("inside", r.jenkins.getItemByFullName("new/child", FreeStyleProject.class).getDescription());
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
