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

import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.GroupDetails;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.SecurityRealm;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.IdStrategy;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.model.NodeListener;
import jenkins.security.HMACConfidentialKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Static state changed by another controller (simulated by a store view with another origin) is applied without a restart.
 */
@WithJenkins
class StaticStateApplierTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> key.matches("users/[^/]+/config\\.xml")
            || key.matches("nodes/[^/]+/config\\.xml")
            || key.matches("[^/]+\\.xml") && !key.startsWith("queue") && !key.startsWith("nodeMonitors")) {
        @Override
        public Entry read(String key) {
            ReadHook hook = onRead;
            return hook == null ? super.read(key) : hook.read(key, () -> super.read(key));
        }
    };

    /** Reads made here, which a test can delay or precede with a change made elsewhere. */
    interface ReadHook {
        PersistenceStore.Entry read(String key, Supplier<PersistenceStore.Entry> read);
    }

    static volatile ReadHook onRead;

    private static boolean calledFrom(Class<?> type, String methodPrefix) {
        return StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals(type.getName()) && f.getMethodName().startsWith(methodPrefix)));
    }

    private static boolean applierBlocked() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("Persistence store item change applier") && t.getState() == Thread.State.BLOCKED);
    }

    private final LogRecorder logging = new LogRecorder();
    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        onRead = null;
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
        String aliceKey = STORE.children("users").stream().filter(d -> d.startsWith("alice_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
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
    void aFieldRemovedByAVersionStoredWhileADescriptorIsReloadedIsCleared() throws Exception {
        JenkinsLocationConfiguration location = JenkinsLocationConfiguration.get();
        location.setUrl("http://first.example/");
        location.setAdminAddress("admin@example.com");
        String key = JenkinsLocationConfiguration.class.getName() + ".xml";
        String second = text(key).replace("admin@example.com", "other@example.com");
        // stored between the read finding what the second version removed (nothing) and the read loading it
        String third = second.replaceAll("<jenkinsUrl>[^<]*</jenkinsUrl>", "");
        onRead = (k, read) -> {
            if (k.equals(key) && calledFrom(Descriptor.class, "load")) {
                onRead = null;
                try {
                    other.write(key, third.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return read.get();
        };
        writeElsewhere(key, second);
        assertNull(onRead);
        assertEquals("other@example.com", location.getAdminAddress());
        assertNull(location.getUrl(), "removed by the third version");
    }

    @Test
    void aUserStoredWithoutPropertiesElsewhereLoadsWithDefaultOnes() throws Exception {
        User frank = User.getById("frank", true);
        frank.save();
        String frankKey = STORE.children("users").stream().filter(d -> d.startsWith("frank_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        // as written by another version of Jenkins, or by hand: no <properties/>, which the constructor initialises
        writeElsewhere(frankKey, "<?xml version='1.1' encoding='UTF-8'?><user><id>frank</id><fullName>Frank Elsewhere</fullName></user>");
        User again = User.getById("frank", false);
        assertEquals("Frank Elsewhere", again.getFullName());
        assertFalse(again.getAllProperties().isEmpty(), "default properties");
    }

    @Test
    void aUserChangedElsewhereWhileUsersAreScannedHasTheChange() throws Exception {
        User grace = User.getById("grace", true);
        grace.setFullName("Grace One");
        grace.save();
        String graceKey = STORE.children("users").stream().filter(d -> d.startsWith("grace_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        onRead = (k, read) -> {
            PersistenceStore.Entry e = read.get();
            if (k.equals(graceKey) && calledFrom(User.AllUsers.class, "scanAll") && calledFrom(hudson.XmlFile.class, "unmarshal")) {
                onRead = null;
                scanning.countDown(); // having read the first version
                try {
                    release.await();
                } catch (InterruptedException x) {
                    throw new AssertionError(x);
                }
            }
            return e;
        };
        Thread reloading = new Thread(() -> {
            try {
                User.reload();
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        });
        reloading.start();
        assertTrue(scanning.await(30, TimeUnit.SECONDS));
        long applied = ItemChangeApplier.getAppliedCount();
        other.write(graceKey, text(graceKey).replace("Grace One", "Grace Two").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        // until the change is applied, or waits for the scan to end
        for (int i = 0; i < 500 && ItemChangeApplier.getAppliedCount() == applied && !applierBlocked(); i++) {
            Thread.sleep(10);
        }
        release.countDown();
        reloading.join();
        awaitApplied();
        assertEquals("Grace Two", User.getById("grace", false).getFullName());
    }

    @Test
    void aUserIdStrategyChangedHereMovesTheStoredUsers() throws Exception {
        r.jenkins.setSecurityRealm(new StrategyRealm(false));
        try {
            User henry = User.getById("Henry", true);
            henry.setFullName("Henry H");
            henry.save();
            User ivan = User.getById("Ivan", true);
            ivan.setFullName("Ivan I");
            ivan.save();
            Util.deleteRecursive(ivan.getUserFolder()); // stored only, as if made on another controller
            Set<String> before = storedUsers("henry_", "ivan_");

            r.jenkins.setSecurityRealm(new StrategyRealm(true));

            assertEquals("Henry H", User.getById("Henry", false).getFullName());
            assertEquals("Ivan I", User.getById("Ivan", false).getFullName());
            Set<String> after = storedUsers("henry_", "ivan_");
            assertEquals(2, after.size(), after.toString());
            assertTrue(after.stream().noneMatch(before::contains), "moved in the store: " + before + " " + after);
        } finally {
            r.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION); // the configuration is stored for later tests
        }
    }

    private static Set<String> storedUsers(String... prefixes) {
        return STORE.children("users").stream().filter(d -> Arrays.stream(prefixes).anyMatch(d::startsWith)).collect(Collectors.toSet());
    }

    @Test
    void aUserIdStrategyChangedElsewhereKeepsTheUsersFindable() throws Exception {
        r.jenkins.setSecurityRealm(new StrategyRealm(false));
        try {
            User judy = User.getById("Judy", true);
            judy.setFullName("Judy J");
            judy.save();
            String oldDir = judy.getUserFolder().getName();
            Field dirNames = User.class.getDeclaredField("DIRNAMES"); // its key, read once per JVM
            dirNames.setAccessible(true);
            String newDir = "judy_" + ((HMACConfidentialKey) dirNames.get(null)).mac("Judy"); // case-sensitive: not of "judy"
            // as setSecurityRealm does there: the users are moved, then the configuration saved
            other.move("users/" + oldDir, "users/" + newDir);
            writeElsewhere("config.xml", text("config.xml").replace("<caseSensitive>false</caseSensitive>", "<caseSensitive>true</caseSensitive>"));
            assertTrue(r.jenkins.getSecurityRealm().getUserIdStrategy() instanceof IdStrategy.CaseSensitive);
            assertEquals("Judy J", User.getById("Judy", false).getFullName());
        } finally {
            r.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION); // the configuration is stored for later tests
        }
    }

    /** A realm whose user ID strategy is configured. */
    public static class StrategyRealm extends AbstractPasswordBasedSecurityRealm {
        private final boolean caseSensitive;

        StrategyRealm(boolean caseSensitive) {
            this.caseSensitive = caseSensitive;
        }

        @Override
        public IdStrategy getUserIdStrategy() {
            return caseSensitive ? new IdStrategy.CaseSensitive() : IdStrategy.CASE_INSENSITIVE;
        }

        @Override
        protected UserDetails authenticate2(String username, String password) throws AuthenticationException {
            throw new BadCredentialsException(username);
        }

        @Override
        public UserDetails loadUserByUsername2(String username) throws UsernameNotFoundException {
            throw new UsernameNotFoundException(username);
        }

        @Override
        public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) throws UsernameNotFoundException {
            throw new UsernameNotFoundException(groupname);
        }

        @TestExtension
        public static class DescriptorImpl extends Descriptor<SecurityRealm> {}
    }

    @Test
    void aNodeVetoedHereIsNotLoadedWhenMadeElsewhere() throws Exception {
        r.createSlave("n3", null, null);
        writeElsewhere("nodes/vetoed/config.xml", text("nodes/n3/config.xml").replace("<name>n3</name>", "<name>vetoed</name>"));
        assertNull(r.jenkins.getNode("vetoed"));
        assertNull(r.jenkins.getComputer("vetoed"));
    }

    @TestExtension("aNodeVetoedHereIsNotLoadedWhenMadeElsewhere")
    public static class Veto extends NodeListener {
        @Override
        protected boolean allowLoad(Node node) {
            return !node.getNodeName().equals("vetoed");
        }
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

    /** A cloud agent, as a cloud plugin's (e.g. a Kubernetes pod) would be. */
    public static final class TestCloudAgent extends hudson.slaves.AbstractCloudSlave {
        TestCloudAgent(String name) throws Exception {
            super(name, "/tmp/" + name, new hudson.slaves.JNLPLauncher());
        }

        @Override
        public hudson.slaves.AbstractCloudComputer<?> createComputer() {
            return new hudson.slaves.AbstractCloudComputer<>(this);
        }

        @Override
        protected void _terminate(hudson.model.TaskListener listener) {}

        @TestExtension
        public static final class DescriptorImpl extends hudson.model.Slave.SlaveDescriptor {}
    }

    @Test
    void cloudAgentsStayWithTheirController() throws Exception {
        r.jenkins.addNode(new TestCloudAgent("pod1"));
        assertNull(STORE.read("nodes/pod1/config.xml"), "this controller's cloud agent is not shared");
        assertNotNull(r.jenkins.getNode("pod1"));

        // one written to the store by another controller (e.g. an older version) is not taken up here
        r.createSlave("n0", null, null);
        String cloudXml = text("nodes/n0/config.xml").replace("<name>n0</name>", "<name>pod2</name>")
                .replaceFirst("<slave>", "<jenkins.persistence.StaticStateApplierTest_-TestCloudAgent>")
                .replaceFirst("</slave>\\s*$", "</jenkins.persistence.StaticStateApplierTest_-TestCloudAgent>");
        writeElsewhere("nodes/pod2/config.xml", cloudXml);
        assertNull(r.jenkins.getNode("pod2"), "another controller's cloud agent is not launched here");

        r.jenkins.removeNode(r.jenkins.getNode("pod1"));
        assertNull(r.jenkins.getNode("pod1"));
    }

    @Test
    void aNodeDeletedElsewhereDoesNotComeBackFromACopyHere() throws Exception {
        r.createSlave("n5", null, null);
        File copy = new File(r.jenkins.getRootDir(), "nodes/n5/config.xml");
        Files.createDirectories(copy.toPath().getParent());
        Files.writeString(copy.toPath(), text("nodes/n5/config.xml"), StandardCharsets.UTF_8); // e.g. read from the store
        other.delete("nodes/n5/config.xml", PersistenceStore.ANY);
        awaitApplied();
        assertNull(r.jenkins.getNode("n5"));
        assertFalse(copy.getParentFile().exists(), "the copy here went with the node");
        r.jenkins.getNodesObject().load(); // as at the next startup
        assertNull(r.jenkins.getNode("n5"), "not brought back");
    }

    @Test
    void aCloudAgentOfThisControllerSurvivesItsRestart() throws Exception {
        r.jenkins.addNode(new TestCloudAgent("pod3"));
        PersistenceStores.keepLocal("nodes/pod3/config.xml", false); // what a restart forgets
        r.jenkins.getNodesObject().load();
        assertNotNull(r.jenkins.getNode("pod3"), "loaded from here, though the store never had it");
        assertTrue(PersistenceStores.isKeptLocal("nodes/pod3/config.xml"));

        r.jenkins.removeNode(r.jenkins.getNode("pod3"));
        assertFalse(PersistenceStores.isKeptLocal("nodes/pod3/config.xml"), "a node of that name later is shared again");
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
