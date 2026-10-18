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

import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Files routed to the store: read and written there, saves checked against the version read.
 */
@WithJenkins
class PersistenceStoreConfigTest {

    /** The store of the running Jenkins. */
    static volatile InMemoryPersistenceStore STORE;

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
        assertThrows(ConflictException.class, () -> route(key).write(b("<project/>")));
        assertArrayEquals(theirs, STORE.read(key).content());
        // never stored: created
        route("jobs/unread2/config.xml").write(b("<project/>"));
        assertThat(STORE.versions(), hasKey("jobs/unread2/config.xml"));
    }

    @Test
    void aKeyCreatedElsewhereAfterBeingReadAbsentIsNotReplaced() throws Exception {
        String key = "jobs/absent/config.xml";
        assertNull(route(key).read());
        STORE.as("other").write(key, b("<project/>"), PersistenceStore.ANY);
        assertThrows(ConflictException.class, () -> route(key).write(b("<project><description>here</description></project>")));
        assertArrayEquals(b("<project/>"), STORE.read(key).content());
    }

    @Test
    void aKeyCreatedAgainElsewhereAfterItsDeletionHereIsNotReplaced() throws Exception {
        String key = "jobs/gone/config.xml";
        route(key).write(b("<project/>"));
        route(key).delete();
        assertThat(STORE.versions(), not(hasKey(key)));
        STORE.as("other").write(key, b("<project><description>elsewhere</description></project>"), PersistenceStore.ANY);
        // a late save from an object loaded before must not replace that
        assertThrows(ConflictException.class, () -> route(key).write(b("<project><description>late</description></project>")));
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
        } finally {
            STORE.setUnreachable(false);
        }
        assertTrue(config.exists());
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
            return STORE = new InMemoryPersistenceStore();
        }
    }
}
