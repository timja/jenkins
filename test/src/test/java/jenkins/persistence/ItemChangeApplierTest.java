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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractItem;
import hudson.model.Failure;
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
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Changes made by another controller (simulated by a store view with another origin) are applied here.
 */
@WithJenkins
class ItemChangeApplierTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore() {
        @Override
        public Entry read(String key) throws IOException {
            Runnable r = beforeLoading;
            // an item reloaded field by field reads its file once, to prepare the reload: that read counts as loading
            if (r != null && StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals("load")
                    && f.getClassName().equals(AbstractItem.class.getName())
))) {
                beforeLoading = null;
                r.run();
            }
            String from = unreachableFrom;
            if (key.equals(unreachableKey) && from != null && StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getMethodName().equals(from)))) {
                unreachableKey = null;
                throw ItemChangeApplierTest.<RuntimeException>sneaky(new IOException("the store is unreachable, this time"));
            }
            return super.read(key);
        }
    };

    /** While set, run once as an item is next loaded here, just before its file is read. */
    static volatile Runnable beforeLoading;

    /** A key whose next read made from a method of that name fails, as from a store unreachable for a moment. */
    static volatile String unreachableKey;

    static volatile String unreachableFrom;

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t; // an IOException, as a store throws, from a read that does not declare it
    }

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
        p.setDescription("then here");
        assertThat(text("jobs/p/config.xml"), containsString("<description>then here</description>"));
    }

    private static String text(String key) throws IOException {
        return new String(STORE.read(key).content(), StandardCharsets.UTF_8);
    }

    private static byte[] withQuietPeriod(String xml, int seconds) {
        return xml.replace("</project>", "  <quietPeriod>" + seconds + "</quietPeriod>\n</project>").getBytes(StandardCharsets.UTF_8);
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
        other.move("jobs/old-name", "jobs/new-name", java.util.List.of());
        awaitApplied();
        assertNull(r.jenkins.getItem("old-name"));
        assertThat(r.jenkins.getItem("new-name"), sameInstance(p));
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/new-name").isDirectory());
        assertTrue(STORE.versions().containsKey("jobs/new-name/config.xml"), "rename was not re-done locally against the store");
    }

    @Test
    void aKeyNamingNoFileIsIgnored() throws Exception {
        MockFolder f = r.createFolder("dots");
        FreeStyleProject v = r.createFreeStyleProject("victim");
        // not written by a controller: ".." below the folder is the folder's own directory
        other.move("jobs/victim", "jobs/dots/jobs/..", java.util.List.of());
        other.write("jobs/../config.xml", project("not an item"), PersistenceStore.ANY);
        awaitApplied();
        assertThat(r.jenkins.getItem("victim"), sameInstance(v));
        assertThat(r.jenkins.getItem("dots"), sameInstance(f));
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/victim").isDirectory());
        assertTrue(new File(r.jenkins.getRootDir(), "jobs/dots").isDirectory());
        other.move("jobs/dots/jobs/..", "jobs/victim", java.util.List.of()); // as it was, for the other tests
    }

    @Test
    void aChangeElsewhereWhoseCheckFailsWhileTheStoreIsUnreachableIsAppliedByTheRetry() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("outage");
        unreachableFrom = "written";
        unreachableKey = "jobs/outage/config.xml";
        other.write("jobs/outage/config.xml", project("changed during an outage"), PersistenceStore.ANY);
        awaitApplied();
        assertNull(unreachableKey, "the check failed");
        assertEquals("changed during an outage", p.getDescription(), "not taken for deleted meanwhile");
    }

    @Test
    void aChildMadeElsewhereWhileTheStoreIsUnreachableIsLoadedByTheRetry() throws Exception {
        MockFolder f = r.createFolder("outage-folder");
        unreachableFrom = "loadChildren";
        unreachableKey = "jobs/outage-folder/jobs/c/config.xml";
        other.write("jobs/outage-folder/jobs/c/config.xml", project("child"), PersistenceStore.ABSENT);
        awaitApplied();
        assertNull(unreachableKey, "the check failed");
        assertNotNull(f.getItem("c"), "not dropped from the folder");
    }

    @Test
    void movedIntoFolderElsewhere() throws Exception {
        r.createFolder("dest");
        FreeStyleProject p = r.createFreeStyleProject("traveller");
        other.move("jobs/traveller", "jobs/dest/jobs/traveller", java.util.List.of());
        awaitApplied();
        assertNull(r.jenkins.getItem("traveller"));
        assertNotNull(r.jenkins.getItemByFullName("dest/traveller"));
    }

    @Test
    void changesWhoseEventsWereLostAreAppliedByTheCatchUp() throws Exception {
        FreeStyleProject changed = r.createFreeStyleProject("lost-change");
        FreeStyleProject deleted = r.createFreeStyleProject("lost-deletion");
        MockFolder f = r.createFolder("lost-folder");
        FreeStyleProject child = f.createProject(FreeStyleProject.class, "child");
        STORE.setLosingEvents(true);
        try {
            other.write("jobs/lost-change/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
            other.delete("jobs/lost-deletion/config.xml", PersistenceStore.ANY);
            other.write("jobs/lost-creation/config.xml", project("made elsewhere"), PersistenceStore.ABSENT);
            other.write("jobs/lost-folder/jobs/child/config.xml", project("child changed elsewhere"), PersistenceStore.ANY);
        } finally {
            STORE.setLosingEvents(false);
        }
        awaitApplied();
        assertNull(changed.getDescription(), "nothing arrived");
        ItemChangeApplier.catchUpNow();
        awaitApplied();
        assertEquals("changed elsewhere", changed.getDescription());
        assertSame(changed, r.jenkins.getItem("lost-change"));
        assertNull(r.jenkins.getItem("lost-deletion"));
        assertFalse(deleted.getRootDir().exists());
        FreeStyleProject made = r.jenkins.getItemByFullName("lost-creation", FreeStyleProject.class);
        assertNotNull(made);
        assertEquals("made elsewhere", made.getDescription());
        assertEquals("child changed elsewhere", child.getDescription());
        // nothing more to do the next time
        long applied = ItemChangeApplier.getAppliedCount();
        ItemChangeApplier.catchUpNow();
        awaitApplied();
        assertEquals(applied, ItemChangeApplier.getAppliedCount());
    }

    @Test
    void aMoveElsewhereOverADirectoryLeftHereIsApplied() throws Exception {
        MockFolder dest = r.createFolder("move-dest");
        FreeStyleProject p = r.createFreeStyleProject("mover");
        // left behind by an item of that name deleted elsewhere while this controller was down, say
        File stale = new File(dest.getRootDir(), "jobs/mover");
        assertTrue(new File(stale, "builds/7").mkdirs());
        Files.writeString(new File(stale, "builds/7/build.xml").toPath(), "<build/>");
        other.move("jobs/mover", "jobs/move-dest/jobs/mover", java.util.List.of());
        awaitApplied();
        assertSame(p, r.jenkins.getItemByFullName("move-dest/mover"));
        assertNull(r.jenkins.getItem("mover"));
        File[] aside = dest.getRootDir().toPath().resolve("jobs").toFile().listFiles((d, n) -> n.startsWith("mover.aside-"));
        assertNotNull(aside);
        assertEquals(1, aside.length, "what was there is kept aside");
        assertTrue(new File(aside[0], "builds/7/build.xml").isFile());
        assertFalse(new File(p.getRootDir(), "builds/7").exists(), "not merged into the moved item");
    }

    @Test
    void aDeletionRacingAMoveElsewhereDeletesNothing() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("racer");
        r.buildAndAssertSuccess(p);
        synchronized (p) { // the rename made elsewhere is being applied, and waits for the item's lock
            other.move("jobs/racer", "jobs/racer-renamed", java.util.List.of());
            awaitApplierBlockedOn(p);
            assertThrows(Failure.class, () -> p.doDoDelete((StaplerRequest2) null, null));
        }
        awaitApplied();
        assertSame(p, r.jenkins.getItem("racer-renamed"));
        assertNotNull(p.getBuildByNumber(1), "its builds are still there");
        assertTrue(new File(p.getRootDir(), "builds/1").isDirectory());
        assertNotNull(STORE.read("jobs/racer-renamed/config.xml"));
    }

    private static void awaitApplierBlockedOn(Object lock) throws InterruptedException {
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            for (java.lang.management.ThreadInfo info : threads.dumpAllThreads(false, false)) {
                if (info.getThreadName().equals("Persistence store change applier") && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockInfo() != null && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(lock)) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the applier never waited for " + lock);
            }
            Thread.sleep(20);
        }
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
    void aDeletionHereOfAnItemDeletedElsewhereMeanwhileIsRefusedAndTheNameCanBeUsedOnceItIsApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("again");
        pauseApplier();
        try {
            other.delete("jobs/again/config.xml", PersistenceStore.ANY);
            // not stored any more, which might as well be a rename elsewhere: nothing is deleted here (its builds)
            assertThrows(ConflictException.class, p::delete);
            assertTrue(p.getRootDir().isDirectory());
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("again"), "the deletion made elsewhere is applied");
        r.createFreeStyleProject("again").setDescription("made again here");
        awaitApplied();
        FreeStyleProject again = r.jenkins.getItemByFullName("again", FreeStyleProject.class);
        assertNotNull(again);
        assertEquals("made again here", again.getDescription());
        assertTrue(again.getRootDir().isDirectory());
    }

    @Test
    void anItemDeletedAndMadeAgainElsewhereIsAnotherOne() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("remade");
        r.buildAndAssertSuccess(p);
        pauseApplier();
        try {
            other.delete("jobs/remade/config.xml", PersistenceStore.ANY);
            other.write("jobs/remade/config.xml", project("made again elsewhere"), PersistenceStore.ABSENT);
        } finally {
            resumeApplier();
        }
        awaitApplied();
        FreeStyleProject again = r.jenkins.getItemByFullName("remade", FreeStyleProject.class);
        assertNotNull(again);
        assertEquals("made again elsewhere", again.getDescription());
        assertNotSame(p, again, "not the deleted job reloaded");
        assertTrue(again.getBuilds().isEmpty(), "without the deleted job's builds");
    }

    @Test
    void aChildOfAFolderRenamedElsewhereIsStillCheckedForConflicts() throws Exception {
        MockFolder f = r.createFolder("rf");
        FreeStyleProject c = f.createProject(FreeStyleProject.class, "c");
        other.move("jobs/rf", "jobs/rf2", java.util.List.of());
        awaitApplied();
        assertThat(r.jenkins.getItemByFullName("rf2/c"), sameInstance(c));
        pauseApplier();
        try {
            other.write("jobs/rf2/jobs/c/config.xml", project("changed elsewhere"), PersistenceStore.ANY);
            assertThrows(ConflictException.class, () -> c.setDescription("here"));
        } finally {
            resumeApplier();
        }
    }

    @Test
    void aChangeMadeElsewhereJustBeforeARenameIsApplied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("edited");
        pauseApplier();
        try {
            other.write("jobs/edited/config.xml", project("edited elsewhere"), PersistenceStore.ANY);
            other.move("jobs/edited", "jobs/edited-renamed", java.util.List.of());
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
            other.move("jobs/born", "jobs/born-renamed", java.util.List.of());
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("born"));
        assertEquals("born elsewhere", r.jenkins.getItemByFullName("born-renamed", FreeStyleProject.class).getDescription());
    }

    @Test
    void anItemRenamedAndThenSavedElsewhereIsMovedNotLoadedAgain() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("before-rename");
        pauseApplier();
        try {
            other.move("jobs/before-rename", "jobs/after-rename", java.util.List.of());
            other.write("jobs/after-rename/config.xml", project("saved after the rename"), PersistenceStore.ANY);
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(r.jenkins.getItem("before-rename"), "moved, not left at its old name");
        assertThat(r.jenkins.getItem("after-rename"), sameInstance(p));
        assertEquals("saved after the rename", p.getDescription());
    }

    /** The name of an item whose next deletion fails, as a deletion that cannot be applied for now would. */
    static volatile String refuseDeletion;

    @TestExtension("aDeletionRetriedAfterTheItemWasMadeAgainDoesNotDeleteIt")
    public static class RefuseDeletion extends ItemListener {
        @Override
        public void onCheckDelete(Item item) {
            if (item.getName().equals(refuseDeletion)) {
                refuseDeletion = null;
                throw new hudson.model.Failure("cannot be deleted now");
            }
        }
    }

    @Test
    void aDeletionRetriedAfterTheItemWasMadeAgainDoesNotDeleteIt() throws Exception {
        FreeStyleProject old = r.createFreeStyleProject("retried");
        r.buildAndAssertSuccess(old);
        pauseApplier();
        try {
            refuseDeletion = "retried";
            other.delete("jobs/retried/config.xml", PersistenceStore.ANY);
            other.write("jobs/retried/config.xml", project("made again elsewhere"), PersistenceStore.ABSENT);
        } finally {
            resumeApplier();
        }
        awaitApplied();
        assertNull(refuseDeletion, "the deletion failed once");
        FreeStyleProject again = r.jenkins.getItemByFullName("retried", FreeStyleProject.class);
        assertNotNull(again, "not deleted by the deletion retried after it was made again");
        assertEquals("made again elsewhere", again.getDescription());
        // the failed deletion was applied before the item was made again: the new one is another item
        assertThat(again, not(sameInstance(old)));
        assertThat(again.getBuilds(), empty());
        assertEquals(1, again.getNextBuildNumber());
    }

    @Test
    void aJobMovedIntoAFolderJustCreatedElsewhereKeepsItsIdentityAndBuilds() throws Exception {
        byte[] folderXml = Items.getConfigFile(r.createFolder("template2")).readRawBytes();
        FreeStyleProject j = r.createFreeStyleProject("j");
        r.buildAndAssertSuccess(j);
        pauseApplier();
        try {
            other.write("jobs/newf/config.xml", folderXml, PersistenceStore.ABSENT);
            other.move("jobs/j", "jobs/newf/jobs/j", java.util.List.of());
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
        other.move("jobs/vacated", "jobs/vacated-renamed", java.util.List.of());
        awaitApplied();
        assertEquals(PersistenceStore.ABSENT, StoredVersions.lastSeen("jobs/vacated/config.xml"));
        try {
            r.createFreeStyleProject("vacated");
        } finally {
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
    void aMoveOfAnItemMadeAgainHereSinceLeavesThatAlone() throws Exception {
        pauseApplier();
        FreeStyleProject a;
        try {
            other.write("jobs/remade-here/config.xml", project("moved elsewhere"), PersistenceStore.ABSENT);
            other.move("jobs/remade-here", "jobs/remade-here-moved", java.util.List.of());
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
            other.move("jobs/j3", "jobs/newf3/jobs/j3", java.util.List.of());
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
            other.move("jobs/mf", "jobs/md/jobs/mf", java.util.List.of());
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
            other.move("jobs/j5", "jobs/f5/jobs/j5", java.util.List.of());
            other.move("jobs/f5", "jobs/g5", java.util.List.of());
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

    @Test
    void keysCoreDoesNotRouteReachTheHandlers() throws Exception {
        Handler.changes.clear();
        other.write("_items/0123/builds/1/build.xml", project("x"), PersistenceStore.ABSENT);
        other.delete("_items/0123/builds/1/build.xml", PersistenceStore.ANY);
        STORE.write("_items/0123/builds/2/build.xml", project("own"), PersistenceStore.ABSENT); // made here: not handed off
        other.write("jobs/handled/builds/1/build.xml", project("x"), PersistenceStore.ABSENT);
        awaitApplied();
        assertEquals(java.util.List.of("_items/0123/builds/1/build.xml false", "_items/0123/builds/1/build.xml true",
                "jobs/handled/builds/1/build.xml false"), Handler.changes);
    }

    @TestExtension("keysCoreDoesNotRouteReachTheHandlers")
    public static final class Handler implements RemoteChangeHandler {
        static final java.util.List<String> changes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean handles(@NonNull String key) {
            return !key.startsWith("jobs/pause/");
        }

        @Override
        public void changed(@NonNull String key, boolean deleted) {
            assertTrue(PersistenceStores.isApplyingRemoteChange());
            changes.add(key + " " + deleted);
        }
    }

    @Test
    void somethingConfiguredBelowAnItemElsewhereReloadsTheItem() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("owner");
        other.write("jobs/owner/promotions/release/config.xml", "<process/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        awaitApplied();
        assertTrue(new File(p.getRootDir(), "promotions/release").isDirectory());
        assertSame(p, r.jenkins.getItem("owner"));
    }

    /** Static state stays local: the store shares items only, and outlives each test's Jenkins. */
    @TestExtension
    public static final class ItemsOnly implements LocalFiles {
        @Override
        public java.util.Collection<String> patterns() {
            return java.util.List.of("*.xml", "users/**", "nodes/**", "log/*");
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
