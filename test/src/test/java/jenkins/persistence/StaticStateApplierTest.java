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

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore() {
        @Override
        public Entry read(String key) throws IOException {
            ReadHook hook = onRead;
            return hook == null ? super.read(key) : hook.read(key, () -> super.read(key));
        }

        @Override
        public long write(String key, byte[] content, long expectedVersion) throws IOException {
            if (key.equals(failWrite)) {
                failWrite = null;
                throw new java.io.UncheckedIOException(new java.io.IOException("the store is unreachable, this time"));
            }
            return super.write(key, content, expectedVersion);
        }
    };

    /** A key whose next write here fails. */
    static volatile String failWrite;

    /** Reads made here, which a test can delay or precede with a change made elsewhere. */
    interface ReadHook {
        PersistenceStore.Entry read(String key, Read read) throws IOException;
    }

    interface Read {
        PersistenceStore.Entry get() throws IOException;
    }

    static volatile ReadHook onRead;

    private static boolean calledFrom(Class<?> type, String methodPrefix) {
        return StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals(type.getName()) && f.getMethodName().startsWith(methodPrefix)));
    }

    private static boolean applierBlocked() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("Persistence store change applier") && t.getState() == Thread.State.BLOCKED);
    }

    private final LogRecorder logging = new LogRecorder();
    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        onRead = null;
        failWrite = null;
        r = rule;
        other = STORE.as("other-controller");
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ItemChangeApplier.isIdle());
    }

    private static String text(String key) throws IOException {
        return new String(STORE.read(key).content(), StandardCharsets.UTF_8);
    }

    private void writeElsewhere(String key, String content) throws Exception {
        other.write(key, content.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();
    }

    @Test
    void readingTheRawFileDoesNotHideAChangeMadeElsewhere() throws Exception {
        r.jenkins.save();
        String base = text("config.xml");
        long refused = PersistenceStores.getRefusedCount();
        synchronized (r.jenkins) {
            other.write("config.xml", base.replaceFirst("<scmCheckoutRetryCount>[0-9]+</scmCheckoutRetryCount>",
                    "<scmCheckoutRetryCount>5</scmCheckoutRetryCount>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            // e.g. GET config.xml over REST: the object in memory does not have that version
            new hudson.XmlFile(jenkins.model.Jenkins.XSTREAM, new java.io.File(r.jenkins.getRootDir(), "config.xml")).asString();
            assertThrows(ConflictException.class, () -> r.jenkins.setQuietPeriod(8), "the save still conflicts");
        }
        awaitApplied();
        assertEquals(refused + 1, PersistenceStores.getRefusedCount());
        String stored = text("config.xml");
        assertFalse(stored.contains("<quietPeriod>8</quietPeriod>"), stored);
        assertTrue(stored.contains("<scmCheckoutRetryCount>5</scmCheckoutRetryCount>"), stored);
    }

    @Test
    void aSaveOfTheGlobalConfigurationOverAChangeElsewhereIsRefusedAndReloaded() throws Exception {
        r.jenkins.save(); // what this controller last wrote
        String base = text("config.xml");
        int quietPeriod = r.jenkins.getQuietPeriod();
        synchronized (r.jenkins) { // the change from elsewhere cannot be applied here meanwhile (reloadGlobalConfig)
            other.write("config.xml", base.replaceFirst("<scmCheckoutRetryCount>[0-9]+</scmCheckoutRetryCount>",
                    "<scmCheckoutRetryCount>4</scmCheckoutRetryCount>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            assertThrows(ConflictException.class, () -> r.jenkins.setQuietPeriod(9));
        }
        awaitApplied();
        String stored = text("config.xml");
        assertFalse(stored.contains("<quietPeriod>9</quietPeriod>"), stored);
        assertTrue(stored.contains("<scmCheckoutRetryCount>4</scmCheckoutRetryCount>"), stored);
        assertEquals(4, r.jenkins.getScmCheckoutRetryCount(), "reloaded with the other change");
        assertEquals(quietPeriod, r.jenkins.getQuietPeriod(), "and without the refused one");
    }

    @Test
    void aDescriptorKeepingItsConfigurationElsewhereIsReloaded() throws Exception {
        Custom c = jenkins.model.GlobalConfiguration.all().get(Custom.class);
        c.value = "here";
        c.save();
        writeElsewhere("custom-descriptor.xml", text("custom-descriptor.xml").replace("here", "elsewhere"));
        assertEquals("elsewhere", c.value);
    }

    @Test
    void aDescriptorChangedElsewhereIsReloadedThroughItsOwnLoad() throws Exception {
        Migrating d = GlobalConfiguration.all().get(Migrating.class);
        d.value = "here";
        d.save();
        String key = PersistenceStores.keyFor(Descriptor.configFileOf(d).getPath());
        // written by a controller with an older version of the plugin: in the old field, which load() migrates
        writeElsewhere(key, text(key).replaceAll("<value>[^<]*</value>", "<legacy>elsewhere</legacy>"));
        assertEquals("ELSEWHERE", d.value);
        assertNull(d.legacy);
    }

    @Test
    void aDescriptorReloadedAfterARefusedSaveIsReloadedThroughItsOwnLoad() throws Exception {
        Migrating d = GlobalConfiguration.all().get(Migrating.class);
        d.value = "here";
        d.save();
        String key = PersistenceStores.keyFor(Descriptor.configFileOf(d).getPath());
        // changed meanwhile, and not applied here yet (written as this controller: no event for it here)
        STORE.write(key, text(key).replaceAll("<value>[^<]*</value>", "<legacy>stored</legacy>").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        d.value = "mine";
        d.save(); // refused (Descriptor.save logs it), and reloaded
        for (int i = 0; i < 500 && !"STORED".equals(d.value); i++) {
            Thread.sleep(10);
        }
        assertEquals("STORED", d.value);
        assertNull(d.legacy);
    }

    @TestExtension({"aDescriptorChangedElsewhereIsReloadedThroughItsOwnLoad", "aDescriptorReloadedAfterARefusedSaveIsReloadedThroughItsOwnLoad"})
    public static final class Migrating extends GlobalConfiguration {
        String value;
        /** Where an older version kept {@link #value}; migrated on load. */
        String legacy;

        public Migrating() {
            load();
        }

        @Override
        public synchronized void load() {
            super.load();
            if (legacy != null) {
                value = legacy.toUpperCase(java.util.Locale.ROOT);
                legacy = null;
            }
        }
    }

    @Test
    void aNodeReloadedAfterARefusedSaveRebindsItsComputer() throws Exception {
        Node n1 = r.createSlave("n1", "old-label", null);
        assertEquals(1, n1.toComputer().getNumExecutors());
        // changed meanwhile, and not applied here yet (written as this controller: no event for it here)
        STORE.write("nodes/n1/config.xml", text("nodes/n1/config.xml").replace("<numExecutors>1</numExecutors>", "<numExecutors>3</numExecutors>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        assertThrows(ConflictException.class, n1::save);
        for (int i = 0; i < 500 && (r.jenkins.getComputer("n1") == null || r.jenkins.getComputer("n1").getNumExecutors() != 3); i++) {
            Thread.sleep(10);
        }
        Node reloaded = r.jenkins.getNode("n1");
        assertEquals(3, reloaded.getNumExecutors());
        assertEquals(3, r.jenkins.getComputer("n1").getNumExecutors(), "its computer has the stored executors");
        assertEquals(reloaded, reloaded.toComputer().getNode(), "bound to the node as reloaded");
    }

    @TestExtension("aDescriptorKeepingItsConfigurationElsewhereIsReloaded")
    public static final class Custom extends jenkins.model.GlobalConfiguration {
        String value;

        public Custom() {
            load();
        }

        @Override
        protected hudson.XmlFile getConfigFile() {
            return new hudson.XmlFile(jenkins.model.Jenkins.XSTREAM, new java.io.File(jenkins.model.Jenkins.get().getRootDir(), "custom-descriptor.xml"));
        }
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
    void staticStateWhoseEventsWereLostIsAppliedByTheCatchUp() throws Exception {
        r.jenkins.setSystemMessage("set here");
        User lost = User.getById("lostuser", true);
        lost.save();
        String lostKey = STORE.children("users").stream().filter(d -> d.startsWith("lostuser_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        r.createSlave("lost-node", null, null);
        Method folder = User.class.getDeclaredMethod("getUserFolderNameFor", String.class);
        folder.setAccessible(true);
        String madeKey = "users/" + folder.invoke(null, "madeuser") + "/config.xml";
        STORE.setLosingEvents(true);
        try {
            other.write("config.xml", text("config.xml").replace("set here", "set elsewhere").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            other.write(madeKey, text(lostKey).replace("<id>lostuser</id>", "<id>madeuser</id>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
            other.delete(lostKey, PersistenceStore.ANY);
            other.delete("nodes/lost-node/config.xml", PersistenceStore.ANY);
        } finally {
            STORE.setLosingEvents(false);
        }
        awaitApplied();
        assertEquals("set here", r.jenkins.getSystemMessage(), "nothing arrived");
        ItemChangeApplier.catchUpNow();
        awaitApplied();
        assertEquals("set elsewhere", r.jenkins.getSystemMessage());
        assertNotNull(User.getById("madeuser", false), "made elsewhere");
        assertNull(User.getById("lostuser", false), "deleted elsewhere");
        assertNull(r.jenkins.getNode("lost-node"), "deleted elsewhere");
    }

    @Test
    void aUserOrNodeDeletedElsewhereAndThenHereIsDeleted() throws Exception {
        User twice = User.getById("deletedtwice", true);
        twice.save();
        String key = STORE.children("users").stream().filter(d -> d.startsWith("deletedtwice_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        Node node = r.createSlave("deletedtwice", null, null);
        STORE.setLosingEvents(true); // not applied here (yet)
        try {
            other.delete(key, PersistenceStore.ANY);
            other.delete("nodes/deletedtwice/config.xml", PersistenceStore.ANY);
        } finally {
            STORE.setLosingEvents(false);
        }
        twice.delete();
        assertNull(User.getById("deletedtwice", false));
        r.jenkins.removeNode(node);
        assertNull(r.jenkins.getNode("deletedtwice"));
        assertFalse(new File(r.jenkins.getRootDir(), "nodes/deletedtwice").exists());
    }

    @Test
    void theExecutorsFollowTheirNumberWhenItsSaveFails() throws Exception {
        r.jenkins.setNumExecutors(2);
        failWrite = "config.xml";
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> r.jenkins.setNumExecutors(3));
        assertNull(failWrite, "the save failed");
        assertEquals(3, r.jenkins.getNumExecutors());
        assertEquals(3, r.jenkins.toComputer().getNumExecutors(), "computers updated all the same");
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
    void anUnmodifiableCollectionClearedElsewhereIsClearedHere() throws Exception {
        Listing listing = GlobalConfiguration.all().get(Listing.class);
        listing.names = com.google.common.collect.ImmutableList.of("a", "b"); // cannot be emptied in place
        listing.save();
        String key = PersistenceStores.keyFor(Descriptor.configFileOf(listing).getPath());
        // saved elsewhere with the list set to null: no element for it
        writeElsewhere(key, text(key).replaceAll("(?s)<names[ >].*?</names>", "").replace("<other>here</other>", "<other>elsewhere</other>"));
        assertEquals("elsewhere", listing.other);
        assertTrue(listing.names.isEmpty(), "removed elsewhere, so not kept here: " + listing.names);
    }

    @TestExtension("anUnmodifiableCollectionClearedElsewhereIsClearedHere")
    public static final class Listing extends GlobalConfiguration {
        List<String> names = List.of();
        String other = "here";

        public Listing() {
            load();
        }
    }

    @Test
    void aUserWhoseStoredContentCannotBeLoadedIsLeftAsItWas() throws Exception {
        User ivy = User.getById("ivy", true);
        ivy.setFullName("Ivy Here");
        ivy.save();
        int properties = ivy.getAllProperties().size();
        assertTrue(properties > 0);
        // a corrupt entry, say: loading it fails, before the user is touched (its properties would be gone, and a save of
        // it then store it without them)
        assertThrows(IOException.class, () -> ivy.loadStored("<?xml version='1.1' encoding='UTF-8'?><user><id>ivy</id><fullName>Ivy Elsewhere</fullName><properties><hudson.tasks"
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals("Ivy Here", ivy.getFullName());
        assertEquals(properties, ivy.getAllProperties().size());
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
    void aUserChangeElsewhereWhoseSecondReadFailsIsAppliedByTheRetry() throws Exception {
        User kim = User.getById("kim", true);
        kim.setFullName("Kim One");
        kim.save();
        String kimKey = STORE.children("users").stream().filter(d -> d.startsWith("kim_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        int[] reads = {0};
        onRead = (k, read) -> {
            if (k.equals(kimKey) && Thread.currentThread().getName().equals("Persistence store change applier") && ++reads[0] == 2) {
                onRead = null;
                throw new UncheckedIOException(new IOException("store unreachable"));
            }
            return read.get();
        };
        other.write(kimKey, text(kimKey).replace("Kim One", "Kim Elsewhere").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        for (int i = 0; i < 1000 && !"Kim Elsewhere".equals(kim.getFullName()); i++) {
            Thread.sleep(10); // the first attempt may fail, then the retry applies it
        }
        awaitApplied();
        assertEquals("Kim Elsewhere", User.getById("kim", false).getFullName());
        kim.setDescription("about Kim");
        kim.save();
        assertTrue(text(kimKey).contains("Kim Elsewhere"), "a save here keeps the change made elsewhere");
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

    private static Set<String> storedUsers(String... prefixes) throws IOException {
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
            other.move("users/" + oldDir, "users/" + newDir, java.util.List.of());
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
    void aUserSavedHereAfterItsDeletionElsewhereIsRefusedAndTheDeletionApplied() throws Exception {
        User dave = User.getById("dave", true);
        dave.save();
        String daveKey = STORE.children("users").stream().filter(d -> d.startsWith("dave_")).map(d -> "users/" + d + "/config.xml").findFirst().orElseThrow();
        other.write("blocker.xml", new byte[] {'x'}, PersistenceStore.ANY); // holds the applier until released
        Blocker.holding.await();
        other.delete(daveKey, PersistenceStore.ANY);
        dave.setFullName("Dave Again");
        assertThrows(ConflictException.class, dave::save, "after the deletion elsewhere, which it would overwrite");
        Blocker.release.countDown();
        awaitApplied();
        assertNull(STORE.read(daveKey), "not stored again");
        assertNull(User.getById("dave", false), "the deletion is applied");
    }

    @TestExtension("aUserSavedHereAfterItsDeletionElsewhereIsRefusedAndTheDeletionApplied")
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
    void aUserChangedInMemoryAndNeverSavedStillGetsChangesMadeElsewhere() throws Exception {
        User carol = User.getById("carol", true);
        carol.setFullName("Carol");
        carol.save();
        Method folder = User.class.getDeclaredMethod("getUserFolderNameFor", String.class);
        folder.setAccessible(true);
        String key = "users/" + folder.invoke(null, "carol") + "/config.xml";
        // changed here and never saved (as JCasC re-hashing a declared password does on each controller)
        carol.setDescription("changed here, not saved");
        other.write(key, text(key).replace("<fullName>Carol</fullName>", "<fullName>Carol Elsewhere</fullName>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        for (int i = 0; i < 300 && !"Carol Elsewhere".equals(User.getById("carol", false).getFullName()); i++) {
            Thread.sleep(100);
        }
        assertEquals("Carol Elsewhere", User.getById("carol", false).getFullName(), "applied after being put off a few times");
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
    void askingForANodesFileDoesNotRouteIt() throws Exception {
        TestCloudAgent pod = new TestCloudAgent("pod-asked");
        r.jenkins.addNode(pod);
        assertTrue(StoreRouting.isKeptLocal("nodes/pod-asked/config.xml"), "kept local once it is this controller's");
        r.jenkins.removeNode(pod);
        assertFalse(StoreRouting.isKeptLocal("nodes/pod-asked/config.xml"));
        r.jenkins.getNodesObject().getConfigFile(pod);
        assertFalse(StoreRouting.isKeptLocal("nodes/pod-asked/config.xml"), "a getter changes nothing");
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
    void aNodeOrUserNamedByNoFileNameIsNotApplied() throws Exception {
        File home = r.jenkins.getRootDir();
        File marker = new File(home, "marker");
        Files.writeString(marker.toPath(), "here", StandardCharsets.UTF_8);
        // keys no controller writes: ".." is JENKINS_HOME itself, whose deletion would take everything
        other.write("nodes/../config.xml", "<slave/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        other.delete("nodes/../config.xml", PersistenceStore.ANY);
        awaitApplied();
        r.jenkins.getNodesObject().changedElsewhere("..", true);
        r.jenkins.getNodesObject().changedElsewhere(".", true);
        User.changedElsewhere("..", true);
        assertTrue(marker.isFile(), "JENKINS_HOME is left alone");
    }

    @Test
    void aPermanentNodeReplacingACloudAgentOfItsNameIsShared() throws Exception {
        r.jenkins.addNode(new TestCloudAgent("pod5"));
        assertNull(STORE.read("nodes/pod5/config.xml"));
        r.jenkins.addNode(new hudson.slaves.DumbSlave("pod5", "/tmp/pod5", new hudson.slaves.JNLPLauncher())); // through the API
        assertNotNull(STORE.read("nodes/pod5/config.xml"), "shared, as any permanent node");
        assertFalse(StoreRouting.isKeptLocal("nodes/pod5/config.xml"));
    }

    @Test
    void aNodeDeletedElsewhereLeavesThisControllersCloudAgentOfItsName() throws Exception {
        r.jenkins.addNode(new TestCloudAgent("pod6"));
        r.createSlave("n6", null, null);
        writeElsewhere("nodes/pod6/config.xml", text("nodes/n6/config.xml").replace("<name>n6</name>", "<name>pod6</name>"));
        other.delete("nodes/pod6/config.xml", PersistenceStore.ANY); // another controller's permanent node of that name
        awaitApplied();
        assertNotNull(r.jenkins.getNode("pod6"));
        assertTrue(new File(r.jenkins.getRootDir(), "nodes/pod6/config.xml").isFile(), "its configuration stays here");
    }

    @Test
    void aCloudAgentOfThisControllerSurvivesItsRestart() throws Exception {
        r.jenkins.addNode(new TestCloudAgent("pod3"));
        StoreRouting.keepLocal("nodes/pod3/config.xml", false); // what a restart forgets
        r.jenkins.getNodesObject().load();
        assertNotNull(r.jenkins.getNode("pod3"), "loaded from here, though the store never had it");
        assertTrue(StoreRouting.isKeptLocal("nodes/pod3/config.xml"));

        r.jenkins.removeNode(r.jenkins.getNode("pod3"));
        assertFalse(StoreRouting.isKeptLocal("nodes/pod3/config.xml"), "a node of that name later is shared again");
    }

    @Test
    void anEmptyNodeDirectoryLeftHereDoesNotHideTheNode() throws Exception {
        assertTrue(new File(r.jenkins.getRootDir(), "nodes/ghost").mkdirs()); // e.g. left by a node deleted elsewhere
        r.jenkins.getNodesObject().load();
        assertFalse(StoreRouting.isKeptLocal("nodes/ghost/config.xml"));
        r.createSlave("n1", null, null);
        writeElsewhere("nodes/ghost/config.xml", text("nodes/n1/config.xml").replace("<name>n1</name>", "<name>ghost</name>"));
        assertNotNull(r.jenkins.getNode("ghost"), "a node made elsewhere under that name is seen here");
    }

    @Test
    void anotherControllersCloudAgentIsNotLaunchedForADirectoryHere() throws Exception {
        r.createSlave("n2", null, null);
        String cloudXml = text("nodes/n2/config.xml").replace("<name>n2</name>", "<name>pod4</name>")
                .replaceFirst("<slave>", "<jenkins.persistence.StaticStateApplierTest_-TestCloudAgent>")
                .replaceFirst("</slave>\\s*$", "</jenkins.persistence.StaticStateApplierTest_-TestCloudAgent>");
        writeElsewhere("nodes/pod4/config.xml", cloudXml);
        new File(r.jenkins.getRootDir(), "nodes/pod4").mkdirs(); // a stray directory of that name here
        r.jenkins.getNodesObject().load();
        assertNull(r.jenkins.getNode("pod4"), "another controller's cloud agent is not launched here");
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
