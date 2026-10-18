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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Which files under {@code JENKINS_HOME} the store serves.
 */
@WithJenkins
class StoreRoutingTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private static boolean routed(String key) {
        return StoreRouting.isRouted(key);
    }

    @Test
    void coreSharesItemConfigurationAndStaticStateByDefault() {
        assertTrue(routed("jobs/p/config.xml"));
        assertTrue(routed("jobs/f/jobs/p/config.xml"), "items in folders");
        assertTrue(routed("jobs/mb/branches/main/config.xml"), "branches");
        assertTrue(routed("jobs/m/configurations/axis-a/1/config.xml"), "matrix configurations");
        assertTrue(routed("jobs/p/promotions/release/config.xml"), "promotion processes");
        assertTrue(routed("jobs/f/jobs/builds/config.xml"), "an item called builds");
        assertTrue(routed("config.xml"));
        assertTrue(routed("hudson.tasks.Mailer.xml"));
        assertTrue(routed("users/alice_123/config.xml"));
        assertTrue(routed("nodes/agent/config.xml"));
        assertTrue(routed("log/recorder.xml"));
        assertFalse(routed("jobs/config.xml"));
        assertFalse(routed("jobs/p/other.xml"), "other files of items need a SharedFiles pattern");
        assertFalse(routed("users/alice_123/other.xml"));
        assertFalse(routed("secret.key"));
    }

    @Test
    void buildDataNumbersSecretsAndReservedKeysAreNeverRouted() {
        // not even by a pattern sharing them
        assertTrue(routed("jobs/p/scm-revision-hash.xml"), "a SharedFiles pattern");
        assertFalse(routed("jobs/p/builds/1/config.xml"));
        assertFalse(routed("jobs/p/builds/1/scm-revision-hash.xml"));
        assertFalse(routed("jobs/p/nextBuildNumber"));
        assertFalse(routed("secrets/master.key"));
        assertFalse(routed("secrets/hudson.util.Secret"));
        assertFalse(routed("_items/0f/builds/1/build.xml"));
        assertFalse(routed("_items.xml"));
        assertFalse(routed("jobs/../config.xml"));
        assertFalse(routed("jobs//config.xml"));
    }

    @Test
    void localFilesWin() {
        assertFalse(routed("queue.xml"), "core's own");
        assertFalse(routed("nodeMonitors.xml"));
        assertFalse(routed("hudson.model.UpdateCenter.xml"));
        assertFalse(routed("jenkins.install.InstallUtil.lastExecVersion"));
        assertFalse(routed("users/users.xml"));
        assertFalse(routed("my-plugin-state.xml"), "a LocalFiles pattern");
        assertFalse(routed("jobs/p/state.xml"), "a LocalFiles pattern wins over a SharedFiles one");
    }

    @Test
    void cloudAgentsAreKeptLocal() {
        assertTrue(routed("nodes/cloud-1/config.xml"));
        StoreRouting.keepLocal("nodes/cloud-1/config.xml", true);
        try {
            assertFalse(routed("nodes/cloud-1/config.xml"));
            assertNull(StoreRouting.route(new File(r.jenkins.getRootDir(), "nodes/cloud-1/config.xml")));
        } finally {
            StoreRouting.keepLocal("nodes/cloud-1/config.xml", false);
        }
        assertTrue(routed("nodes/cloud-1/config.xml"));
    }

    @Test
    void routesAreKeysOfFilesUnderTheHome() {
        Route route = StoreRouting.route(new File(r.jenkins.getRootDir(), "jobs/f/jobs/p/config.xml"));
        assertNotNull(route);
        assertEquals("jobs/f/jobs/p/config.xml", route.key());
        assertEquals(new File(r.jenkins.getRootDir(), "jobs/f/jobs/p/config.xml"), PersistenceStores.fileFor(route.key()));
        assertNull(StoreRouting.route(new File(r.jenkins.getRootDir().getParentFile(), "config.xml")), "outside the home");
        assertNull(PersistenceStores.keyFor(r.jenkins.getRootDir()));
    }

    @Test
    void keysNamingNoFileAreNoPaths() {
        assertFalse(PersistenceStores.isSafeKey("jobs/../../etc/passwd"));
        assertFalse(PersistenceStores.isSafeKey("jobs/./config.xml"));
        assertFalse(PersistenceStores.isSafeKey("jobs/a\\..\\b/config.xml"));
        assertFalse(PersistenceStores.isSafeKey("/etc/passwd"));
        assertTrue(PersistenceStores.isSafeKey("jobs/a b/config.xml"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PersistenceStores.fileFor("jobs/../../x"));
    }

    @TestExtension
    public static final class Local implements LocalFiles {
        @Override
        public Collection<String> patterns() {
            return List.of("my-plugin-state.xml", "jobs/**/state.xml");
        }
    }

    @TestExtension
    public static final class Shared implements SharedFiles {
        @Override
        public Collection<String> patterns() {
            return List.of("jobs/**/scm-revision-hash.xml", "jobs/**/state.xml", "jobs/**/nextBuildNumber", "secrets/*", "_items/**");
        }
    }

    @TestExtension
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return new InMemoryPersistenceStore();
        }
    }
}
