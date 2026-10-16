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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import jenkins.model.GlobalConfiguration;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Static state changed by another controller (simulated by a store view with another origin) is applied without a restart.
 */
@WithJenkins
class StaticStateApplierTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> key.matches("users/[^/]+/config\\.xml")
            || key.matches("nodes/[^/]+/config\\.xml")
            || key.matches("[^/]+\\.xml") && !key.startsWith("queue") && !key.startsWith("nodeMonitors"));

    private final LogRecorder logging = new LogRecorder();
    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ItemChangeApplier.isIdle());
    }

    private static String text(String key) {
        return new String(STORE.read(key).content(), StandardCharsets.UTF_8);
    }

    private void writeElsewhere(String key, String content) throws Exception {
        other.write(key, content.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();
    }

    @Test
    void globalConfigurationChangedElsewhere() throws Exception {
        r.jenkins.setSystemMessage("set here");
        r.jenkins.setNumExecutors(2);
        writeElsewhere("config.xml", text("config.xml").replace("set here", "set elsewhere")
                .replace("<numExecutors>2</numExecutors>", "<numExecutors>5</numExecutors>"));
        assertEquals("set elsewhere", r.jenkins.getSystemMessage());
        assertEquals(5, r.jenkins.getNumExecutors());
        assertEquals(5, r.jenkins.toComputer().getNumExecutors(), "computers updated");

        // a field cleared elsewhere has no element in the file: it is cleared here too, and stays so when saved here
        writeElsewhere("config.xml", text("config.xml").replaceAll("<systemMessage>[^<]*</systemMessage>", ""));
        assertNull(r.jenkins.getSystemMessage());
        r.jenkins.save();
        assertFalse(text("config.xml").contains("<systemMessage>"));
    }

    @Test
    void agentPortChangedElsewhere() throws Exception {
        writeElsewhere("config.xml", text("config.xml").replaceAll("<slaveAgentPort>[^<]*</slaveAgentPort>", "<slaveAgentPort>0</slaveAgentPort>"));
        assertNotNull(r.jenkins.getTcpSlaveAgentListener(), "listening on a port chosen");
        writeElsewhere("config.xml", text("config.xml").replaceAll("<slaveAgentPort>[^<]*</slaveAgentPort>", "<slaveAgentPort>-1</slaveAgentPort>"));
        assertNull(r.jenkins.getTcpSlaveAgentListener(), "no longer listening");
    }

    @Test
    void descriptorChangedElsewhere() throws Exception {
        Demo demo = GlobalConfiguration.all().get(Demo.class);
        demo.value = "here";
        demo.save();
        writeElsewhere("jenkins.persistence.StaticStateApplierTest$Demo.xml", text("jenkins.persistence.StaticStateApplierTest$Demo.xml").replace("here", "elsewhere"));
        assertEquals("elsewhere", demo.value);

        JenkinsLocationConfiguration location = JenkinsLocationConfiguration.get();
        location.setAdminAddress("admin@example.com");
        String locationKey = JenkinsLocationConfiguration.class.getName() + ".xml";
        writeElsewhere(locationKey, text(locationKey).replaceAll("<adminAddress>[^<]*</adminAddress>", ""));
        assertNotEquals("admin@example.com", location.getAdminAddress(), "cleared elsewhere");
    }

    @Test
    void usersChangedCreatedAndDeletedElsewhere() throws Exception {
        User alice = User.getById("alice", true);
        alice.setFullName("Alice");
        alice.save();
        String aliceKey = STORE.children("users").stream().map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        writeElsewhere(aliceKey, text(aliceKey).replace("<fullName>Alice</fullName>", "<fullName>Alice Changed</fullName>"));
        assertEquals("Alice Changed", User.getById("alice", false).getFullName());
        alice.setDescription("about Alice");
        alice.save();
        writeElsewhere(aliceKey, text(aliceKey).replaceAll("<description>[^<]*</description>", ""));
        assertNull(alice.getDescription(), "cleared elsewhere");

        Method folder = User.class.getDeclaredMethod("getUserFolderNameFor", String.class);
        folder.setAccessible(true);
        String bobKey = "users/" + folder.invoke(null, "bob") + "/config.xml";
        writeElsewhere(bobKey, text(aliceKey).replace("<id>alice</id>", "<id>bob</id>").replace("Alice Changed", "Bob"));
        assertEquals("Bob", User.getById("bob", false).getFullName(), "created elsewhere");
        assertNotNull(User.getById("bob", false).getUserFolder(), "has a folder here, as a user made here has");

        other.delete(aliceKey, PersistenceStore.ANY);
        awaitApplied();
        assertNull(User.getById("alice", false), "deleted elsewhere");
        assertFalse(new File(r.jenkins.getRootDir(), aliceKey).exists(), "and its local copy");
    }

    @Test
    void aUserLoadedFromAChangeElsewhereBeforeTheStartupScanIsNoDuplicate() throws Exception {
        User erin = User.getById("erin", true);
        erin.save();
        logging.record(User.class, Level.WARNING).capture(10);
        User.AllUsers.scanAll(); // as at startup, after a change made elsewhere loaded erin already
        assertTrue(logging.getMessages().stream().noneMatch(m -> m.contains("duplicates")), logging.getMessages().toString());
        assertNotNull(User.getById("erin", false));
    }

    @Test
    void aUserSavedHereAfterItsDeletionElsewhereIsKept() throws Exception {
        User dave = User.getById("dave", true);
        dave.save();
        String daveKey = STORE.children("users").stream().filter(d -> d.startsWith("dave_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        other.write("blocker.xml", new byte[] {'x'}, PersistenceStore.ANY); // holds the applier until released
        Blocker.holding.await();
        other.delete(daveKey, PersistenceStore.ANY);
        dave.setFullName("Dave Again");
        dave.save(); // after the deletion elsewhere, which it overwrites (last writer wins)
        Blocker.release.countDown();
        awaitApplied();
        assertNotNull(STORE.read(daveKey), "stored again");
        assertEquals("Dave Again", User.getById("dave", false).getFullName(), "the deletion, older than the save, is not applied");
        assertTrue(new File(r.jenkins.getRootDir(), daveKey).getParentFile().isDirectory());
    }

    @TestExtension("aUserSavedHereAfterItsDeletionElsewhereIsKept")
    public static class Blocker implements RemoteChangeHandler {
        static final CountDownLatch holding = new CountDownLatch(1);
        static final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean handles(String key) {
            return key.equals("blocker.xml");
        }

        @Override
        public void changed(String key, boolean deleted) throws Exception {
            holding.countDown();
            release.await();
        }
    }

    @Test
    void aUserDeletedElsewhereCannotSignInHere() throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        realm.createAccount("carol", "carol-password-1");
        assertNotNull(realm.loadUserByUsername2("carol")); // cached here
        String carolKey = STORE.children("users").stream().filter(d -> d.startsWith("carol_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();

        other.delete(carolKey, PersistenceStore.ANY);
        awaitApplied();

        assertThrows(UsernameNotFoundException.class, () -> realm.loadUserByUsername2("carol"));
        JenkinsRule.WebClient wc = r.createWebClient();
        assertThrows(Exception.class, () -> wc.login("carol", "carol-password-1"));
    }

    @Test
    void nodesChangedCreatedAndDeletedElsewhere() throws Exception {
        r.createSlave("n1", "old-label", null);
        assertEquals(1, Label.get("old-label").getNodes().size());
        writeElsewhere("nodes/n1/config.xml", text("nodes/n1/config.xml").replace("old-label", "new-label"));
        Node n1 = r.jenkins.getNode("n1");
        assertEquals("new-label", n1.getLabelString());
        assertTrue(Label.get("old-label").getNodes().isEmpty(), "the label removed elsewhere has left the node");
        assertEquals(n1, n1.toComputer().getNode(), "computer bound to the reloaded node");

        writeElsewhere("nodes/n2/config.xml", text("nodes/n1/config.xml").replace("<name>n1</name>", "<name>n2</name>"));
        assertNotNull(r.jenkins.getNode("n2"), "created elsewhere");
        assertNotNull(r.jenkins.getComputer("n2"));

        other.delete("nodes/n2/config.xml", PersistenceStore.ANY);
        awaitApplied();
        assertNull(r.jenkins.getNode("n2"), "deleted elsewhere");
    }

    @Test
    void pluginStateGoesToItsHandler() throws Exception {
        writeElsewhere("custom.plugin.xml", "<x/>");
        assertEquals(List.of("custom.plugin.xml false"), Handler.seen);
    }

    @TestExtension
    public static class Demo extends GlobalConfiguration {
        String value = "default";

        {
            load();
        }
    }

    @TestExtension("pluginStateGoesToItsHandler")
    public static class Handler implements RemoteChangeHandler {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public boolean handles(String key) {
            return key.equals("custom.plugin.xml");
        }

        @Override
        public void changed(String key, boolean deleted) {
            seen.add(key + " " + deleted);
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
