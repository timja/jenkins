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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Renames and moves carry the stored configuration along.
 */
@WithJenkins
class PersistenceStoreItemLifecycleTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/")) {
        @Override
        public List<Moved> move(@NonNull String fromPrefix, @NonNull String toPrefix) throws ConflictException {
            List<Moved> moved = super.move(fromPrefix, toPrefix);
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
    void renamedItemIsStillCheckedForConflicts() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("checked-before");
        p.renameTo("checked-after");
        // another controller changes the renamed item
        STORE.write("jobs/checked-after/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        PersistenceStores.CONFLICTS = "fail";
        try {
            assertThrows(ConflictException.class, () -> p.setDescription("here"));
        } finally {
            PersistenceStores.CONFLICTS = "warn";
        }
    }

    @Test
    void anItemChangedElsewhereBeforeItsRenameStillConflicts() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("stale-before");
        // another controller changes it; this one has not seen that
        STORE.write("jobs/stale-before/config.xml", "<?xml version='1.1' encoding='UTF-8'?><project><description>elsewhere</description></project>"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        p.renameTo("stale-after");
        PersistenceStores.CONFLICTS = "fail";
        try {
            assertThrows(ConflictException.class, () -> p.setDescription("here"), "not taken as seen by the move");
        } finally {
            PersistenceStores.CONFLICTS = "warn";
        }
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
    void moveIntoItselfIsRefused() throws Exception {
        MockFolder f = r.createFolder("self");
        MockFolder g = f.createProject(MockFolder.class, "g");
        Map<String, Long> before = STORE.versions();
        assertThrows(IllegalArgumentException.class, () -> Items.move(f, g));
        assertThrows(IllegalArgumentException.class, () -> PersistenceStores.move(f.getRootDir(), new File(g.getRootDir(), "jobs/self")));
        assertEquals(before, STORE.versions());
        assertEquals(f, r.jenkins.getItem("self"));
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
        PersistenceStores.Route old = PersistenceStores.route(PersistenceStores.fileFor("jobs/vacated/config.xml"));
        PersistenceStores.CONFLICTS = "fail";
        try {
            // as a save resolving the old name would
            assertThrows(ConflictException.class, () -> old.write("<project/>".getBytes(StandardCharsets.UTF_8)));
        } finally {
            PersistenceStores.CONFLICTS = "warn";
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
