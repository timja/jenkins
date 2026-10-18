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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.transform.stream.StreamSource;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Renames and moves carry the stored configuration along.
 */
@WithJenkins
class PersistenceStoreItemLifecycleTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore() {
        @Override
        public List<Moved> move(@NonNull String fromPrefix, @NonNull String toPrefix, @NonNull List<Precondition> preconditions) throws IOException {
            List<Moved> moved = super.move(fromPrefix, toPrefix, preconditions);
            CountDownLatch latch = moving;
            if (latch != null) {
                moveStarted.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            return moved;
        }
    };

    /** While set, store moves wait for it once they moved the entries. */
    static volatile CountDownLatch moving;
    static volatile CountDownLatch moveStarted;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private void freshHome() throws Exception {
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));
        r.jenkins.reload();
    }

    @Test
    void rename() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("before");
        p.setDescription("renamed");
        p.renameTo("after");
        assertThat(STORE.versions(), not(hasKey("jobs/before/config.xml")));
        assertThat(STORE.versions(), hasKey("jobs/after/config.xml"));
        freshHome();
        assertNull(r.jenkins.getItem("before"));
        assertEquals("renamed", r.jenkins.getItemByFullName("after", FreeStyleProject.class).getDescription());
    }

    @Test
    void renameOntoStoredItemFails() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("mine");
        // another controller already created "theirs"; this controller has not loaded it yet
        STORE.write("jobs/theirs/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> p.renameTo("theirs"));
        assertEquals("mine", p.getName());
        assertThat(STORE.versions(), hasKey("jobs/mine/config.xml"));
    }

    @Test
    void renameThroughTheUiOntoAStoredItemSaysTheNameIsInUse() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("ui-mine");
        // made on another controller after this one checked the name
        STORE.write("jobs/ui-theirs/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        WebResponse rsp = wc.getPage(wc.addCrumb(new WebRequest(new URL(r.getURL(), "job/ui-mine/confirmRename?newName=ui-theirs"), HttpMethod.POST))).getWebResponse();
        assertEquals(400, rsp.getStatusCode());
        assertThat(rsp.getContentAsString(), containsString(hudson.model.Messages.AbstractItem_NewNameInUse("ui-theirs")));
        assertEquals("ui-mine", p.getName());
    }

    @Test
    void renameOfAnItemRenamedElsewhereFails() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("twice");
        // another controller renamed it to "twice-a"; this one has not applied that yet
        STORE.move("jobs/twice", "jobs/twice-a", java.util.List.of());
        assertThrows(ConflictException.class, () -> p.renameTo("twice-b"));
        assertEquals("twice", p.getName());
        assertThat(STORE.versions(), hasKey("jobs/twice-a/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/twice-b/config.xml")));
        MockFolder f = r.createFolder("twice-f");
        assertThrows(ConflictException.class, () -> Items.move(p, f));
        assertThat(STORE.versions(), not(hasKey("jobs/twice-f/jobs/twice/config.xml")));
        assertEquals(p, r.jenkins.getItem("twice"));
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        WebResponse rsp = wc.getPage(wc.addCrumb(new WebRequest(new URL(r.getURL(), "job/twice/confirmRename?newName=twice-c"), HttpMethod.POST))).getWebResponse();
        assertEquals(400, rsp.getStatusCode());
        assertThat(rsp.getContentAsString(), containsString(hudson.model.Messages.AbstractItem_ChangedElsewhere()));
        assertThat(STORE.versions(), not(hasKey("jobs/twice-c/config.xml")));
    }

    @Test
    void renamedItemIsStillCheckedForConflicts() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("checked-before");
        p.renameTo("checked-after");
        // another controller changes the renamed item
        STORE.write("jobs/checked-after/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        try {
            assertThrows(ConflictException.class, () -> p.setDescription("here"));
        } finally {
        }
    }

    @Test
    void anItemChangedElsewhereSinceItWasReadIsNotRenamed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("stale-before");
        // another controller changes it; this one has not seen that
        STORE.write("jobs/stale-before/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        // the store's move checks the version held here: the rename waits for the change to be applied here
        assertThrows(ConflictException.class, () -> p.renameTo("stale-after"));
        assertEquals("stale-before", p.getName());
        assertTrue(p.getRootDir().isDirectory());
        assertThat(STORE.versions(), hasKey("jobs/stale-before/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/stale-after/config.xml")));
        assertThrows(ConflictException.class, () -> p.setDescription("here"), "nor taken as seen");
    }

    @Test
    void moveOntoAStaleDirectoryMovesNothing() throws Exception {
        MockFolder f = r.createFolder("stale-f");
        FreeStyleProject p = r.createFreeStyleProject("stale-p");
        new File(f.getRootDir(), "jobs/stale-p").mkdirs(); // left over here
        assertThrows(IOException.class, () -> Items.move(p, f));
        assertThat(STORE.versions(), hasKey("jobs/stale-p/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/stale-f/jobs/stale-p/config.xml")));
        assertEquals(p, r.jenkins.getItem("stale-p"));
    }

    @Test
    void moveIntoFolder() throws Exception {
        MockFolder f = r.createFolder("f");
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("moved");
        Items.move(p, f);
        assertThat(STORE.versions(), not(hasKey("jobs/p/config.xml")));
        assertThat(STORE.versions(), hasKey("jobs/f/jobs/p/config.xml"));
        freshHome();
        assertEquals("moved", r.jenkins.getItemByFullName("f/p", FreeStyleProject.class).getDescription());
    }

    @Test
    void renameFolderMovesChildren() throws Exception {
        MockFolder f = r.createFolder("old");
        f.createProject(FreeStyleProject.class, "child").setDescription("inside");
        f.renameTo("new");
        assertThat(STORE.versions(), hasKey("jobs/new/config.xml"));
        assertThat(STORE.versions(), hasKey("jobs/new/jobs/child/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/old/jobs/child/config.xml")));
        freshHome();
        assertEquals("inside", r.jenkins.getItemByFullName("new/child", FreeStyleProject.class).getDescription());
    }

    @Test
    void renameWhoseDirectoryCannotMoveLeavesTheStoreAsItWas() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("undone-before");
        // neither renamed nor copied: the item's directory is a file, and the new name a directory with something in it
        Util.deleteRecursive(p.getRootDir());
        Files.writeString(p.getRootDir().toPath(), "not a directory");
        File taken = new File(r.jenkins.getRootDir(), "jobs/undone-after");
        taken.mkdirs();
        Files.writeString(new File(taken, "something").toPath(), "here");
        assertThrows(RuntimeException.class, () -> p.renameTo("undone-after"));
        assertEquals("undone-before", p.getName());
        assertThat(STORE.versions(), hasKey("jobs/undone-before/config.xml"));
        assertThat(STORE.versions(), not(hasKey("jobs/undone-after/config.xml")));
    }

    @Test
    void anItemSavedWhileItIsMovedIsSavedInItsNewPlace() throws Exception {
        MockFolder f = r.createFolder("during-f");
        FreeStyleProject p = r.createFreeStyleProject("during-p");
        moving = new CountDownLatch(1);
        moveStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread mover = new Thread(() -> {
            try {
                Items.move(p, f);
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        Thread saver = new Thread(() -> {
            try {
                p.setDescription("saved during the move");
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        try {
            mover.start();
            assertTrue(moveStarted.await(30, TimeUnit.SECONDS));
            saver.start();
            saver.join(500); // waits for the move
        } finally {
            moving.countDown();
            moving = null;
        }
        mover.join();
        saver.join();
        assertNull(failed.get());
        assertThat(STORE.versions(), not(hasKey("jobs/during-p/config.xml")));
        assertThat(new String(STORE.read("jobs/during-f/jobs/during-p/config.xml").content(), StandardCharsets.UTF_8),
                containsString("saved during the move"));
    }

    @Test
    void somethingCreatedElsewhereWhereAnItemWasRenamedFromIsNotReplaced() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("vacated");
        p.renameTo("vacated-new");
        STORE.write("jobs/vacated/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        Route old = StoreRouting.route(PersistenceStores.fileFor("jobs/vacated/config.xml"));
        try {
            // as a save resolving the old name would
            assertThrows(ConflictException.class, () -> old.write("<project/>".getBytes(StandardCharsets.UTF_8), null));
        } finally {
        }
    }

    @Test
    void anItemRenamedWhileItIsUpdatedByXmlIsSavedInItsNewPlace() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("posted-before");
        RelocatingProperty.relocation.set(() -> p.renameTo("posted-after"));
        p.updateByXml(new StreamSource(new StringReader(RelocatingProperty.XML)));
        assertEquals("posted-after", p.getName());
        assertThat(STORE.versions(), not(hasKey("jobs/posted-before/config.xml")));
        assertThat(new String(STORE.read("jobs/posted-after/config.xml").content(), StandardCharsets.UTF_8), containsString("posted"));
    }

    @Test
    void anItemMovedWhileItIsUpdatedByXmlIsSavedInItsNewPlace() throws Exception {
        MockFolder f = r.createFolder("posted-f");
        FreeStyleProject p = r.createFreeStyleProject("posted-p");
        RelocatingProperty.relocation.set(() -> Items.move(p, f));
        p.updateByXml(new StreamSource(new StringReader(RelocatingProperty.XML)));
        assertEquals("posted-f/posted-p", p.getFullName());
        assertThat(STORE.versions(), not(hasKey("jobs/posted-p/config.xml")));
        assertThat(new String(STORE.read("jobs/posted-f/jobs/posted-p/config.xml").content(), StandardCharsets.UTF_8), containsString("posted"));
    }

    /** Has its job renamed or moved, from another thread, while the posted configuration is applied. */
    public static class RelocatingProperty extends JobProperty<Job<?, ?>> {
        static final String XML = "<?xml version='1.1' encoding='UTF-8'?><project><description>posted</description><properties>"
                + "<jenkins.persistence.PersistenceStoreItemLifecycleTest_-RelocatingProperty/></properties></project>";
        static final AtomicReference<Relocation> relocation = new AtomicReference<>();

        interface Relocation {
            void run() throws Exception;
        }

        @DataBoundConstructor
        public RelocatingProperty() {}

        @Override
        protected void setOwner(Job<?, ?> owner) {
            super.setOwner(owner);
            Relocation relocate = relocation.getAndSet(null);
            if (relocate != null) {
                ExecutorService elsewhere = Executors.newSingleThreadExecutor();
                try {
                    elsewhere.submit(() -> {
                        relocate.run();
                        return null;
                    }).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    elsewhere.shutdown();
                }
            }
        }

        @TestExtension({"anItemRenamedWhileItIsUpdatedByXmlIsSavedInItsNewPlace", "anItemMovedWhileItIsUpdatedByXmlIsSavedInItsNewPlace"})
        public static class DescriptorImpl extends JobPropertyDescriptor {}
    }

    @Test
    void aChildSavedWhileItsFolderIsMovedIsNotStoredAtItsOldPlace() throws Exception {
        MockFolder src = r.createFolder("src");
        r.createFolder("dst");
        FreeStyleProject c = src.createProject(FreeStyleProject.class, "c");
        File moved = new File(r.jenkins.getRootDir(), "jobs/dst/jobs/src");
        // the first step of Items.move: the stored entries are moved, the folder is not in its new place here yet, so the
        // child's configuration is still taken to be at the old one
        StoredItems.move(src.getRootDir(), moved);
        assertThrows(ConflictException.class, () -> c.setDescription("while moving"));
        assertNull(STORE.read("jobs/src/jobs/c/config.xml"), "not stored again where it was");
        assertThat(STORE.text("jobs/dst/jobs/src/jobs/c/config.xml"), not(containsString("while moving")));
        // undone, as when the directory cannot be moved: in its old place again
        StoredItems.move(moved, src.getRootDir());
        StoredItems.movedAway(moved);
        c.setDescription("after");
        assertThat(STORE.text("jobs/src/jobs/c/config.xml"), containsString("<description>after</description>"));
        assertNull(STORE.read("jobs/dst/jobs/src/jobs/c/config.xml"));
    }

    @Test
    void aChildSavedAfterItsFolderWasMovedIsStoredInItsNewPlace() throws Exception {
        MockFolder src = r.createFolder("src2");
        MockFolder dst = r.createFolder("dst2");
        FreeStyleProject c = src.createProject(FreeStyleProject.class, "c");
        Items.move(src, dst);
        c.setDescription("moved");
        assertThat(STORE.text("jobs/dst2/jobs/src2/jobs/c/config.xml"), containsString("<description>moved</description>"));
        assertNull(STORE.read("jobs/src2/jobs/c/config.xml"));
        // and an item made where the folder was is made as usual
        r.createFolder("src2").createProject(FreeStyleProject.class, "c").setDescription("new");
        assertThat(STORE.text("jobs/src2/jobs/c/config.xml"), containsString("<description>new</description>"));
    }

    @Test
    void aLateSaveOfADeletedItemDoesNotStoreItAgain() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("late");
        p.delete();
        assertThrows(ConflictException.class, p::save);
        assertNull(STORE.read("jobs/late/config.xml"));
        FreeStyleProject again = r.createFreeStyleProject("late"); // one made again of that name is another item
        again.setDescription("new");
        assertThat(STORE.text("jobs/late/config.xml"), containsString("<description>new</description>"));
    }

    @Test
    void aDeletedItemTakesWhatIsStoredBelowItWithIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleted-with-subitems");
        STORE.write("jobs/deleted-with-subitems/promotions/release/config.xml", "<process/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        p.delete();
        assertThat(STORE.versions().keySet().stream().filter(k -> k.startsWith("jobs/deleted-with-subitems/")).toList(), org.hamcrest.Matchers.empty());
    }

    @Test
    void theDirectoriesOfWhatIsConfiguredBelowAnItemAreMadeWhenItLoads() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("with-subitems");
        STORE.write("jobs/with-subitems/promotions/release/config.xml", "<process/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        STORE.write("jobs/with-subitems/../../escaped/config.xml", "<process/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        File dir = new File(p.getRootDir(), "promotions/release");
        assertFalse(dir.exists());
        p.onLoad(r.jenkins, "with-subitems");
        assertTrue(dir.isDirectory(), "for the plugin listing it");
        assertFalse(new File(r.jenkins.getRootDir().getParentFile(), "escaped").exists());
    }

    @Test
    void anItemMadeWithAStoreHasAnIdentityOfItsOwn() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("pinned");
        assertThat(new String(STORE.read("jobs/pinned/config.xml").content(), StandardCharsets.UTF_8),
                containsString("<itemUuid>" + p.getItemUuid() + "</itemUuid>"));
        p.renameTo("pinned-renamed");
        assertThat(new String(STORE.read("jobs/pinned-renamed/config.xml").content(), StandardCharsets.UTF_8),
                containsString("<itemUuid>" + p.getItemUuid() + "</itemUuid>"));
    }

    /** The contents of every write of a key the store tells of, as other controllers see them. */
    private static AutoCloseable recordWrites(String key, List<String> written) {
        return STORE.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN && e.key().equals(key)) {
                try {
                    written.add(new String(STORE.read(key).content(), StandardCharsets.UTF_8));
                } catch (IOException x) {
                    throw new AssertionError(x);
                }
            }
        });
    }

    @Test
    void anItemCreatedFromXmlIsOnlyEverStoredWithItsOwnIdentity() throws Exception {
        List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        String someoneElses = java.util.UUID.randomUUID().toString();
        FreeStyleProject p;
        try (AutoCloseable recording = recordWrites("jobs/from-xml/config.xml", written)) {
            // posted without an identity, or with another item's
            p = (FreeStyleProject) r.jenkins.createProjectFromXML("from-xml", new java.io.ByteArrayInputStream(
                    "<?xml version='1.1' encoding='UTF-8'?><project><description>posted</description></project>".getBytes(StandardCharsets.UTF_8)));
            r.jenkins.createProjectFromXML("from-xml-2", new java.io.ByteArrayInputStream(
                    ("<?xml version='1.1' encoding='UTF-8'?><project><itemUuid>" + someoneElses + "</itemUuid></project>").getBytes(StandardCharsets.UTF_8)));
        }
        assertEquals(1, written.size(), "stored once, as a creation: " + written);
        assertThat(written.get(0), containsString("<itemUuid>" + p.getItemUuid() + "</itemUuid>"));
        assertThat(written.get(0), containsString("<description>posted</description>"));
        String other = new String(STORE.read("jobs/from-xml-2/config.xml").content(), StandardCharsets.UTF_8);
        assertThat(other, not(containsString(someoneElses)));
        assertThat(other, containsString("<itemUuid>" + r.jenkins.getItem("from-xml-2").getItemUuid() + "</itemUuid>"));
    }

    @Test
    void aCopyIsOnlyEverStoredWithAnIdentityOfItsOwn() throws Exception {
        FreeStyleProject src = r.createFreeStyleProject("copied-from");
        src.setDescription("copied");
        List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        FreeStyleProject copy;
        try (AutoCloseable recording = recordWrites("jobs/copied-to/config.xml", written)) {
            copy = (FreeStyleProject) r.jenkins.copy((hudson.model.TopLevelItem) src, "copied-to");
        }
        assertEquals(1, written.size(), "stored once, as a creation: " + written);
        assertThat(written.get(0), not(containsString(src.getItemUuid())));
        assertThat(written.get(0), containsString("<itemUuid>" + copy.getItemUuid() + "</itemUuid>"));
        assertThat(written.get(0), containsString("<description>copied</description>"));
        assertEquals(copy, r.jenkins.getItem("copied-to"));
        freshHome();
        assertEquals(copy.getItemUuid(), r.jenkins.getItem("copied-to").getItemUuid());
    }

    @Test
    void aCreationRefusedBecauseTheNameWasStoredMeanwhileStoresNothing() throws Exception {
        FreeStyleProject src = r.createFreeStyleProject("source-of-refused");
        String theirs = "<?xml version='1.1' encoding='UTF-8'?><project><description>theirs</description></project>";
        // stored by another controller while this one makes the copy (Interloper): the stored write, a creation, is refused
        Interloper.content = theirs;
        assertThrows(hudson.model.Failure.class, () -> r.jenkins.copy((hudson.model.TopLevelItem) src, "refused-copy"));
        assertEquals(theirs, new String(STORE.read("jobs/refused-copy/config.xml").content(), StandardCharsets.UTF_8));
        assertNull(r.jenkins.getItem("refused-copy"));
    }

    /** Stores an item of the name of the copy being made, as another controller would, when the copy is saved here. */
    @TestExtension("aCreationRefusedBecauseTheNameWasStoredMeanwhileStoresNothing")
    public static final class Interloper extends hudson.model.listeners.SaveableListener {
        static volatile String content;

        @Override
        public void onChange(hudson.model.Saveable o, hudson.XmlFile file) {
            String c = content;
            if (c != null && file.getPath().getParentFile().getName().equals("refused-copy")) {
                content = null;
                try {
                    STORE.write("jobs/refused-copy/config.xml", c.getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    /** Static state stays local: the store shares items only, and outlives each test's Jenkins. */
    @TestExtension
    public static final class ItemsOnly implements LocalFiles {
        @Override
        public java.util.Collection<String> patterns() {
            return List.of("*.xml", "users/**", "nodes/**", "log/*");
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
