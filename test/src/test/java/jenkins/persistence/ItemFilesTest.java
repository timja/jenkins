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

import hudson.model.FreeStyleProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Opt-in sharing of plugins' own files in item and user directories (Target 15). */
@WithJenkins
class ItemFilesTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> (key.startsWith("jobs/") || key.startsWith("users/")) && key.endsWith("/config.xml") && !key.contains("/builds/"));
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore() {
        @Override
        public java.io.InputStream get(String key) {
            if (key.equals(failGet)) {
                failGet = null;
                throw new java.io.UncheckedIOException(new java.io.IOException("the blob store is unreachable, this time"));
            }
            java.util.function.Consumer<String> hook = beforeGet;
            if (hook != null) {
                hook.accept(key);
            }
            return super.get(key);
        }

        @Override
        public void put(String key, java.io.InputStream content, long length) throws java.io.IOException {
            java.util.function.Consumer<String> hook = beforePut;
            if (hook != null) {
                hook.accept(key);
            }
            super.put(key, content, length);
        }
    };
    /** A blob whose next read fails. */
    static volatile String failGet;
    /** While set, run as a blob is read or stored, before it is. */
    static volatile java.util.function.Consumer<String> beforeGet, beforePut;

    @BeforeEach
    void enable() {
        System.setProperty(ItemFiles.class.getName() + ".enabled", "true");
    }

    @AfterEach
    void disable() {
        System.clearProperty(ItemFiles.class.getName() + ".enabled");
        beforeGet = null;
        beforePut = null;
    }

    @Test
    void aPluginsFileIsSharedAndTakenElsewhere(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Path state = p.getRootDir().toPath().resolve("my-plugin-state.txt");
        Files.writeString(state, "v1");
        ItemFiles.publish(p);
        assertNotNull(STORE.read("jobs/p/item-files.json"), "a manifest");
        assertFalse(new String(STORE.read("jobs/p/item-files.json").content(), StandardCharsets.UTF_8).contains("config.xml"),
                "what the store has already is left out");
        // a fresh copy of the directory, as on another controller
        Files.delete(state);
        Files.delete(p.getRootDir().toPath().resolve(ItemFiles.KNOWN));
        ItemFiles.forgetForTests();
        ItemFiles.take(p);
        assertEquals("v1", Files.readString(state));
        // changed elsewhere: taken here, where it did not change
        String manifest = new String(STORE.read("jobs/p/item-files.json").content(), StandardCharsets.UTF_8);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        String sha2 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v2));
        BLOBS.put("jobs/p/item-files/" + sha2, new java.io.ByteArrayInputStream(v2), v2.length);
        // written with this controller's origin, so the store listener does not take it too, racing with the steps below
        STORE.write("jobs/p/item-files.json",
                manifest.replaceAll("\"my-plugin-state.txt\":\"[0-9a-f]+\"", "\"my-plugin-state.txt\":\"" + sha2 + "\"")
                        .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        ItemFiles.take(p);
        assertEquals("v2", Files.readString(state));
        // a change made here and not shared yet is not overwritten by what is shared
        Files.writeString(state, "v3");
        ItemFiles.take(p);
        assertEquals("v3", Files.readString(state));
        ItemFiles.publish(p);
        assertTrue(new String(STORE.read("jobs/p/item-files.json").content(), StandardCharsets.UTF_8)
                .contains(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("v3".getBytes(StandardCharsets.UTF_8)))),
                "and is shared next");
        Files.delete(state);
        ItemFiles.publish(p);
        assertFalse(new String(STORE.read("jobs/p/item-files.json").content(), StandardCharsets.UTF_8).contains("my-plugin-state"),
                "removed here: removed from the manifest");
    }

    @Test
    void aPluginsFileInAUserDirectoryIsSharedAndTakenElsewhere(JenkinsRule r) throws Exception {
        hudson.model.User alice = hudson.model.User.getById("alice", true);
        alice.save();
        java.io.File dir = alice.getUserFolder();
        // as the avatar plugin writes its image, next to the user's config.xml
        Path avatar = dir.toPath().resolve("avatar.png");
        Files.write(avatar, new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        Files.writeString(dir.toPath().resolve("apiTokenStats.xml"), "<stats/>");
        new ItemFiles.Publisher().execute(hudson.model.TaskListener.NULL);
        String key = PersistenceStores.keyFor(dir) + "/item-files.json";
        String manifest = new String(STORE.read(key).content(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("avatar.png"), manifest);
        assertFalse(manifest.contains("apiTokenStats.xml"), "each controller's own token usage is not shared");
        assertFalse(manifest.contains("config.xml"), "what the store has already is left out");
        // a fresh copy of the directory, as on another controller, told of the manifest written elsewhere
        Files.delete(avatar);
        Files.delete(dir.toPath().resolve(ItemFiles.KNOWN));
        ItemFiles.forgetForTests();
        ItemFiles.changedElsewhere(key);
        assertEquals(7, Files.size(avatar));
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String manifest(String key) {
        PersistenceStore.Entry e = STORE.read(key);
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8);
    }

    @PerController
    public static final class Counts {}

    @Test
    void onlyFilesSharedThisWayAreSharedOrTaken(JenkinsRule r, @TempDir Path elsewhere) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("q");
        Path root = p.getRootDir().toPath();
        PersistenceStores.declare(root.resolve("stats.xml").toFile(), new Counts());
        Files.writeString(root.resolve("stats.xml"), "<counts/>");
        Files.writeString(root.resolve("shared.txt"), "s");
        ItemFiles.publish(p);
        assertTrue(manifest("jobs/q/item-files.json").contains("shared.txt"));
        assertFalse(manifest("jobs/q/item-files.json").contains("stats.xml"), "kept per controller");

        // a manifest naming files not shared this way is not taken for them
        byte[] other = "from elsewhere".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(other);
        BLOBS.put("jobs/q/item-files/" + sha, new java.io.ByteArrayInputStream(other), other.length);
        boolean links = !hudson.Functions.isWindows();
        if (links) {
            Files.createSymbolicLink(root.resolve("linked"), elsewhere);
        }
        net.sf.json.JSONObject m = new net.sf.json.JSONObject();
        for (String path : java.util.List.of("ok.txt", "config.xml", "builds/1/build.xml", "../escaped.txt", "stats.xml", "linked/x.txt")) {
            m.put(path, sha);
        }
        STORE.write("jobs/q/item-files.json", m.toString().getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        ItemFiles.take(p);
        assertEquals("from elsewhere", Files.readString(root.resolve("ok.txt")), "a file shared this way is taken");
        assertFalse(Files.exists(root.resolve("config.xml")) && Files.readString(root.resolve("config.xml")).equals("from elsewhere"),
                "not its configuration");
        assertFalse(Files.exists(root.resolve("builds/1/build.xml")), "nor a build's");
        assertFalse(Files.exists(root.resolve("../escaped.txt")));
        assertEquals("<counts/>", Files.readString(root.resolve("stats.xml")), "nor a file kept per controller");
        assertFalse(Files.exists(elsewhere.resolve("x.txt")), "nor through a link");
    }

    @Test
    void theFilesOfADeletedItemGoWithIt(JenkinsRule r) throws Exception {
        FreeStyleProject a = r.createFreeStyleProject("a");
        Files.writeString(a.getRootDir().toPath().resolve("a.txt"), "a");
        ItemFiles.publish(a);
        assertNotNull(manifest("jobs/a/item-files.json"));
        a.delete();
        assertNull(manifest("jobs/a/item-files.json"));
        assertNull(BLOBS.get("jobs/a/item-files/" + sha256("a".getBytes(StandardCharsets.UTF_8))));
        // its name is free, for an item renamed to it, which takes none of its files
        FreeStyleProject b = r.createFreeStyleProject("b");
        b.renameTo("a");
        ItemFiles.take(b);
        assertFalse(Files.exists(b.getRootDir().toPath().resolve("a.txt")));
    }

    @Test
    void whatWasSharedIsKnownAfterARestart(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("restarted");
        Path state = p.getRootDir().toPath().resolve("state.txt");
        Files.writeString(state, "v1");
        ItemFiles.publish(p);
        assertFalse(manifest("jobs/restarted/item-files.json").contains(ItemFiles.KNOWN), "kept here only");
        ItemFiles.forgetForTests(); // a restart, with this home
        // meanwhile another controller shared v2
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        BLOBS.put("jobs/restarted/item-files/" + sha256(v2), new java.io.ByteArrayInputStream(v2), v2.length);
        // (with this controller's origin, so the store listener does not take it too, racing with the steps below)
        STORE.write("jobs/restarted/item-files.json",
                manifest("jobs/restarted/item-files.json").replace(sha256("v1".getBytes(StandardCharsets.UTF_8)), sha256(v2)).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ItemFiles.take(p);
        assertEquals("v2", Files.readString(state), "the old copy here is not taken for a change made here");
        long version = STORE.read("jobs/restarted/item-files.json").version();
        ItemFiles.publish(p);
        assertEquals(version, STORE.read("jobs/restarted/item-files.json").version(), "nor shared over v2");
    }

    @Test
    void aChangeThatCouldNotBeTakenIsTakenLater(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("retried");
        Path state = p.getRootDir().toPath().resolve("state.txt");
        Files.writeString(state, "v1");
        ItemFiles.publish(p);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        BLOBS.put("jobs/retried/item-files/" + sha256(v2), new java.io.ByteArrayInputStream(v2), v2.length);
        failGet = "jobs/retried/item-files/" + sha256(v2);
        // (with this controller's origin, so the store listener does not take it too; it is applied below as if elsewhere)
        STORE.write("jobs/retried/item-files.json",
                manifest("jobs/retried/item-files.json").replace(sha256("v1".getBytes(StandardCharsets.UTF_8)), sha256(v2)).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ItemFiles.changedElsewhere("jobs/retried/item-files.json");
        assertNull(failGet, "it could not be taken");
        assertEquals("v1", Files.readString(state));
        new ItemFiles.Publisher().execute(hudson.model.TaskListener.NULL);
        assertEquals("v2", Files.readString(state), "taken with the next publication");
    }

    @Test
    void aFileRewrittenWhileItIsSharedIsNotStoredUnderTheChecksumOfWhatWasScanned(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("rewritten");
        Path state = p.getRootDir().toPath().resolve("state.txt");
        Files.writeString(state, "v1");
        beforePut = key -> {
            try {
                Files.writeString(state, "v2, written by the plugin meanwhile");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        ItemFiles.publish(p);
        beforePut = null;
        String sha1 = sha256("v1".getBytes(StandardCharsets.UTF_8));
        try (java.io.InputStream in = BLOBS.get("jobs/rewritten/item-files/" + sha1)) {
            assertNotNull(in);
            assertEquals(sha1, sha256(in.readAllBytes()), "the blob holds what its name says");
        }
        ItemFiles.publish(p);
        assertTrue(manifest("jobs/rewritten/item-files.json").contains(sha256("v2, written by the plugin meanwhile".getBytes(StandardCharsets.UTF_8))),
                "the new content is shared next");
    }

    @Test
    void ofTwoTakesAtOnceTheNewerManifestStays(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("overlapping");
        Path state = p.getRootDir().toPath().resolve("state.txt");
        Files.writeString(state, "v1");
        ItemFiles.publish(p);
        String v1 = manifest("jobs/overlapping/item-files.json");
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = "v3".getBytes(StandardCharsets.UTF_8);
        BLOBS.put("jobs/overlapping/item-files/" + sha256(v2), new java.io.ByteArrayInputStream(v2), v2.length);
        BLOBS.put("jobs/overlapping/item-files/" + sha256(v3), new java.io.ByteArrayInputStream(v3), v3.length);
        String oldSha = sha256("v1".getBytes(StandardCharsets.UTF_8));
        // (with this controller's origin, so the store listener does not take them too; they are taken below as if elsewhere)
        STORE.write("jobs/overlapping/item-files.json", v1.replace(oldSha, sha256(v2)).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        String v2Blob = "jobs/overlapping/item-files/" + sha256(v2);
        beforeGet = key -> {
            if (key.equals(v2Blob)) {
                reading.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Thread first = new Thread(() -> ItemFiles.changedElsewhere("jobs/overlapping/item-files.json"));
        first.start();
        assertTrue(reading.await(30, java.util.concurrent.TimeUnit.SECONDS), "the take of v2 reads its blob");
        STORE.write("jobs/overlapping/item-files.json", v1.replace(oldSha, sha256(v3)).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        Thread second = new Thread(() -> ItemFiles.changedElsewhere("jobs/overlapping/item-files.json"));
        second.start();
        // the take of v3 waits for the one of v2 (or, were they not made one at a time, finishes first)
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (second.isAlive() && second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        release.countDown();
        first.join(30_000);
        second.join(30_000);
        assertEquals("v3", Files.readString(state), "the newer manifest's file");
        long version = STORE.read("jobs/overlapping/item-files.json").version();
        ItemFiles.publish(p);
        assertEquals(version, STORE.read("jobs/overlapping/item-files.json").version(), "and its baseline: nothing to share back");
    }

    @Test
    void theFilesOfADeletedUserGoWithIt(JenkinsRule r) throws Exception {
        hudson.model.User bob = hudson.model.User.getById("bob", true);
        bob.save();
        java.io.File dir = bob.getUserFolder();
        Files.writeString(dir.toPath().resolve("avatar.png"), "bob's");
        new ItemFiles.Publisher().execute(hudson.model.TaskListener.NULL);
        String key = PersistenceStores.keyFor(dir);
        assertNotNull(manifest(key + "/item-files.json"));
        bob.delete();
        assertNull(manifest(key + "/item-files.json"));
        assertNull(BLOBS.get(key + "/item-files/" + sha256("bob's".getBytes(StandardCharsets.UTF_8))));
        // someone else given the id later, in the same directory, takes none of them
        hudson.model.User other = hudson.model.User.getById("bob", true);
        other.save();
        assertEquals(dir, other.getUserFolder());
        ItemFiles.take(other.getUserFolder());
        assertFalse(Files.exists(dir.toPath().resolve("avatar.png")));
    }

    @Test
    void anItemCreatedElsewhereTakesItsFilesSharedBeforeItWasLoadedHere(JenkinsRule r) throws Exception {
        byte[] content = "made elsewhere".getBytes(StandardCharsets.UTF_8);
        BLOBS.put("jobs/late/item-files/" + sha256(content), new java.io.ByteArrayInputStream(content), content.length);
        // the manifest applied before the item is loaded here (with this controller's origin: as if applied already, with
        // no item to take it for)
        STORE.write("jobs/late/item-files.json", ("{\"state.txt\":\"" + sha256(content) + "\"}").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        STORE.as("other-controller").write("jobs/late/config.xml",
                "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        Path state = r.jenkins.getRootDir().toPath().resolve("jobs/late/state.txt");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(state) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertNotNull(r.jenkins.getItem("late"));
        assertEquals("made elsewhere", Files.readString(state));
    }

    @Test
    void filesThatCouldNotBeTakenAtStartupAreTakenLater(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("booted");
        Path state = p.getRootDir().toPath().resolve("state.txt");
        Files.writeString(state, "v1");
        ItemFiles.publish(p);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        BLOBS.put("jobs/booted/item-files/" + sha256(v2), new java.io.ByteArrayInputStream(v2), v2.length);
        // (with this controller's origin, so the store listener does not take it; it is taken below as at startup)
        STORE.write("jobs/booted/item-files.json",
                manifest("jobs/booted/item-files.json").replace(sha256("v1".getBytes(StandardCharsets.UTF_8)), sha256(v2)).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        failGet = "jobs/booted/item-files/" + sha256(v2);
        ItemFiles.takeEvery();
        assertNull(failGet, "it could not be taken");
        assertEquals("v1", Files.readString(state));
        new ItemFiles.Publisher().execute(hudson.model.TaskListener.NULL);
        assertEquals("v2", Files.readString(state), "taken with the next publication");
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }

    @TestExtension
    public static class Blobs implements BlobStoreProvider {
        @Override
        public BlobStore create() {
            return BLOBS;
        }
    }
}
