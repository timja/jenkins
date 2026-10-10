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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Util;
import hudson.model.FreeStyleProject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Items which exist only in the store (as on a freshly started controller) are loaded.
 */
@WithJenkins
class PersistenceStoreItemEnumerationTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void topLevelItemsLoadFromStore() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("from store");
        // an item created by another controller, never seen locally
        STORE.write("jobs/q/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        // simulate a fresh JENKINS_HOME
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));

        r.jenkins.reload();

        assertEquals("from store", r.jenkins.getItemByFullName("p", FreeStyleProject.class).getDescription());
        assertEquals("elsewhere", r.jenkins.getItemByFullName("q", FreeStyleProject.class).getDescription());
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/q").isDirectory(), "local item directory is created for builds");
    }

    @Test
    void folderChildrenLoadFromStore() throws Exception {
        MockFolder f = r.createFolder("f");
        f.createProject(FreeStyleProject.class, "x").setDescription("nested");
        STORE.write("jobs/f/jobs/y/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>nested elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));

        r.jenkins.reload();

        assertThat(r.jenkins.getItem("f"), instanceOf(MockFolder.class));
        assertEquals("nested", r.jenkins.getItemByFullName("f/x", FreeStyleProject.class).getDescription());
        FreeStyleProject y = r.jenkins.getItemByFullName("f/y", FreeStyleProject.class);
        assertNotNull(y);
        assertEquals("nested elsewhere", y.getDescription());
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
