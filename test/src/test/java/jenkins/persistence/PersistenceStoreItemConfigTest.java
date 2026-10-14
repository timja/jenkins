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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PersistenceStoreItemConfigTest {

    /** Owns item configuration only, as a store delivered by a plugin would. */
    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @AfterEach
    void tearDown() {
        PersistenceStores.CONFLICTS = "refuse";
    }

    @Test
    void providerIsResolved() {
        assertThat(PersistenceStores.get(), is(STORE));
    }

    @Test
    void itemConfigLivesInStore() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("stored");
        assertThat(STORE.versions(), hasKey("jobs/p/config.xml"));
        File local = new File(r.jenkins.getRootDir(), "jobs/p/config.xml");
        assertFalse(local.exists(), "config.xml must not be written locally");
        FreeStyleProject loaded = (FreeStyleProject) Items.load(r.jenkins, p.getRootDir());
        assertEquals("stored", loaded.getDescription());
        assertThat(Items.getConfigFile(p).asString(), containsString("<description>stored</description>"));
    }

    @Test
    void globalConfigStaysLocal() throws Exception {
        r.jenkins.setSystemMessage("local");
        assertTrue(new File(r.jenkins.getRootDir(), "config.xml").exists());
        assertThat(STORE.versions(), not(hasKey("config.xml")));
    }

    @Test
    void createFromXmlAndCopy() throws Exception {
        String xml = "<?xml version='1.1' encoding='UTF-8'?><project><description>from xml</description></project>";
        r.jenkins.createProjectFromXML("x", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(STORE.versions(), hasKey("jobs/x/config.xml"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/x/config.xml").exists());
        FreeStyleProject src = r.jenkins.getItemByFullName("x", FreeStyleProject.class);
        FreeStyleProject copy = (FreeStyleProject) r.jenkins.copy((hudson.model.TopLevelItem) src, "y");
        assertEquals("from xml", copy.getDescription());
        assertThat(STORE.versions(), hasKey("jobs/y/config.xml"));
    }

    @Test
    void getFileMaterializesSnapshot() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("m");
        p.setDescription("snapshot");
        File f = Items.getConfigFile(p).getFile();
        assertTrue(f.exists());
        assertThat(Files.readString(f.toPath()), containsString("snapshot"));
    }

    @Test
    void deleteRemovesKey() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("d");
        assertThat(STORE.versions(), hasKey("jobs/d/config.xml"));
        p.delete();
        assertThat(STORE.versions(), not(hasKey("jobs/d/config.xml")));
        assertFalse(PersistenceStores.hasSnapshot("jobs/d/config.xml"), "nothing kept of it in memory either");
        assertNotNull(r.jenkins);
    }

    @Test
    void identicalSaveIsNoOp() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("n");
        long v = STORE.versions().get("jobs/n/config.xml");
        p.save();
        assertEquals(v, STORE.versions().get("jobs/n/config.xml"));
    }

    @Test
    void conflictingWriteIsDetected() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("c");
        String key = "jobs/c/config.xml";
        long before = PersistenceStores.getConflictCount();
        // another controller modifies the job behind our back
        STORE.write(key, "<project><description>elsewhere</description></project>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        assertThrows(ConflictException.class, () -> p.setDescription("here"));
        assertEquals(before + 1, PersistenceStores.getConflictCount());
        assertThat(new String(STORE.read(key).content(), StandardCharsets.UTF_8), containsString("elsewhere"));

        PersistenceStores.CONFLICTS = "fail";
        STORE.write(key, "<project><description>elsewhere again</description></project>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        assertThrows(IOException.class, () -> p.setDescription("rejected"));
        assertThat(new String(STORE.read(key).content(), StandardCharsets.UTF_8), containsString("elsewhere again"));
    }

    @Test
    void aKeyNeverReadHereIsOnlyCreated() throws Exception {
        String key = "jobs/unread/config.xml";
        byte[] theirs = "<project><description>elsewhere</description></project>".getBytes(StandardCharsets.UTF_8);
        STORE.write(key, theirs, PersistenceStore.ANY);
        // not read here (its read failed, say, and the object holds defaults): its save must not replace what is stored
        PersistenceStores.Route route = PersistenceStores.route(PersistenceStores.fileFor(key));
        assertThrows(ConflictException.class, () -> route.write("<project/>".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(theirs, STORE.read(key).content());
        // never stored: created
        PersistenceStores.Route fresh = PersistenceStores.route(PersistenceStores.fileFor("jobs/unread2/config.xml"));
        fresh.write("<project/>".getBytes(StandardCharsets.UTF_8));
        assertThat(STORE.versions(), hasKey("jobs/unread2/config.xml"));
    }

    @Test
    void createdElsewhereAfterReadingAbsentIsAConflict() throws Exception {
        String key = "jobs/absent/config.xml";
        PersistenceStores.Route route = PersistenceStores.route(PersistenceStores.fileFor(key));
        assertNull(route.read());
        // another controller creates it after we saw it absent
        STORE.write(key, "<project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        PersistenceStores.CONFLICTS = "fail";
        assertThrows(ConflictException.class, () -> route.write("<project><description>here</description></project>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void creatingAnItemDoesNotReplaceOneStoredElsewhere() throws Exception {
        // stored by another controller, not (yet) loaded here: create-only, whatever the conflict policy
        String key = "jobs/taken/config.xml";
        byte[] theirs = "<project><description>elsewhere</description></project>".getBytes(StandardCharsets.UTF_8);
        STORE.write(key, theirs, PersistenceStore.ANY);
        // its local directory (it failed to load here, say) keeps its builds
        File build = new File(r.jenkins.getRootDir(), "jobs/taken/builds/1/build.xml");
        Files.createDirectories(build.getParentFile().toPath());
        Files.writeString(build.toPath(), "<build/>");
        assertThrows(Failure.class, () -> r.jenkins.createProject(FreeStyleProject.class, "taken"));
        assertArrayEquals(theirs, STORE.read(key).content());
        String xml = "<?xml version='1.1' encoding='UTF-8'?><project><description>mine</description></project>";
        assertThrows(Failure.class, () -> r.jenkins.createProjectFromXML("taken", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        assertArrayEquals(theirs, STORE.read(key).content());
        assertTrue(build.exists(), "the stored item's local directory is not deleted");
        assertNull(r.jenkins.getItem("taken"));
    }

    @Test
    void failedCreationFromXmlDeletesOnlyWhatItWrote() throws Exception {
        String key = "jobs/taken2/config.xml";
        byte[] theirs = "<project><description>elsewhere</description></project>".getBytes(StandardCharsets.UTF_8);
        STORE.write(key, theirs, PersistenceStore.ANY);
        assertThrows(IOException.class, () -> r.jenkins.createProjectFromXML("taken2", new ByteArrayInputStream("<project".getBytes(StandardCharsets.UTF_8))));
        assertArrayEquals(theirs, STORE.read(key).content());
        // what a failed creation wrote itself is deleted (the type is not an item)
        String bad = "<?xml version='1.1' encoding='UTF-8'?><hudson.model.Failure/>";
        assertThrows(Exception.class, () -> r.jenkins.createProjectFromXML("broken", new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))));
        assertThat(STORE.versions(), not(hasKey("jobs/broken/config.xml")));
    }

    @Test
    void recreatedElsewhereAfterDeletingIsAConflict() throws Exception {
        String key = "jobs/gone/config.xml";
        PersistenceStores.Route route = PersistenceStores.route(PersistenceStores.fileFor(key));
        route.write("<project/>".getBytes(StandardCharsets.UTF_8));
        route.delete();
        // another controller creates it again; a late save from the object loaded before must not replace that
        STORE.write(key, "<project><description>elsewhere</description></project>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        PersistenceStores.CONFLICTS = "fail";
        assertThrows(ConflictException.class, () -> route.write("<project><description>late</description></project>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void getFileReplacesTheSnapshotWhole() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("w");
        String key = "jobs/w/config.xml";
        byte[] a = ("<project><description>" + "a".repeat(1 << 20) + "</description></project>").getBytes(StandardCharsets.UTF_8);
        byte[] b = ("<project><description>" + "b".repeat(1 << 19) + "</description></project>").getBytes(StandardCharsets.UTF_8);
        File f = Items.getConfigFile(p).getFile();
        STORE.write(key, a, PersistenceStore.ANY);
        Items.getConfigFile(p).getFile();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try {
                    byte[] seen = Files.readAllBytes(f.toPath());
                    if (!Arrays.equals(seen, a) && !Arrays.equals(seen, b)) {
                        torn.set("read " + seen.length + " bytes");
                    }
                } catch (IOException e) {
                    torn.set(e.toString());
                }
            }
        });
        reader.start();
        try {
            long end = System.currentTimeMillis() + 2000;
            for (int i = 0; System.currentTimeMillis() < end && torn.get() == null; i++) {
                STORE.write(key, i % 2 == 0 ? b : a, PersistenceStore.ANY);
                Items.getConfigFile(p).getFile();
            }
        } finally {
            done.set(true);
            reader.join();
        }
        assertNull(torn.get());
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
