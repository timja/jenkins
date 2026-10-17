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
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Changes made by another controller (simulated by a store view with another origin) are applied here.
 */
@WithJenkins
class ItemChangeApplierTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/")) {
        @Override
        public Entry read(String key) {
            Runnable r = beforeLoading;
            if (r != null && StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals("load")
                    && f.getClassName().equals(AbstractItem.class.getName())))) {
                beforeLoading = null;
                r.run();
            }
            return super.read(key);
        }
    };

    /** While set, run once as an item is next loaded here, just before its file is read. */
    static volatile Runnable beforeLoading;

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
    }

    private static byte[] project(String description) {
        return ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + description + "</description></project>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitApplied() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!ItemChangeApplier.isIdle()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("changes not applied in time");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void createdElsewhere() throws Exception {
        other.write("jobs/remote/config.xml", project("made elsewhere"), PersistenceStore.ABSENT);
        awaitApplied();
        FreeStyleProject p = r.jenkins.getItemByFullName("remote", FreeStyleProject.class);
        assertNotNull(p);
        assertEquals("made elsewhere", p.getDescription());
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/remote").isDirectory());
    }

    @Test
    void updatedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        long before = ItemChangeApplier.getAppliedCount();
        other.write("jobs/p/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
        awaitApplied();
        assertThat(r.jenkins.getItem("p"), sameInstance(p));
        assertEquals("changed elsewhere", p.getDescription());
        assertEquals(before + 1, ItemChangeApplier.getAppliedCount());
        // and a local save afterwards is not a conflict: we saw the latest version
        long conflicts = PersistenceStores.getConflictCount();
        p.setDescription("then here");
        assertEquals(conflicts, PersistenceStores.getConflictCount());
    }

    private static String text(String key) {
        return new String(STORE.read(key).content(), StandardCharsets.UTF_8);
    }

    private static byte[] withQuietPeriod(String xml, int seconds) {
        return xml.replace("</project>", "  <quietPeriod>" + seconds + "</quietPeriod>\n</project>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void changesMadeElsewhereToAnItemWithASavePendingWaitForItTogether() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("pending");
        String key = "jobs/pending/config.xml";
        String base = text(key);
        long merges = PersistenceStores.getMergeCount();
        try (hudson.BulkChange bc = new hudson.BulkChange(p)) {
            p.setDescription("here"); // saved below
            for (int i = 1; i <= 4; i++) { // a burst of changes elsewhere: they do not use up the retries at once
                other.write(key, withQuietPeriod(base, i), PersistenceStore.ANY);
            }
            Thread.sleep(1_500);
            assertEquals("here", p.getDescription(), "not reloaded over the change about to be saved");
            bc.commit();
        }
        awaitApplied();
        assertEquals(merges + 1, PersistenceStores.getMergeCount());
        assertThat(text(key), org.hamcrest.Matchers.containsString("<description>here</description>"));
        assertEquals(4, p.getQuietPeriod(), "reloaded with the changes made elsewhere");
    }

    @Test
    void aChangePutOffWhoseRetryFindsItSeenAlreadyDoesNotHoldUpLaterOnes() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("seen-meanwhile");
        String key = "jobs/seen-meanwhile/config.xml";
        String base = text(key);
        try (hudson.BulkChange bc = new hudson.BulkChange(p)) {
            p.setDescription("here"); // never saved
            other.write(key, withQuietPeriod(base, 1), PersistenceStore.ANY);
            Thread.sleep(200); // put off
            PersistenceStores.seen(key, STORE.read(key).version()); // read here before its retry, as a reload here would
            Thread.sleep(1_500); // its retry finds it seen already
            other.write(key, withQuietPeriod(base, 2), PersistenceStore.ANY);
            for (int i = 0; i < 100 && p.getQuietPeriod() != 2; i++) {
                Thread.sleep(100);
            }
            assertEquals(2, p.getQuietPeriod(), "applied once put off enough, as any change");
        }
    }

    @Test
    void anItemLoadedAtStartupIsNotReloadedOverAChangeNotSavedYet() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("loaded");
        String key = "jobs/loaded/config.xml";
        PersistenceStores.setForTesting(null, null); // what a restart forgets
        p.getConfigFile().unmarshal(p); // loaded at startup
        assertFalse(PersistenceStores.hasSnapshot(key));
        ItemChangeApplier.subscribe();
        ItemChangeApplier.start();
        awaitApplied();
        assertTrue(PersistenceStores.hasSnapshot(key), "taken once loading is done");

        String base = text(key);
        try (hudson.BulkChange bc = new hudson.BulkChange(p)) {
            p.setDescription("here");
            other.write(key, withQuietPeriod(base, 7), PersistenceStore.ANY);
            Thread.sleep(500);
            assertEquals("here", p.getDescription(), "not reloaded over the change about to be saved");
            bc.commit();
        }
        awaitApplied();
        assertThat(text(key), org.hamcrest.Matchers.containsString("<description>here</description>"));
        assertEquals(7, p.getQuietPeriod());

        other.write("jobs/made-elsewhere/config.xml", project("x"), PersistenceStore.ABSENT);
        awaitApplied();
        assertTrue(PersistenceStores.hasSnapshot("jobs/made-elsewhere/config.xml"), "and one created elsewhere once loaded");
    }

    @Test
    void ownChangesAreNotReapplied() throws Exception {
        long before = ItemChangeApplier.getAppliedCount();
        r.createFreeStyleProject("mine").setDescription("local");
        awaitApplied();
        assertEquals(before, ItemChangeApplier.getAppliedCount());
    }

    @Test
    void createdInFolderElsewhere() throws Exception {
        r.createFolder("f");
        other.write("jobs/f/jobs/c/config.xml", project("child"), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals("child", r.jenkins.getItemByFullName("f/c", FreeStyleProject.class).getDescription());
    }

    @Test
    void childBeforeParent() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template")).readRawBytes();
        other.write("jobs/g/jobs/c/config.xml", project("early child"), PersistenceStore.ABSENT);
        other.write("jobs/g/config.xml", folderXml, PersistenceStore.ABSENT);
        awaitApplied();
        assertThat(r.jenkins.getItem("g"), instanceOf(MockFolder.class));
        assertEquals("early child", r.jenkins.getItemByFullName("g/c", FreeStyleProject.class).getDescription());
    }

    @Test
    void deletedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("doomed");
        File dir = p.getRootDir();
        other.delete("jobs/doomed/config.xml", PersistenceStore.ANY);
        awaitApplied();
        assertNull(r.jenkins.getItem("doomed"));
        assertFalse(dir.exists());
    }

    @Test
    void renamedElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("old-name");
        other.move("jobs/old-name", "jobs/new-name");
        awaitApplied();
        assertNull(r.jenkins.getItem("old-name"));
        assertThat(r.jenkins.getItem("new-name"), sameInstance(p));
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/new-name").isDirectory());
        assertTrue(STORE.versions().containsKey("jobs/new-name/config.xml"), "rename was not re-done locally against the store");
    }

    @Test
    void movedIntoFolderElsewhere() throws Exception {
        r.createFolder("dest");
        FreeStyleProject p = r.createFreeStyleProject("traveller");
        other.move("jobs/traveller", "jobs/dest/jobs/traveller");
        awaitApplied();
        assertNull(r.jenkins.getItem("traveller"));
        assertNotNull(r.jenkins.getItemByFullName("dest/traveller"));
    }

    /** While set, the applier waits for it when it applies a change to item "pause". */
    static volatile CountDownLatch paused;
    static volatile CountDownLatch pausing;

    /** Holds the applier up: changes made elsewhere meanwhile queue up until {@link #resumeApplier}. */
    private void pauseApplier() throws Exception {
        if (r.jenkins.getItem("pause") == null) {
            r.createFreeStyleProject("pause");
        }
        paused = new CountDownLatch(1);
        pausing = new CountDownLatch(1);
        other.write("jobs/pause/config.xml", project("pause " + System.nanoTime()), PersistenceStore.ANY);
        assertTrue(pausing.await(30, TimeUnit.SECONDS));
    }

    private static void resumeApplier() {
        CountDownLatch l = paused;
        paused = null;
        l.countDown();
    }

    @TestExtension
    public static class Pause extends ItemListener {
        @Override
        public void onUpdated(Item item) {
            CountDownLatch l = paused;
            if (l != null && item.getName().equals("pause")) {
                pausing.countDown();
                try {
                    l.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void aDeletionOlderThanTheItemMadeHereSinceIsNotApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("again");
        pauseApplier();
        try {
            other.delete("jobs/again/config.xml", PersistenceStore.ANY);
            p.delete();
            r.createFreeStyleProject("again").setDescription("made again here");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        FreeStyleProject again = r.jenkins.getItemByFullName("again", FreeStyleProject.class);
        assertNotNull(again);
        assertEquals("made again here", again.getDescription());
        assertTrue(again.getRootDir().isDirectory());
    }

    @Test
    void aChildOfAFolderRenamedElsewhereIsStillCheckedForConflicts() throws Exception {
        MockFolder f = r.createFolder("rf");
        FreeStyleProject c = f.createProject(FreeStyleProject.class, "c");
        other.move("jobs/rf", "jobs/rf2");
        awaitApplied();
        assertThat(r.jenkins.getItemByFullName("rf2/c"), sameInstance(c));
        pauseApplier();
        PersistenceStores.CONFLICTS = "fail";
        try {
            other.write("jobs/rf2/jobs/c/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
            assertThrows(ConflictException.class, () -> c.setDescription("here"));
        } finally {
            PersistenceStores.CONFLICTS = "warn";
            resumeApplier();
        }
    }

    @Test
    void aChangeMadeElsewhereJustBeforeARenameIsApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("edited");
        pauseApplier();
        try {
            other.write("jobs/edited/config.xml", project("edited elsewhere"), PersistenceStore.ANY);
            other.move("jobs/edited", "jobs/edited-renamed");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertThat(r.jenkins.getItem("edited-renamed"), sameInstance(p));
        assertEquals("edited elsewhere", p.getDescription());
    }

    @Test
    void anItemCreatedAndRenamedElsewhereAppears() throws Exception {
        pauseApplier();
        try {
            other.write("jobs/born/config.xml", project("born elsewhere"), PersistenceStore.ABSENT);
            other.move("jobs/born", "jobs/born-renamed");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("born"));
        assertEquals("born elsewhere", r.jenkins.getItemByFullName("born-renamed", FreeStyleProject.class).getDescription());
    }

    @Test
    void aJobMovedIntoAFolderJustCreatedElsewhereKeepsItsIdentityAndBuilds() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template2")).readRawBytes();
        FreeStyleProject j = r.createFreeStyleProject("j");
        r.buildAndAssertSuccess(j);
        pauseApplier();
        try {
            other.write("jobs/newf/config.xml", folderXml, PersistenceStore.ABSENT);
            other.move("jobs/j", "jobs/newf/jobs/j");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("j"));
        MockFolder newf = r.jenkins.getItemByFullName("newf", MockFolder.class);
        assertEquals(1, newf.getItems().size());
        assertThat(newf.getItem("j"), sameInstance(j));
        assertNotNull(j.getLastBuild());
    }

    @Test
    void theOldNameOfAnItemRenamedElsewhereIsFree() throws Exception {
        r.createFreeStyleProject("vacated");
        other.move("jobs/vacated", "jobs/vacated-renamed");
        awaitApplied();
        assertEquals(PersistenceStore.ABSENT, PersistenceStores.lastSeen("jobs/vacated/config.xml"));
        PersistenceStores.CONFLICTS = "fail";
        try {
            r.createFreeStyleProject("vacated");
        } finally {
            PersistenceStores.CONFLICTS = "warn";
        }
    }

    @Test
    void aFieldClearedElsewhereIsClearedHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("named");
        p.setDisplayName("Shown");
        other.write("jobs/named/config.xml", project("display name cleared elsewhere"), PersistenceStore.ANY);
        awaitApplied();
        assertEquals("display name cleared elsewhere", p.getDescription());
        assertNull(p.getDisplayNameOrNull());
    }

    @Test
    void aFieldRemovedByAVersionStoredWhileReloadingIsCleared() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("twice");
        p.setDisplayName("Shown");
        // the version stored next keeps the display name; the one after it, stored between the read finding what that
        // version removed and the read loading it, has it removed
        beforeLoading = () -> {
            try {
                other.write("jobs/twice/config.xml", project("second"), PersistenceStore.ANY);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        other.write("jobs/twice/config.xml", ("<?xml version='1.1' encoding='UTF-8'?><project><description>first</description>"
                + "<displayName>Shown</displayName></project>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        awaitApplied();
        assertNull(beforeLoading);
        assertEquals("second", p.getDescription());
        assertNull(p.getDisplayNameOrNull());
    }

    @Test
    void aMoveOfAnItemMadeAgainHereSinceLeavesThatAlone() throws Exception {
        pauseApplier();
        FreeStyleProject a;
        try {
            other.write("jobs/remade-here/config.xml", project("moved elsewhere"), PersistenceStore.ABSENT);
            other.move("jobs/remade-here", "jobs/remade-here-moved");
            a = r.createFreeStyleProject("remade-here");
            a.setDescription("made again here");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertThat(r.jenkins.getItem("remade-here"), sameInstance(a));
        assertEquals("made again here", a.getDescription());
        assertEquals("moved elsewhere", r.jenkins.getItemByFullName("remade-here-moved", FreeStyleProject.class).getDescription());
    }

    @Test
    void aJobChangedElsewhereJustBeforeItIsMovedIntoAFolderJustCreatedIsReloaded() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template3")).readRawBytes();
        FreeStyleProject j = r.createFreeStyleProject("j3");
        pauseApplier();
        try {
            other.write("jobs/j3/config.xml", project("changed before the move"), PersistenceStore.ANY);
            other.write("jobs/newf3/config.xml", folderXml, PersistenceStore.ABSENT);
            other.move("jobs/j3", "jobs/newf3/jobs/j3");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertThat(r.jenkins.getItemByFullName("newf3/j3"), sameInstance(j));
        assertEquals("changed before the move", j.getDescription());
    }

    @Test
    void aChildChangedElsewhereJustBeforeItsFolderIsMovedIntoAFolderJustCreatedIsReloaded() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template4")).readRawBytes();
        MockFolder f = r.createFolder("mf");
        FreeStyleProject x = f.createProject(FreeStyleProject.class, "x");
        pauseApplier();
        try {
            other.write("jobs/mf/jobs/x/config.xml", project("child changed before the move"), PersistenceStore.ANY);
            other.write("jobs/md/config.xml", folderXml, PersistenceStore.ABSENT);
            other.move("jobs/mf", "jobs/md/jobs/mf");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertThat(r.jenkins.getItemByFullName("md/mf"), sameInstance(f));
        assertThat(r.jenkins.getItemByFullName("md/mf/x"), sameInstance(x));
        assertEquals("child changed before the move", x.getDescription());
    }

    @Test
    void aMoveIntoAFolderRenamedElsewhereSinceLeavesNoCopyBehind() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template5")).readRawBytes();
        r.createFreeStyleProject("j5");
        pauseApplier();
        try {
            other.write("jobs/f5/config.xml", folderXml, PersistenceStore.ABSENT);
            other.move("jobs/j5", "jobs/f5/jobs/j5");
            other.move("jobs/f5", "jobs/g5");
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("j5"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/j5").exists());
        assertNull(r.jenkins.getItem("f5"));
        assertNotNull(r.jenkins.getItemByFullName("g5/j5", FreeStyleProject.class));
    }

    /** While set, the first save of item "race" changes it elsewhere and waits for the applier to try adding it. */
    static volatile boolean racing;

    @TestExtension("anItemCreatedHereAndChangedElsewhereBeforeItIsAddedIsKept")
    public static class ChangeElsewhereWhileCreating extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (racing && o instanceof FreeStyleProject p && p.getName().equals("race")) {
                racing = false;
                try {
                    STORE.as("other-controller").write("jobs/race/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
                    // until the applier, having loaded it, waits to add it
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!applierBlocked() && System.nanoTime() < deadline) {
                        Thread.sleep(20);
                    }
                } catch (IOException | InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }

        private static boolean applierBlocked() {
            return Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().equals("Persistence store item change applier") && t.getState() == Thread.State.BLOCKED);
        }
    }

    @Test
    void anItemCreatedHereAndChangedElsewhereBeforeItIsAddedIsKept() throws Exception {
        racing = true;
        FreeStyleProject p = r.createFreeStyleProject("race");
        awaitApplied();
        assertThat(r.jenkins.getItem("race"), sameInstance(p));
        assertTrue(p.getRootDir().isDirectory());
        assertEquals("changed elsewhere", p.getDescription());
    }

    /** While set, saves of folder "lockf" wait for it once written. */
    static volatile CountDownLatch saving;
    static volatile CountDownLatch savingEntered;

    @TestExtension("aFolderIsReloadedForAChildCreatedElsewhereOnlyBetweenItsSaves")
    public static class BlockFolderSave extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            CountDownLatch l = saving;
            if (l != null && o instanceof MockFolder f && f.getName().equals("lockf")) {
                savingEntered.countDown();
                try {
                    l.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void aFolderIsReloadedForAChildCreatedElsewhereOnlyBetweenItsSaves() throws Exception {
        MockFolder f = r.createFolder("lockf");
        saving = new CountDownLatch(1);
        savingEntered = new CountDownLatch(1);
        Thread saver = new Thread(() -> {
            try {
                f.setDescription("saved here");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            saver.start();
            assertTrue(savingEntered.await(30, TimeUnit.SECONDS));
            other.write("jobs/lockf/jobs/c/config.xml", project("child"), PersistenceStore.ABSENT);
            Thread.sleep(500);
            assertNull(r.jenkins.getItemByFullName("lockf/c"), "the folder was reloaded during its save");
        } finally {
            CountDownLatch l = saving;
            saving = null;
            l.countDown();
        }
        saver.join();
        awaitApplied();
        assertNotNull(r.jenkins.getItemByFullName("lockf/c"));
    }

    @TestExtension("subscribesBeforeItemsAreLoaded")
    public static class SubscribedCheck {
        volatile Boolean subscribedBeforeGlobalConfiguration;

        @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED, before = InitMilestone.SYSTEM_CONFIG_ADAPTED)
        public void check() {
            subscribedBeforeGlobalConfiguration = ItemChangeApplier.isSubscribed();
        }
    }

    @Test
    void subscribesBeforeItemsAreLoaded() {
        // item loading requires SYSTEM_CONFIG_ADAPTED
        assertEquals(Boolean.TRUE, r.jenkins.getExtensionList(SubscribedCheck.class).getFirst().subscribedBeforeGlobalConfiguration);
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
