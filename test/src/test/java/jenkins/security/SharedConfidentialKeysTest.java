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

package jenkins.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import hudson.Functions;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import jenkins.persistence.ConflictException;
import jenkins.persistence.InMemoryPersistenceStore;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Confidential keys first used at runtime are shared through the persistence store; the first controller to use one decides its value.
 */
@WithJenkins
class SharedConfidentialKeysTest {

    /** The key whose first read finds it absent while another controller creates it, and that controller's value. */
    static volatile String racing;
    static volatile byte[] theirs;
    static volatile boolean storesKeys = true;

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> false) {
        @Override
        public boolean storesConfidentialKeys() {
            return storesKeys;
        }

        @Override
        public Entry read(String key) {
            Entry e = super.read(key);
            if (e == null && key.equals(racing)) {
                racing = null;
                try {
                    as("other-controller").write(key, theirs, PersistenceStore.ABSENT); // just after this read
                } catch (ConflictException x) {
                    throw new AssertionError(x);
                }
            }
            return e;
        }
    };

    private static File localFile(JenkinsRule r, String id) {
        return new File(r.jenkins.getRootDir(), "secrets/" + id);
    }

    @Test
    void keyFirstUsedHereIsPublished(JenkinsRule r) {
        HMACConfidentialKey k = new HMACConfidentialKey("test.shared.k1");
        String mac = k.mac("x");
        assertNotNull(STORE.read("secrets/test.shared.k1"), "published");
        assertTrue(localFile(r, "test.shared.k1").isFile(), "kept locally too");
        // as on a controller that has not seen it locally
        assertEquals(mac, new HMACConfidentialKey("test.shared.k1").mac("x"));
    }

    @Test
    void keyKnownOnlyToTheStoreIsUsed(JenkinsRule r) throws Exception {
        String mac = new HMACConfidentialKey("test.shared.k2").mac("x");
        Files.delete(localFile(r, "test.shared.k2").toPath()); // a fresh home
        assertEquals(mac, new HMACConfidentialKey("test.shared.k2").mac("x"), "same value from the store");
    }

    @Test
    void keyCreatedElsewhereFirstWins(JenkinsRule r) throws Exception {
        CryptoConfidentialKey mine = new CryptoConfidentialKey("test.shared.k3");
        byte[] reference = mine.encrypt().doFinal("hello".getBytes());
        // another controller created k4 first, with the same value as k3
        STORE.as("other-controller").write("secrets/test.shared.k4", STORE.read("secrets/test.shared.k3").content(), PersistenceStore.ABSENT);
        CryptoConfidentialKey theirs = new CryptoConfidentialKey("test.shared.k4");
        assertArrayEquals(reference, theirs.encrypt().doFinal("hello".getBytes()), "uses the value created elsewhere");
    }

    @Test
    void localKeysAreReconciledWithTheStore(JenkinsRule r) throws Exception {
        new HexStringConfidentialKey("test.shared.k5", 32).get();
        STORE.delete("secrets/test.shared.k5", PersistenceStore.ANY); // e.g. created before the store was available
        DefaultConfidentialStore.reconcile();
        assertArrayEquals(Files.readAllBytes(localFile(r, "test.shared.k5").toPath()), STORE.read("secrets/test.shared.k5").content());
    }

    @Test
    void keyCreatedElsewhereBetweenReadingAndWritingWins(JenkinsRule r) throws Exception {
        CryptoConfidentialKey reference = new CryptoConfidentialKey("test.shared.k6");
        byte[] expected = reference.encrypt().doFinal("hello".getBytes());
        theirs = STORE.read("secrets/test.shared.k6").content(); // the other controller's value: the same as k6
        racing = "secrets/test.shared.k7";
        CryptoConfidentialKey k7 = new CryptoConfidentialKey("test.shared.k7");
        assertArrayEquals(expected, k7.encrypt().doFinal("hello".getBytes()), "the value created first, elsewhere");
        assertArrayEquals(theirs, Files.readAllBytes(localFile(r, "test.shared.k7").toPath()), "kept locally");
    }

    @Test
    void onlyKeysAreShared(JenkinsRule r) throws Exception {
        Files.writeString(localFile(r, "initialAdminPassword").toPath(), "letmein"); // plain text, as the setup wizard writes
        Files.createDirectories(localFile(r, "some.d").toPath());
        new HexStringConfidentialKey("test.shared.k8", 32).get();
        STORE.delete("secrets/test.shared.k8", PersistenceStore.ANY);

        DefaultConfidentialStore.reconcile();

        assertNull(STORE.read("secrets/initialAdminPassword"));
        assertNull(STORE.read("secrets/some.d"));
        assertNotNull(STORE.read("secrets/test.shared.k8"), "a key still is");
    }

    @Test
    void aTruncatedKeyInTheStoreFallsBackToALocalOne(JenkinsRule r) throws Exception {
        // 15 bytes: not a whole number of cipher blocks
        STORE.as("other-controller").write("secrets/test.shared.truncated", new byte[15], PersistenceStore.ABSENT);
        LogRecorder logging = new LogRecorder().record(DefaultConfidentialStore.class, Level.WARNING).capture(10);
        String mac = new HMACConfidentialKey("test.shared.truncated").mac("x");
        assertTrue(logging.getMessages().stream().anyMatch(m -> m.contains("Cannot decrypt test.shared.truncated")), logging.getMessages().toString());
        assertEquals(mac, new HMACConfidentialKey("test.shared.truncated").mac("x"), "a local key, kept");
    }

    @Test
    void linksAreNotShared(JenkinsRule r, @TempDir Path elsewhere) throws Exception {
        assumeFalse(Functions.isWindows(), "symbolic links need a privilege on Windows");
        Path target = Files.writeString(elsewhere.resolve("file"), "outside");
        Files.createSymbolicLink(localFile(r, "linked").toPath(), target);

        DefaultConfidentialStore.reconcile();

        assertNull(STORE.read("secrets/linked"));
    }

    @Test
    void aStoreNotOptingInGetsNoKeys(JenkinsRule r) throws Exception {
        storesKeys = false;
        try {
            String mac = new HMACConfidentialKey("test.shared.k9").mac("x");
            new HexStringConfidentialKey("test.shared.k10", 32).get();
            DefaultConfidentialStore.reconcile();
            assertNull(STORE.read("secrets/test.shared.k9"), "not created in the store");
            assertNull(STORE.read("secrets/test.shared.k10"), "nor published by reconciling");
            assertEquals(mac, new HMACConfidentialKey("test.shared.k9").mac("x"), "kept locally, as without a store");
        } finally {
            storesKeys = true;
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
