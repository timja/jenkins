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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Saveable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Local state in a cluster (Target 15): copies of stored files written to directly are written back; files declared
 * per controller stay local; sub-items of jobs are shared and reload their job.
 */
@WithJenkins
class LocalStateTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> key.startsWith("jobs/") && key.endsWith("config.xml")
            && !key.contains("/builds/") || !key.contains("/") && key.endsWith(".xml"));

    private static String stored(String key) {
        PersistenceStore.Entry e = STORE.read(key);
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8);
    }

    @Test
    void aCopyWrittenDirectlyIsWrittenBack(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("direct");
        p.setDescription("saved");
        File f = p.getConfigFile().getFile(); // a plugin takes the file...
        Files.writeString(f.toPath(), Files.readString(f.toPath()).replace("saved", "written directly"));
        f.setLastModified(System.currentTimeMillis() + 2000);
        // ...and writes it itself; nothing reads it back until the next getFile, or the periodic write-back
        assertThat(stored("jobs/direct/config.xml"), containsString("saved"));
        p.getConfigFile().getFile();
        assertThat(stored("jobs/direct/config.xml"), containsString("written directly"));
    }

    @Test
    void aCopyChangedOnBothSidesIsMerged(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("both");
        File f = p.getConfigFile().getFile();
        String local = Files.readString(f.toPath()).replaceFirst("(<project[^>]*>)", "$1<displayName>here</displayName>");
        // meanwhile another controller changes the description
        String theirs = stored("jobs/both/config.xml").replaceFirst("<description>[^<]*</description>|<description/>", "")
                .replaceFirst("(<project[^>]*>)", "$1<description>elsewhere</description>");
        STORE.as("other-controller").write("jobs/both/config.xml", theirs.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        Files.writeString(f.toPath(), local);
        f.setLastModified(System.currentTimeMillis() + 2000);
        hudson.model.AsyncPeriodicWork.all().get(MaterializedFiles.WriteBack.class).execute(hudson.model.TaskListener.NULL);
        String now = stored("jobs/both/config.xml");
        assertThat(now, containsString("<displayName>here</displayName>"));
        assertThat(now, containsString("<description>elsewhere</description>"));
    }

    @Test
    void aChangeMadeElsewhereJustAfterAWriteBackIsNotWrittenOver(JenkinsRule r) throws Exception {
        String key = "write-back-test.xml";
        XmlFile xf = new XmlFile(new File(r.jenkins.getRootDir(), key));
        xf.write("original");
        File f = xf.getFile();
        Files.writeString(f.toPath(), "<?xml version='1.1' encoding='UTF-8'?>\n<string>here</string>");
        f.setLastModified(System.currentTimeMillis() + 2000);
        PersistenceStore other = STORE.as("other-controller");
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        // another controller writes just after this one wrote the copy back
        AutoCloseable subscription = STORE.subscribe(e -> {
            if (e.key().equals(key) && e.origin().equals(STORE.getOrigin()) && armed.getAndSet(false)) {
                try {
                    other.write(key, "<?xml version='1.1' encoding='UTF-8'?>\n<string>elsewhere</string>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                } catch (java.io.IOException x) {
                    throw new AssertionError(x);
                }
            }
        });
        try {
            // with the copy's lock, so that it is not refreshed from the change made elsewhere meanwhile
            synchronized (MaterializedFiles.lockFor(key)) {
                hudson.model.AsyncPeriodicWork.all().get(MaterializedFiles.WriteBack.class).execute(hudson.model.TaskListener.NULL);
                assertFalse(armed.get());
                assertThat(stored(key), containsString("elsewhere"));
                hudson.model.AsyncPeriodicWork.all().get(MaterializedFiles.WriteBack.class).execute(hudson.model.TaskListener.NULL);
            }
        } finally {
            subscription.close();
        }
        assertThat("not written over: the copy is what was written back, not what was stored after", stored(key), containsString("elsewhere"));
    }

    @Test
    void aCopyBeingRefreshedIsNotWrittenBack(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("refreshed");
        String key = "jobs/refreshed/config.xml";
        PersistenceStore other = STORE.as("other-controller");
        List<String> writtenHere = new java.util.concurrent.CopyOnWriteArrayList<>();
        AutoCloseable subscription = STORE.subscribe(e -> {
            if (e.key().equals(key) && e.origin().equals(STORE.getOrigin())) {
                writtenHere.add(e.toString());
            }
        });
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writeBack = new Thread(() -> {
            while (!done.get()) {
                hudson.model.AsyncPeriodicWork.all().get(MaterializedFiles.WriteBack.class).execute(hudson.model.TaskListener.NULL);
            }
        });
        writeBack.start();
        try {
            long end = System.currentTimeMillis() + 2000;
            for (int i = 0; System.currentTimeMillis() < end; i++) {
                // changed elsewhere, then the copy refreshed here: nothing is changed here, so nothing is written back
                String description = String.valueOf((char) ('a' + i % 3)).repeat(100_000);
                other.write(key, ("<?xml version='1.1' encoding='UTF-8'?><project><description>" + description + "</description></project>")
                        .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                p.getConfigFile().getFile();
            }
        } finally {
            done.set(true);
            writeBack.join();
            subscription.close();
        }
        assertEquals(List.of(), writtenHere);
    }

    @Test
    void declaredFilesStayLocal(JenkinsRule r) throws Exception {
        assertTrue(PersistenceStores.declaredLocal("queue.xml"), "core's own");
        assertTrue(PersistenceStores.declaredLocal("jenkins.install.InstallUtil.lastExecVersion"));
        assertTrue(PersistenceStores.declaredLocal("jobs/x/my-plugin-state.xml"), "a LocalFiles pattern");
        assertFalse(PersistenceStores.declaredLocal("jobs/x/config.xml"));
        assertTrue(PersistenceStores.declaredLocal(Stats.class.getName() + ".xml"), "the root file of a @PerController class");
        assertFalse(PersistenceStores.declaredLocal("hudson.tasks.Mailer.xml"));
        new Stats().save();
        assertNull(stored(Stats.class.getName() + ".xml"), "not shared");
        assertTrue(new File(r.jenkins.getRootDir(), Stats.class.getName() + ".xml").isFile(), "kept in this controller's home");
        // and by instance, wherever its file is
        Stats elsewhere = new Stats();
        XmlFile file = new XmlFile(new File(r.jenkins.getRootDir(), "jobs/x/config.xml"));
        file.write(elsewhere);
        assertNull(stored("jobs/x/config.xml"), "an object of a @PerController class is kept local by its file");
    }

    @Test
    void subItemsReloadTheirJob(JenkinsRule r) throws Exception {
        assertEquals("jobs/m/config.xml", SubItems.ownerKey("jobs/m/configurations/axis-a/1/config.xml"));
        assertEquals("jobs/f/jobs/p/config.xml", SubItems.ownerKey("jobs/f/jobs/p/promotions/release/config.xml"));
        assertNull(SubItems.ownerKey("jobs/m/config.xml"));
        assertNull(ItemChangeApplier.itemPath("jobs/p/promotions/release/config.xml"), "not an item called p/release");
        assertEquals(List.of("f", "p"), ItemChangeApplier.itemPath("jobs/f/jobs/p/config.xml"));
        assertEquals(List.of("mb", "main"), ItemChangeApplier.itemPath("jobs/mb/branches/main/config.xml"), "multibranch children");
        FreeStyleProject p = r.createFreeStyleProject("promoted");
        STORE.as("other-controller").write("jobs/promoted/promotions/release/config.xml",
                "<?xml version='1.1' encoding='UTF-8'?><process/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        File dir = new File(p.getRootDir(), "promotions/release");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!dir.isDirectory() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(dir.isDirectory(), "its directory is there for the plugin to list");
        // a fresh home: the directory is created when the job loads
        hudson.Util.deleteRecursive(dir);
        p.onLoad(r.jenkins, "promoted");
        assertTrue(dir.isDirectory());
    }

    @PerController
    public static final class Stats implements Saveable {
        int count = 1;

        @Override
        public void save() throws java.io.IOException {
            new XmlFile(new File(Jenkins.get().getRootDir(), Stats.class.getName() + ".xml")).write(this);
        }
    }

    @TestExtension
    public static final class Declared implements LocalFiles {
        @Override
        public Collection<String> patterns() {
            return List.of("jobs/**/my-plugin-state.xml");
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
