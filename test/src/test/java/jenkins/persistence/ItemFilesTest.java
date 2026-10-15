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
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

    @BeforeEach
    void enable() {
        System.setProperty(ItemFiles.class.getName() + ".enabled", "true");
    }

    @AfterEach
    void disable() {
        System.clearProperty(ItemFiles.class.getName() + ".enabled");
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
