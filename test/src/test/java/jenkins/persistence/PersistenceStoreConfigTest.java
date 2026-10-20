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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Files routed to the store: read and written there, saves checked against the version read, local copies for
 * callers reading the file directly.
 */
@WithJenkins
class PersistenceStoreConfigTest {

    /** The store of the running Jenkins, counting reads by key. */
    static volatile CountingStore STORE;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Route route(String key) {
        return StoreRouting.route(PersistenceStores.fileFor(key));
    }

    @Test
    void theProviderIsResolvedOnce() {
        assertSame(STORE, PersistenceStores.get());
        assertSame(STORE, PersistenceStores.get());
    }

    @Test
    void itemConfigurationLivesInTheStore() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("stored");
        assertThat(STORE.versions(), hasKey("jobs/p/config.xml"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/p/config.xml").exists(), "not written locally");
        assertTrue(p.getRootDir().isDirectory(), "the item's directory stays local, for its builds");
        FreeStyleProject loaded = (FreeStyleProject) Items.load(r.jenkins, p.getRootDir());
        assertEquals("stored", loaded.getDescription());
        assertThat(Items.getConfigFile(p).asString(), containsString("<description>stored</description>"));
        assertTrue(Items.getConfigFile(p).exists());
    }

    @Test
    void anIdenticalSaveMakesNoNewVersion() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("n");
        long v = STORE.versions().get("jobs/n/config.xml");
        p.save();
        assertEquals(v, STORE.versions().get("jobs/n/config.xml"));
    }

    @Test
    void aKeyNeverReadHereIsOnlyCreated() throws Exception {
        String key = "jobs/unread/config.xml";
        byte[] theirs = b("<project><description>elsewhere</description></project>");
        STORE.as("other").write(key, theirs, PersistenceStore.ANY);
        // not read here (its read failed, say, and the object holds defaults): its save must not replace what is stored
        assertThrows(ConflictException.class, () -> route(key).write(b("<project/>"), null));
        assertArrayEquals(theirs, STORE.read(key).content());
        // never stored: created
        route("jobs/unread2/config.xml").write(b("<project/>"), null);
        assertThat(STORE.versions(), hasKey("jobs/unread2/config.xml"));
    }

    @Test
    void aKeyCreatedElsewhereAfterBeingReadAbsentIsNotReplaced() throws Exception {
        String key = "jobs/absent/config.xml";
        assertNull(route(key).read());
        STORE.as("other").write(key, b("<project/>"), PersistenceStore.ANY);
        assertThrows(ConflictException.class, () -> route(key).write(b("<project><description>here</description></project>"), null));
        assertArrayEquals(b("<project/>"), STORE.read(key).content());
    }

    @Test
    void aKeyCreatedAgainElsewhereAfterItsDeletionHereIsNotReplaced() throws Exception {
        String key = "jobs/gone/config.xml";
        route(key).write(b("<project/>"), null);
        route(key).delete();
        assertThat(STORE.versions(), not(hasKey(key)));
        STORE.as("other").write(key, b("<project><description>elsewhere</description></project>"), PersistenceStore.ANY);
        // a late save from an object loaded before must not replace that
        assertThrows(ConflictException.class, () -> route(key).write(b("<project><description>late</description></project>"), null));
        assertThat(STORE.text(key), containsString("elsewhere"));
    }

    @Test
    void aStoreThatCannotBeReachedIsNeverTakenForAbsence() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("outage");
        XmlFile config = Items.getConfigFile(p);
        STORE.setUnreachable(true);
        try {
            assertThrows(UncheckedIOException.class, config::exists);
            assertThrows(IOException.class, config::checkExists);
            IOException e = assertThrows(IOException.class, config::read);
            assertFalse(e instanceof java.nio.file.NoSuchFileException, "not taken for a missing file: " + e);
            assertThrows(IOException.class, () -> p.setDescription("not saved"));
            // a copy for direct readers is left as it is, with no exception
            assertSame(config.getPath(), config.getFile());
        } finally {
            STORE.setUnreachable(false);
        }
        assertTrue(config.exists());
    }

    @Test
    void getFileCopiesTheStoredContentAndReadsTheStoreOnlyAfterAChange() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("m");
        p.setDescription("copied");
        String key = "jobs/m/config.xml";
        XmlFile config = Items.getConfigFile(p);
        int before = STORE.reads(key);
        File f = config.getFile();
        assertThat(Files.readString(f.toPath()), containsString("copied"));
        for (int i = 0; i < 10; i++) {
            config.getFile();
        }
        assertEquals(before + 1, STORE.reads(key), "the copy is current: no read for each use");
        PersistenceStores.MaterializedCopy copy = PersistenceStores.materializedCopies().stream()
                .filter(c -> c.key().equals(key)).findFirst().orElseThrow();
        assertEquals(f, copy.file());
        assertEquals(STORE.versions().get(key), copy.version());
        assertEquals(f.lastModified(), copy.modified());
        // changed elsewhere: copied again at the next use
        String theirs = STORE.text(key).replace("copied", "elsewhere");
        STORE.as("other").write(key, b(theirs), PersistenceStore.ANY);
        assertThat(Files.readString(config.getFile().toPath()), containsString("elsewhere"));
        assertEquals(before + 2, STORE.reads(key));
        config.getFile();
        assertEquals(before + 2, STORE.reads(key));
        // and written here
        route(key).read();
        route(key).write(b(theirs.replace("elsewhere", "written here")), null);
        assertThat(Files.readString(config.getFile().toPath()), containsString("written here"));
    }

    @Test
    void aCopyChangedOnDiskIsLeftAsItIs() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("direct");
        p.setDescription("saved");
        File f = Items.getConfigFile(p).getFile();
        String direct = Files.readString(f.toPath()).replace("saved", "written directly");
        Files.writeString(f.toPath(), direct);
        // the store is not updated from it, and it is not replaced by the stored content either
        assertEquals(f, Items.getConfigFile(p).getFile());
        assertEquals(direct, Files.readString(f.toPath()));
        assertThat(STORE.text("jobs/direct/config.xml"), containsString("saved"));
        // once it holds the stored content again (written back, say), it is current again
        Files.write(f.toPath(), STORE.read("jobs/direct/config.xml").content());
        Items.getConfigFile(p).getFile();
        p.setDescription("saved again");
        assertThat(Files.readString(Items.getConfigFile(p).getFile().toPath()), containsString("saved again"));
    }

    @Test
    void aCopyOfWhatIsNoLongerStoredIsDeleted() throws Exception {
        String key = "jobs/vanished/config.xml";
        route(key).write(b("<project/>"), null);
        XmlFile config = new XmlFile(PersistenceStores.fileFor(key));
        File f = config.getFile();
        assertTrue(f.isFile());
        STORE.as("other").delete(key, PersistenceStore.ANY);
        config.getFile();
        assertFalse(f.exists());
        assertFalse(PersistenceStores.materializedCopies().stream().anyMatch(c -> c.key().equals(key)));
    }

    @Test
    void aCopyIsReplacedWhole() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("w");
        String key = "jobs/w/config.xml";
        byte[] a = b("<project><description>" + "a".repeat(1 << 20) + "</description></project>");
        byte[] c = b("<project><description>" + "b".repeat(1 << 19) + "</description></project>");
        File f = Items.getConfigFile(p).getFile();
        PersistenceStore other = STORE.as("other");
        other.write(key, a, PersistenceStore.ANY);
        Items.getConfigFile(p).getFile();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try {
                    byte[] seen = Files.readAllBytes(f.toPath());
                    if (!Arrays.equals(seen, a) && !Arrays.equals(seen, c)) {
                        torn.set("read " + seen.length + " bytes");
                    }
                } catch (IOException e) {
                    torn.set(e.toString());
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 50 && torn.get() == null; i++) {
                other.write(key, i % 2 == 0 ? c : a, PersistenceStore.ANY);
                Items.getConfigFile(p).getFile();
            }
        } finally {
            done.set(true);
            reader.join();
        }
        assertNull(torn.get());
    }

    @Test
    void filesMatchingASharedPatternAreStoredToo() throws Exception {
        XmlFile x = new XmlFile(new File(r.jenkins.getRootDir(), "jobs/t/scm-revision-hash.xml"));
        assertFalse(x.exists());
        x.write("abc");
        assertThat(STORE.text("jobs/t/scm-revision-hash.xml"), containsString("<string>abc</string>"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/t/scm-revision-hash.xml").exists());
        assertEquals("abc", x.read());
        assertTrue(x.exists());
        STORE.as("other").write("jobs/t/scm-revision-hash.xml", b("<string>def</string>"), PersistenceStore.ANY);
        assertEquals("def", x.read());
        x.write("ghi"); // read here at that version
        assertThat(STORE.text("jobs/t/scm-revision-hash.xml"), containsString("<string>ghi</string>"));
        x.delete();
        assertFalse(x.exists());
    }

    @Test
    void whatIsAppliedFromElsewhereIsNotWrittenBack() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("applied");
        long v = STORE.versions().get("jobs/applied/config.xml");
        PersistenceStores.applyingRemoteChange(() -> {
            p.setDescription("in memory only");
            return null;
        });
        assertEquals(v, STORE.versions().get("jobs/applied/config.xml"));
    }

    /** Counts reads by key. */
    static final class CountingStore extends InMemoryPersistenceStore {
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

        int reads(String key) {
            AtomicInteger n = reads.get(key);
            return n == null ? 0 : n.get();
        }

        @Override
        public Entry read(@NonNull String key) throws IOException {
            reads.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            return super.read(key);
        }
    }

    @TestExtension
    public static final class Shared implements SharedFiles {
        @Override
        public Collection<String> patterns() {
            return List.of("jobs/*/scm-revision-hash.xml");
        }
    }

    @TestExtension
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE = new CountingStore();
        }
    }
}
