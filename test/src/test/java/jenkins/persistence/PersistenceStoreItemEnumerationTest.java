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
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Items which exist only in the store (as on a freshly started controller) are loaded.
 */
@WithJenkins
class PersistenceStoreItemEnumerationTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/")) {
        @Override
        public Entry read(@NonNull String key) {
            if (reads != null) {
                reads.add(key);
            }
            if (key.equals(unreadable)) {
                throw new UncheckedIOException(new IOException("cannot read " + key));
            }
            return super.read(key);
        }
    };

    /** A key whose reads fail. */
    static volatile String unreadable;

    /** While set, the keys read. */
    static volatile List<String> reads;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void topLevelItemsLoadFromStore() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("from store");
        // an item created by another controller, never seen locally
        STORE.write("jobs/q/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        // simulate a fresh JENKINS_HOME
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));

        r.jenkins.reload();

        assertEquals("from store", r.jenkins.getItemByFullName("p", FreeStyleProject.class).getDescription());
        assertEquals("elsewhere", r.jenkins.getItemByFullName("q", FreeStyleProject.class).getDescription());
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/q").isDirectory(), "local item directory is created for builds");
    }

    @Test
    void folderChildrenLoadFromStore() throws Exception {
        MockFolder f = r.createFolder("f");
        f.createProject(FreeStyleProject.class, "x").setDescription("nested");
        STORE.write("jobs/f/jobs/y/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>nested elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));

        r.jenkins.reload();

        assertThat(r.jenkins.getItem("f"), instanceOf(MockFolder.class));
        assertEquals("nested", r.jenkins.getItemByFullName("f/x", FreeStyleProject.class).getDescription());
        FreeStyleProject y = r.jenkins.getItemByFullName("f/y", FreeStyleProject.class);
        assertNotNull(y);
        assertEquals("nested elsewhere", y.getDescription());
    }

    @Test
    void keysLeftBelowAGoneItemAreNoItem() throws Exception {
        // only a sub-item's configuration is left of folder "gone"
        STORE.write("jobs/gone/jobs/x/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);

        r.jenkins.reload();

        assertNull(r.jenkins.getItem("gone"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/gone").exists());
    }

    @Test
    void oneUnreadableItemDoesNotHideTheOthers() throws Exception {
        STORE.write("jobs/a/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        STORE.write("jobs/b/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        STORE.write("jobs/c/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        unreadable = "jobs/b/config.xml";
        try {
            r.jenkins.reload();
        } finally {
            unreadable = null;
        }

        assertNotNull(r.jenkins.getItem("a"));
        assertNull(r.jenkins.getItem("b"));
        assertNotNull(r.jenkins.getItem("c"));
    }

    @Test
    void itemsListedLocallyAreNotReadToEnumerate() throws Exception {
        r.createFreeStyleProject("here");
        STORE.write("jobs/only-stored/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        File jobs = new File(r.jenkins.getRootDir(), "jobs");
        reads = new CopyOnWriteArrayList<>();
        Set<String> names;
        try {
            names = PersistenceStores.storedItemNames(jobs, Set.of("here"));
        } finally {
            List<String> read = reads;
            reads = null;
            assertFalse(read.contains("jobs/here/config.xml"), read.toString());
        }
        assertFalse(names.contains("here"));
        assertTrue(names.contains("only-stored"));
    }

    @Test
    void manyItemsOnlyInTheStoreAllLoad() throws Exception {
        for (int i = 0; i < 12; i++) {
            STORE.write("jobs/many-" + i + "/config.xml", ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + i + "</description></project>")
                    .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        }
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));

        r.jenkins.reload();

        for (int i = 0; i < 12; i++) {
            assertEquals(String.valueOf(i), r.jenkins.getItemByFullName("many-" + i, FreeStyleProject.class).getDescription());
        }
    }

    @Test
    void anItemFailingToLinkDoesNotHideTheOthers() throws Exception {
        STORE.write("jobs/link-a/config.xml", "<?xml version='1.1' encoding='UTF-8'?><jenkins.persistence.PersistenceStoreItemEnumerationTest_-Unlinkable/>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        STORE.write("jobs/link-b/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        Unlinkable.failing = true;
        try {
            r.jenkins.reload();
        } finally {
            Unlinkable.failing = false;
        }

        assertNull(r.jenkins.getItem("link-a"));
        assertNotNull(r.jenkins.getItem("link-b"));
        STORE.delete("jobs/link-a/config.xml", PersistenceStore.ANY); // its type exists only in this test
    }

    /** An item whose loading fails as when a class it needs is missing. */
    public static class Unlinkable extends AbstractItem implements TopLevelItem {
        static volatile boolean failing;

        public Unlinkable(ItemGroup<?> parent, String name) {
            super(parent, name);
        }

        @Override
        public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
            if (failing) {
                throw new NoClassDefFoundError("missing/Dependency");
            }
            super.onLoad(parent, name);
        }

        @Override
        public Collection<? extends Job> getAllJobs() {
            return Set.of();
        }

        @Override
        public TopLevelItemDescriptor getDescriptor() {
            return (TopLevelItemDescriptor) Jenkins.get().getDescriptorOrDie(getClass());
        }

        @TestExtension("anItemFailingToLinkDoesNotHideTheOthers")
        public static class DescriptorImpl extends TopLevelItemDescriptor {
            @Override
            public TopLevelItem newInstance(ItemGroup parent, String name) {
                return new Unlinkable(parent, name);
            }
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
