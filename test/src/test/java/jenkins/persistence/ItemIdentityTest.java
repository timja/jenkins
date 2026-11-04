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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@link PersistenceStores#itemIdentity}: the identity the store keeps for an item, found from the item.
 */
@WithJenkins
class ItemIdentityTest {

    static volatile InMemoryPersistenceStore STORE;

    @Test
    void theIdentityIsTheOneTheStoreKeepsAtTheItemsKey(JenkinsRule r) throws Exception {
        MockFolder f = r.createFolder("f");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "p");
        // routing of configuration files to the store comes later: here the test stores it
        assertNull(PersistenceStores.itemIdentity(p), "not stored yet");
        STORE.write("jobs/f/config.xml", "f".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        STORE.write("jobs/f/jobs/p/config.xml", "p".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        String id = PersistenceStores.itemIdentity(p);
        assertNotNull(id);
        assertEquals(STORE.itemIdentity("jobs/f/jobs/p"), id);
        assertEquals(STORE.itemIdentity("jobs/f"), PersistenceStores.itemIdentity(f));
    }

    @Test
    void theKeyOfADirectoryIsItsPathBelowTheHome(JenkinsRule r) {
        Path home = Path.of("/var/jenkins_home");
        assertEquals("jobs/a/jobs/b", PersistenceStores.keyOf(home, home.resolve("jobs/a/jobs/b")));
        assertEquals("jobs/b", PersistenceStores.keyOf(home, home.resolve("jobs/a/../b")));
        assertNull(PersistenceStores.keyOf(home, home), "the home itself is no item");
        assertNull(PersistenceStores.keyOf(home, home.resolve("../elsewhere/jobs/a")), "nor is a directory outside it");
        assertNull(PersistenceStores.keyOf(home, Path.of("/var/jenkins_home2/jobs/a")), "nor one whose path only starts with the same text");
    }

    @TestExtension
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE = new InMemoryPersistenceStore();
        }
    }
}
