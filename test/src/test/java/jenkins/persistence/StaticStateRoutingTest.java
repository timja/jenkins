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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Util;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.slaves.DumbSlave;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jenkins.model.GlobalConfiguration;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Users, nodes and global configuration that exist only in the store (as on a fresh controller) are loaded.
 */
@WithJenkins
class StaticStateRoutingTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> key.matches("users/[^/]+/config\\.xml")
            || key.matches("nodes/[^/]+/config\\.xml")
            || key.matches("[^/]+\\.xml") && !key.startsWith("queue") && !key.startsWith("nodeMonitors"));

    static {
        try {
            STORE.write("jenkins.persistence.StaticStateRoutingTest$Demo.xml",
                    "<?xml version='1.1' encoding='UTF-8'?><jenkins.persistence.StaticStateRoutingTest_-Demo><value>from the store</value></jenkins.persistence.StaticStateRoutingTest_-Demo>"
                            .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        } catch (ConflictException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void usersExistingOnlyInTheStoreAreLoaded(JenkinsRule r) throws Exception {
        User alice = User.getById("alice", true);
        alice.setFullName("Alice Example");
        alice.save();
        Set<String> stored = STORE.children("users");
        assertEquals(1, stored.size(), stored.toString());

        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "users"));
        User.reload();

        User again = User.getById("alice", false);
        assertNotNull(again, "loaded from the store");
        assertEquals("Alice Example", again.getFullName());
    }

    @Test
    void deletedUsersAreDeletedFromTheStore(JenkinsRule r) throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        realm.createAccount("bob", "bob-password-1");
        assertTrue(STORE.children("users").stream().anyMatch(n -> n.startsWith("bob_")), "stored");

        User.getById("bob", false).delete();

        assertTrue(STORE.children("users").stream().noneMatch(n -> n.startsWith("bob_")), "deleted from the store too");
        // a controller starting afresh does not bring the user back
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "users"));
        User.reload();
        assertNull(User.getById("bob", false));
        assertThrows(UsernameNotFoundException.class, () -> realm.loadUserByUsername2("bob"));
    }

    @Test
    void nodesExistingOnlyInTheStoreAreLoadedAndDeletionsReachTheStore(JenkinsRule r) throws Exception {
        DumbSlave agent = r.createSlave("agent-x", null, null);
        assertNotNull(STORE.read("nodes/agent-x/config.xml"));

        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "nodes"));
        r.jenkins.getNodesObject().load();
        assertNotNull(r.jenkins.getNode("agent-x"), "loaded from the store");

        r.jenkins.removeNode(r.jenkins.getNode("agent-x"));
        assertNull(STORE.read("nodes/agent-x/config.xml"), "deletion reached the store");
        assertNull(agent.toComputer());
    }

    @Test
    void globalConfigurationComesFromTheStore(JenkinsRule r) {
        assertEquals("from the store", GlobalConfiguration.all().get(Demo.class).value);
    }

    @TestExtension
    public static class Demo extends GlobalConfiguration {
        String value = "default";

        {
            load();
        }
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
