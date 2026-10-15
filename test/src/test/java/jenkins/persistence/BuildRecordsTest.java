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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.tools.tar.TarConstants;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.apache.tools.tar.TarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Completed builds are published to the stores, and a controller with an empty home lists and opens them.
 */
@WithJenkins
class BuildRecordsTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && (key.endsWith("/config.xml") || key.endsWith("/nextBuildNumber")) && !key.contains("/builds/")) {
        @Override
        public boolean storesBuildRecords(String jobKey) {
            return true;
        }

        @Override
        public Set<String> liveOrigins() {
            return live;
        }

        @Override
        public Entry read(String key) {
            Entry e = super.read(key);
            Consumer<String> hook = afterRead;
            if (hook != null) {
                hook.accept(key);
            }
            return e;
        }

        @Override
        public long write(String key, byte[] content, long expectedVersion) throws ConflictException {
            Consumer<String> hook = beforeWrite;
            if (hook != null) {
                hook.accept(key);
            }
            return super.write(key, content, expectedVersion);
        }
        public Set<String> children(String parentKey) {
            if (unlistable) {
                throw sneaky(new IOException("the store is unreachable")); // checked, as a real store throws it
            }
            return super.children(parentKey);
        }
    };
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    static volatile Consumer<String> afterRead;
    /** Origins the store reports as alive; null means membership is not tracked. */
    static volatile Set<String> live;
    static volatile Consumer<String> beforeWrite;
    static volatile boolean unlistable;
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore() {
        @Override
        public void move(String fromPrefix, String toPrefix) {
            if (failMove) {
                // moves one blob, then fails
                failMove = false;
                String first = keys().stream().filter(k -> k.startsWith(fromPrefix + "/")).findFirst().orElseThrow();
                super.move(first, toPrefix + first.substring(fromPrefix.length()));
                throw new IllegalStateException("connection lost");
            }
            super.move(fromPrefix, toPrefix);
        }
    };
    static volatile boolean failMove;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        BLOBS.clear();
    }

    /** Writes to the log, to a data file, and to {@code archive/}. */
    private static FreeStyleProject project(JenkinsRule r, String name) throws IOException {
        FreeStyleProject p = r.createFreeStyleProject(name);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException {
                listener.getLogger().println("hello from #" + build.getNumber());
                File data = new File(build.getRootDir(), "extra/data.txt");
                Files.createDirectories(data.getParentFile().toPath());
                Files.writeString(data.toPath(), "data of #" + build.getNumber());
                File archived = new File(build.getRootDir(), "archive/a.txt");
                Files.createDirectories(archived.getParentFile().toPath());
                Files.writeString(archived.toPath(), "artifact");
                return true;
            }
        });
        return p;
    }

    private static String record(String key) {
        PersistenceStore.Entry e = STORE.read(key);
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8);
    }

    private static void wipeAndReload(JenkinsRule r) throws Exception {
        Util.deleteRecursive(new File(r.jenkins.getRootDir(), "jobs"));
        r.jenkins.reload();
    }

    @Test
    void completedBuildIsPublished() throws Exception {
        FreeStyleProject p = project(r, "published");
        r.buildAndAssertSuccess(p);
        assertThat(record("jobs/published/builds/1/build.xml"), containsString("<result>SUCCESS</result>"));
        assertThat(BLOBS.keys(), contains("jobs/published/builds/1/files.tgz"));
        assertTrue(new File(p.getBuildByNumber(1).getRootDir(), "build.xml").isFile(), "build.xml is still written locally");
    }

    @Test
    void freshHomeListsAndOpensBuilds() throws Exception {
        FreeStyleProject p = project(r, "fresh");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.getBuildByNumber(1).setDescription("edited after completion");

        wipeAndReload(r);

        p = r.jenkins.getItemByFullName("fresh", FreeStyleProject.class);
        assertEquals(List.of(2, 1), p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        FreeStyleBuild b1 = p.getBuildByNumber(1);
        assertEquals(Result.SUCCESS, b1.getResult());
        assertEquals("edited after completion", b1.getDescription());
        assertThat(b1.getLog(), containsString("hello from #1"));
        assertEquals("data of #1", Files.readString(new File(b1.getRootDir(), "extra/data.txt").toPath()));
        assertFalse(new File(b1.getRootDir(), "archive").exists(), "artifacts are not part of the snapshot");
        assertEquals(2, p.getLastSuccessfulBuild().getNumber());
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(3, p.getLastBuild().getNumber());
    }

    @Test
    void linksAreLeftOutOfSnapshots(@TempDir Path outside) throws Exception {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "outside the build");
        Files.createDirectories(outside.resolve("dir"));
        Files.writeString(outside.resolve("dir/f.txt"), "outside the build too");
        FreeStyleProject p = r.createFreeStyleProject("links");
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException {
                Path root = build.getRootDir().toPath();
                Files.writeString(root.resolve("own.txt"), "the build's own");
                Files.createSymbolicLink(root.resolve("link.txt"), secret);
                Files.createSymbolicLink(root.resolve("linkdir"), outside.resolve("dir"));
                return true;
            }
        });
        r.buildAndAssertSuccess(p);

        List<String> names = new ArrayList<>();
        try (TarInputStream tar = new TarInputStream(new GZIPInputStream(BLOBS.get("jobs/links/builds/1/files.tgz")))) {
            for (TarEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                names.add(e.getName());
                assertFalse(e.isSymbolicLink(), e.getName());
                if (!e.isDirectory()) {
                    assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8), not(containsString("outside the build")));
                }
            }
        }
        assertThat(names, hasItem("own.txt"));

        wipeAndReload(r);
        File dir = r.jenkins.getItemByFullName("links", FreeStyleProject.class).getBuildByNumber(1).getRootDir();
        assertEquals("the build's own", Files.readString(new File(dir, "own.txt").toPath()));
        assertFalse(Files.exists(dir.toPath().resolve("link.txt"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(dir.toPath().resolve("linkdir"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void linksInAStoredSnapshotAreNotMaterialized(@TempDir Path outside) throws Exception {
        FreeStyleProject p = project(r, "planted");
        r.buildAndAssertSuccess(p);
        // a snapshot with a link in it, as no publisher here makes
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarOutputStream tar = new TarOutputStream(new GZIPOutputStream(bytes))) {
            TarEntry link = new TarEntry("log", TarConstants.LF_SYMLINK);
            link.setLinkName(outside.resolve("elsewhere").toString());
            tar.putNextEntry(link);
            tar.closeEntry();
        }
        BLOBS.put("jobs/planted/builds/1/files.tgz", new ByteArrayInputStream(bytes.toByteArray()), bytes.size());

        wipeAndReload(r);
        File dir = r.jenkins.getItemByFullName("planted", FreeStyleProject.class).getBuildByNumber(1).getRootDir();
        assertFalse(Files.isSymbolicLink(dir.toPath().resolve("log")));
    }

    @Test
    void anImportCanTellNoBuildsFromUnknown() throws Exception {
        FreeStyleProject p = project(r, "listed");
        r.buildAndAssertSuccess(p);
        assertEquals(Set.of(1), BuildRecords.listStoredNumbers(p));
        unlistable = true;
        try {
            assertThrows(IOException.class, () -> BuildRecords.listStoredNumbers(p));
            assertEquals(Set.of(), BuildRecords.storedNumbers(p), "where unknown is taken as none");
        } finally {
            unlistable = false;
        }
    }

    @Test
    void onlyOpenedBuildsAreMaterialized() throws Exception {
        FreeStyleProject p = project(r, "lazy");
        for (int i = 0; i < 3; i++) {
            r.buildAndAssertSuccess(p);
        }

        wipeAndReload(r);

        p = r.jenkins.getItemByFullName("lazy", FreeStyleProject.class);
        assertNotNull(p.getBuildByNumber(2));
        assertTrue(new File(p.getBuildDir(), "2/build.xml").isFile());
        assertFalse(new File(p.getBuildDir(), "1").exists(), "build 1 was never opened");
    }

    @Test
    void deletedBuildIsRemovedFromStores() throws Exception {
        FreeStyleProject p = project(r, "deleted");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.getBuildByNumber(1).delete();
        assertNull(record("jobs/deleted/builds/1/build.xml"));
        assertThat(BLOBS.keys(), contains("jobs/deleted/builds/2/files.tgz"));

        wipeAndReload(r);

        p = r.jenkins.getItemByFullName("deleted", FreeStyleProject.class);
        assertEquals(List.of(2), p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
    }

    @Test
    void buildDeletedWhileBeingRepublishedStaysDeleted() throws Exception {
        FreeStyleProject p = project(r, "redescribed");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        b.setDescription("edited here");
        String key = "jobs/redescribed/builds/1/build.xml";
        assertThat(record(key), containsString("edited here"));
        // another controller discards the build between this one reading its record and writing it again
        afterRead = k -> {
            if (k.equals(key)) {
                afterRead = null;
                try {
                    STORE.delete(k, PersistenceStore.ANY);
                } catch (IOException x) {
                    throw new UncheckedIOException(x);
                }
            }
        };
        try {
            BuildRecords.republish(b);
        } finally {
            afterRead = null;
        }
        assertNull(record(key), "not brought back by the republication");
    }

    @Test
    void buildDeletedWhileBeingPublishedStaysDeleted() throws Exception {
        FreeStyleProject p = project(r, "discarded");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        // a build discarder runs as soon as builds complete, possibly before or while one is being published
        BuildRecords.delete(b);
        BuildRecords.publish(b);
        assertNull(record("jobs/discarded/builds/1/build.xml"), "not brought back by a late publication");
        assertThat(BLOBS.keys(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("jobs/discarded/builds/1/files.tgz")));
    }

    @Test
    void buildDeletedWhileItsRecordIsWrittenAgainStaysDeleted() throws Exception {
        FreeStyleProject p = project(r, "saved");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = "jobs/saved/builds/1/build.xml";
        File local = new File(b.getRootDir(), "build.xml");
        AtomicInteger writes = new AtomicInteger();
        beforeWrite = k -> {
            if (k.equals(key)) {
                try {
                    if (writes.incrementAndGet() == 1) {
                        // saved here while being published: the record is written again, with the change
                        Files.writeString(local.toPath(), Files.readString(local.toPath()) + "<!-- saved -->");
                    } else {
                        // and the build is discarded just before that second write
                        beforeWrite = null;
                        BuildRecords.delete(b);
                    }
                } catch (IOException x) {
                    throw new UncheckedIOException(x);
                }
            }
        };
        try {
            BuildRecords.publish(b);
        } finally {
            beforeWrite = null;
        }
        assertEquals(2, writes.get(), "written again");
        assertNull(record(key), "not brought back by the second write");
        assertThat(BLOBS.keys(), org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("jobs/saved/builds/1/files.tgz")));
    }

    @Test
    void buildDeletedWhilePublishedForLongerThanMarkersAreKeptStaysDeleted() throws Exception {
        FreeStyleProject p = project(r, "stalled");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(p);
        FreeStyleBuild b2 = r.buildAndAssertSuccess(p);
        String key = "jobs/stalled/builds/1/build.xml";
        long kept = BuildRecords.DELETED_MILLIS;
        // the build is discarded as its record is being written, and by the time the write is done (a stalled write)
        // its marker is old enough to go when another build is deleted
        beforeWrite = k -> {
            if (k.equals(key)) {
                beforeWrite = null;
                try {
                    BuildRecords.delete(b1);
                    BuildRecords.DELETED_MILLIS = -1;
                    BuildRecords.delete(b2);
                } catch (IOException x) {
                    throw new UncheckedIOException(x);
                }
            }
        };
        try {
            BuildRecords.publish(b1);
        } finally {
            beforeWrite = null;
            BuildRecords.DELETED_MILLIS = kept;
        }
        assertNull(record(key), "not brought back by the publication still running");
    }

    @Test
    void aBuildOfAJobMadeAgainUnderTheNameOfOneWhoseBuildWasDeletedIsPublished() throws Exception {
        FreeStyleProject p = project(r, "reused");
        r.buildAndAssertSuccess(p);
        p.getBuildByNumber(1).delete();
        p.renameTo("moved"); // takes its next build number along
        FreeStyleProject again = project(r, "reused");
        FreeStyleBuild b = r.buildAndAssertSuccess(again);
        assertEquals(1, b.getNumber(), "the deleted build's number again");
        assertNotNull(record("jobs/reused/builds/1/build.xml"), "published: not the build deleted");
        assertThat(BLOBS.keys(), hasItem("jobs/reused/builds/1/files.tgz"));
    }

    @Test
    void fileAddedToACompletedBuildIsRepublished() throws Exception {
        FreeStyleProject p = project(r, "late");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        java.nio.file.Files.writeString(new java.io.File(b.getRootDir(), "late.txt").toPath(), "added later");
        hudson.model.AsyncPeriodicWork.all().get(BuildRecords.LateFiles.class).execute(hudson.model.TaskListener.NULL);
        wipeAndReload(r);
        b = r.jenkins.getItemByFullName("late", FreeStyleProject.class).getBuildByNumber(1);
        assertEquals("added later", java.nio.file.Files.readString(new java.io.File(b.getRootDir(), "late.txt").toPath()));
    }

    @Test
    void lateFilesKeepAnEditMadeElsewhere() throws Exception {
        FreeStyleProject p = project(r, "edited-late");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = "jobs/edited-late/builds/1/build.xml";
        // another controller edits the completed build (its description), and this one has not applied that yet
        // (written as this controller, so that it is never applied here)
        STORE.write(key, record(key).replace("<keepLog>", "<description>set elsewhere</description><keepLog>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        Files.writeString(new File(b.getRootDir(), "late.txt").toPath(), "added later");
        hudson.model.AsyncPeriodicWork.all().get(BuildRecords.LateFiles.class).execute(hudson.model.TaskListener.NULL);
        assertThat(record(key), containsString("set elsewhere"));
        assertThat(record(key), containsString("files updated"));
    }

    @Test
    void lateFilesAddedOnTwoControllersAreBothKept() throws Exception {
        FreeStyleProject p = project(r, "both-late");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = "jobs/both-late/builds/1/build.xml";
        String snapshot = "jobs/both-late/builds/1/files.tgz";
        Files.writeString(new File(b.getRootDir(), "b.txt").toPath(), "added here");
        // meanwhile another controller added a.txt to its copy, and republished the build with it
        Path elsewhere = Files.createTempDirectory("elsewhere");
        try {
            try (InputStream in = BLOBS.get(snapshot)) {
                new hudson.FilePath(elsewhere.toFile()).untarFrom(in, hudson.FilePath.TarCompression.GZIP);
            }
            Files.writeString(elsewhere.resolve("a.txt"), "added elsewhere");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (java.io.OutputStream out = new GZIPOutputStream(bytes)) {
                new hudson.FilePath(elsewhere.toFile()).tar(out, "**");
            }
            BLOBS.put(snapshot, new ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        } finally {
            Util.deleteRecursive(elsewhere.toFile());
        }
        STORE.as("other-controller").write(key, (record(key) + "\n<!-- files updated 1 -->").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        for (int i = 0; i < 300 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(100);
        }
        assertTrue(ItemChangeApplier.isIdle());
        List<String> names = new ArrayList<>();
        try (TarInputStream tar = new TarInputStream(new GZIPInputStream(BLOBS.get(snapshot)))) {
            for (TarEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names, hasItem("b.txt"));
        assertThat("the file added elsewhere is not dropped by publishing this copy", names, hasItem("a.txt"));
    }

    @Test
    void fileAddedLongAfterIsPublishedWhenTheBuildIsSaved() throws Exception {
        FreeStyleProject p = project(r, "later");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        BuildRecords.recentlyPublished.clear(); // an hour later, or after a restart
        java.nio.file.Files.writeString(new java.io.File(b.getRootDir(), "late.txt").toPath(), "added much later");
        b.setDescription("kept");
        wipeAndReload(r);
        b = r.jenkins.getItemByFullName("later", FreeStyleProject.class).getBuildByNumber(1);
        assertEquals("kept", b.getDescription());
        assertEquals("added much later", java.nio.file.Files.readString(new java.io.File(b.getRootDir(), "late.txt").toPath()));
    }

    @Test
    void fileAddedLongAfterIsFoundByTheScan() throws Exception {
        FreeStyleProject p = project(r, "scanned");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        BuildRecords.recentlyPublished.clear();
        java.nio.file.Files.writeString(new java.io.File(b.getRootDir(), "late.txt").toPath(), "found by the scan");
        hudson.model.AsyncPeriodicWork.all().get(BuildRecords.LateFiles.class).execute(hudson.model.TaskListener.NULL);
        wipeAndReload(r);
        b = r.jenkins.getItemByFullName("scanned", FreeStyleProject.class).getBuildByNumber(1);
        assertEquals("found by the scan", java.nio.file.Files.readString(new java.io.File(b.getRootDir(), "late.txt").toPath()));
    }

    @Test
    void fileAddedToAFetchedCopyIsPublished() throws Exception {
        FreeStyleProject p = project(r, "fetched");
        r.buildAndAssertSuccess(p);
        wipeAndReload(r); // as on a controller which did not run the build
        FreeStyleBuild b = r.jenkins.getItemByFullName("fetched", FreeStyleProject.class).getBuildByNumber(1);
        java.nio.file.Files.writeString(new java.io.File(b.getRootDir(), "late.txt").toPath(), "added to a fetched copy");
        b.setDescription("described here");
        wipeAndReload(r);
        b = r.jenkins.getItemByFullName("fetched", FreeStyleProject.class).getBuildByNumber(1);
        assertEquals("added to a fetched copy", java.nio.file.Files.readString(new java.io.File(b.getRootDir(), "late.txt").toPath()));
        assertThat("the manifest is local, not part of the snapshot",
                BuildRecords.Manifest.filesAdded(b.getRootDir()), org.hamcrest.Matchers.is(false));
    }

    @Test
    void deletedJobIsDroppedFromTheLateFilesSweep() throws Exception {
        FreeStyleProject p = project(r, "gone");
        r.buildAndAssertSuccess(p);
        assertTrue(BuildRecords.recentlyPublished.keySet().stream().anyMatch(k -> k.startsWith("jobs/gone/")));
        p.delete();
        hudson.model.AsyncPeriodicWork.all().get(BuildRecords.LateFiles.class).execute(hudson.model.TaskListener.NULL);
        assertTrue(BuildRecords.recentlyPublished.keySet().stream().noneMatch(k -> k.startsWith("jobs/gone/")), "not republished every minute");
    }

    @Test
    void buildNothingCouldResumeIsClosedAsAborted() throws Exception {
        FreeStyleProject p = project(r, "lost");
        r.buildAndAssertSuccess(p);
        BuildRecords.publishLost(p, 2, System.currentTimeMillis(), "It was running on a controller which is gone.");
        assertNotNull(record("jobs/lost/builds/2/build.xml"));
        assertNull(BLOBS.get("jobs/lost/builds/2/files.tgz"), "the stand-in has a key of its own");
        assertEquals(hudson.model.Result.ABORTED, p.getBuildByNumber(2).getResult(), "the publishing controller lists it too");
        wipeAndReload(r);
        FreeStyleBuild lost = r.jenkins.getItemByFullName("lost", FreeStyleProject.class).getBuildByNumber(2);
        assertNotNull(lost);
        assertEquals(hudson.model.Result.ABORTED, lost.getResult());
        assertThat(lost.getLog(), org.hamcrest.Matchers.containsString("a controller which is gone"));
    }

    @Test
    void lateFilesDoNotBringBackABuildDeletedElsewhere() throws Exception {
        FreeStyleProject p = project(r, "deleted-late");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = "jobs/deleted-late/builds/1/build.xml";
        // another controller deletes the build, and this one has not applied that yet (deleted as this controller, so
        // that it is never applied here)
        STORE.delete(key, PersistenceStore.ANY);
        Files.writeString(new File(b.getRootDir(), "late.txt").toPath(), "added later");
        hudson.model.AsyncPeriodicWork.all().get(BuildRecords.LateFiles.class).execute(hudson.model.TaskListener.NULL);
        assertNull(record(key), "not brought back");
        assertNull(BLOBS.get("jobs/deleted-late/builds/1/files.tgz"));
    }

    @Test
    void aBuildClosedByTwoControllersAtOnceKeepsItsConsole() throws Exception {
        FreeStyleProject p = project(r, "lost-twice");
        r.buildAndAssertSuccess(p);
        String key = "jobs/lost-twice/builds/2/build.xml";
        long start = System.currentTimeMillis();
        String reason = "It was running on a controller which is gone.";
        // another controller closes it too, its record written once this one found none
        afterRead = k -> {
            if (k.equals(key)) {
                afterRead = null;
                try {
                    BuildRecords.publishLost(p, 2, start, reason);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
        try {
            BuildRecords.publishLost(p, 2, start, reason);
        } finally {
            afterRead = null;
        }
        assertNotNull(BLOBS.get("jobs/lost-twice/builds/2/lost.tgz"), "the stand-in of the record that stands is kept");
        wipeAndReload(r);
        FreeStyleBuild lost = r.jenkins.getItemByFullName("lost-twice", FreeStyleProject.class).getBuildByNumber(2);
        assertEquals(hudson.model.Result.ABORTED, lost.getResult());
        assertThat(lost.getLog(), containsString("a controller which is gone"));
    }

    @Test
    void aClosedBuildKeepsWhatItPrinted() throws Exception {
        FreeStyleProject p = project(r, "shipped");
        r.buildAndAssertSuccess(p);
        ShippedLogs.LOGS.put("jobs/shipped/builds/2", "tick 1\ntick 2\n");
        BuildRecords.publishLost(p, 2, System.currentTimeMillis(), "It was running on a controller which is gone.");
        wipeAndReload(r);
        String log = r.jenkins.getItemByFullName("shipped", FreeStyleProject.class).getBuildByNumber(2).getLog();
        assertThat(log, org.hamcrest.Matchers.containsString("tick 1\ntick 2"));
        assertThat(log, org.hamcrest.Matchers.containsString("a controller which is gone"));
        assertTrue(log.indexOf("tick 2") < log.indexOf("a controller which is gone"), "what it printed, then why it ended");
    }

    @TestExtension("aClosedBuildKeepsWhatItPrinted")
    public static final class ShippedLogs implements BuildRecords.LostLog {
        static final java.util.Map<String, String> LOGS = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.io.InputStream shippedLog(String base) {
            String log = LOGS.get(base);
            return log == null ? null : new java.io.ByteArrayInputStream(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void renameMovesRecordsAndSnapshots() throws Exception {
        FreeStyleProject p = project(r, "before");
        r.buildAndAssertSuccess(p);
        p.renameTo("after");
        assertNull(record("jobs/before/builds/1/build.xml"));
        assertNotNull(record("jobs/after/builds/1/build.xml"));
        assertThat(BLOBS.keys(), contains("jobs/after/builds/1/files.tgz"));

        wipeAndReload(r);

        p = r.jenkins.getItemByFullName("after", FreeStyleProject.class);
        assertThat(p.getBuildByNumber(1).getLog(), containsString("hello from #1"));
    }

    @Test
    void aMoveClaimsItsDestinationFirst() throws Exception {
        FreeStyleProject p = project(r, "claimer");
        r.buildAndAssertSuccess(p);
        // another controller is moving something to "claimed" right now
        STORE.as("other").write("jobs/claimed.moving", (System.currentTimeMillis() + " other jobs/x").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> p.renameTo("claimed"));
        assertEquals("claimer", p.getName());
        assertNotNull(record("jobs/claimer/builds/1/build.xml"), "nothing moved");
        assertThat(BLOBS.keys(), hasItem("jobs/claimer/builds/1/files.tgz"));
        STORE.delete("jobs/claimed.moving", PersistenceStore.ANY);

        p.renameTo("claimed");
        assertNotNull(record("jobs/claimed/builds/1/build.xml"));
        assertNull(STORE.read("jobs/claimed.moving"), "the claim is released");
    }

    @Test
    void aMoveClaimsItsSourceToo() throws Exception {
        FreeStyleProject p = project(r, "contested");
        r.buildAndAssertSuccess(p);
        // another controller is moving "contested" somewhere else right now
        STORE.as("other").write("jobs/contested.moving", (System.currentTimeMillis() + " other jobs/contested").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> p.renameTo("mine"));
        assertEquals("contested", p.getName());
        assertNotNull(record("jobs/contested/builds/1/build.xml"), "nothing moved");
        assertThat(BLOBS.keys(), contains("jobs/contested/builds/1/files.tgz"));
        assertNull(STORE.read("jobs/mine.moving"), "the destination's claim is released");
        assertNotNull(STORE.read("jobs/contested.moving"), "the other claim is left alone");
    }

    @Test
    void movesOfAFolderAndOfAnItemInItExcludeEachOther() throws Exception {
        MockFolder f = r.createFolder("outer");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "inner");
        PersistenceStore other = STORE.as("other");
        // another controller is renaming the folder right now
        other.write("jobs/outer.moving", (System.currentTimeMillis() + " other jobs/outer").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> p.renameTo("inner2"));
        assertEquals("inner", p.getName());
        assertNull(STORE.read("jobs/outer/jobs/inner2.moving"), "the claims are released");
        assertNull(STORE.read("jobs/outer/jobs/inner.moving"));
        STORE.delete("jobs/outer.moving", PersistenceStore.ANY);
        // another controller is renaming the job in it right now
        other.write("jobs/outer/jobs/inner.moving", (System.currentTimeMillis() + " other jobs/outer/jobs/inner").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> f.renameTo("outer2"));
        assertEquals("outer", f.getName());
        assertNotNull(STORE.read("jobs/outer/jobs/inner/config.xml"), "nothing moved");
        STORE.delete("jobs/outer/jobs/inner.moving", PersistenceStore.ANY);

        f.renameTo("outer2");
        assertNotNull(STORE.read("jobs/outer2/jobs/inner/config.xml"));
    }

    @Test
    void aMoveOfAnItemMovedElsewhereLeavesItsBlobsAlone() throws Exception {
        FreeStyleProject p = project(r, "gone");
        r.buildAndAssertSuccess(p);
        // another controller renamed it (blobs, then entries); this one has not applied that yet
        BLOBS.move("jobs/gone", "jobs/gone-there");
        STORE.move("jobs/gone", "jobs/gone-there");
        assertThrows(ConflictException.class, () -> p.renameTo("gone-here"));
        assertThat(BLOBS.keys(), contains("jobs/gone-there/builds/1/files.tgz"));
        assertNull(STORE.read("jobs/gone-here/builds/1/build.xml"));
    }

    @Test
    void blobsMovedBeforeAMoveFailedAreMovedBack() throws Exception {
        FreeStyleProject p = project(r, "partly");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        failMove = true;
        try {
            assertThrows(IllegalStateException.class, () -> p.renameTo("partly-moved"));
        } finally {
            failMove = false;
        }
        assertEquals("partly", p.getName());
        assertThat(BLOBS.keys(), containsInAnyOrder("jobs/partly/builds/1/files.tgz", "jobs/partly/builds/2/files.tgz"));
        assertNotNull(record("jobs/partly/builds/1/build.xml"));
        assertNotNull(record("jobs/partly/builds/2/build.xml"));
    }

    @Test
    void aRecordWhoseSnapshotIsNotThereIsNotMaterialized() throws Exception {
        FreeStyleProject p = project(r, "moving");
        r.buildAndAssertSuccess(p);
        byte[] snapshot;
        try (InputStream in = BLOBS.get("jobs/moving/builds/1/files.tgz")) {
            snapshot = in.readAllBytes();
        }
        // as seen during a rename elsewhere: the blobs already moved, the record not yet
        BLOBS.delete("jobs/moving/builds/1");
        wipeAndReload(r);
        p = r.jenkins.getItemByFullName("moving", FreeStyleProject.class);
        assertNull(p.getBuildByNumber(1));
        assertFalse(new File(p.getBuildDir(), "1/build.xml").exists(), "not taken as complete");

        BLOBS.put("jobs/moving/builds/1/files.tgz", new ByteArrayInputStream(snapshot), snapshot.length);
        p._getRuns().purgeCache();
        assertThat(p.getBuildByNumber(1).getLog(), containsString("hello from #1"));
    }

    @Test
    void aClaimLeftByAMoveThatNeverFinishedIsTakenOver() throws Exception {
        FreeStyleProject p = project(r, "late-claimer");
        r.buildAndAssertSuccess(p);
        long old = System.currentTimeMillis() - PersistenceStores.CLAIM_LAPSE_MILLIS - 1000;
        STORE.as("dead").write("jobs/abandoned.moving", (old + " dead jobs/x").getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        p.renameTo("abandoned");
        assertNotNull(record("jobs/abandoned/builds/1/build.xml"));
        assertNull(STORE.read("jobs/abandoned.moving"));
    }

    @Test
    void aClaimLapsesWhenItsControllerIsGoneHoweverOld() throws Exception {
        FreeStyleProject p = project(r, "slow-claimer");
        r.buildAndAssertSuccess(p);
        long old = System.currentTimeMillis() - PersistenceStores.CLAIM_LAPSE_MILLIS - 1000;
        STORE.as("slow").write("jobs/slow-dest.moving", (old + " slow jobs/x").getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        live = Set.of(STORE.getOrigin(), "slow");
        try {
            // a move that has taken longer than the lapse is still going on
            assertThrows(ConflictException.class, () -> p.renameTo("slow-dest"));
            assertEquals("slow-claimer", p.getName());
            // a claim however recent, of a controller that is gone
            STORE.delete("jobs/slow-dest.moving", PersistenceStore.ANY);
            STORE.as("dead").write("jobs/slow-dest.moving", (System.currentTimeMillis() + " dead jobs/x").getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ABSENT);
            p.renameTo("slow-dest");
            assertNotNull(record("jobs/slow-dest/builds/1/build.xml"));
            assertNull(STORE.read("jobs/slow-dest.moving"));
        } finally {
            live = null;
        }
    }

    @Test
    void aClaimOfThisControllerThatNoMoveHoldsIsTakenOver() throws Exception {
        FreeStyleProject p = project(r, "own-claimer");
        r.buildAndAssertSuccess(p);
        // left by a move here whose release failed
        STORE.write("jobs/own-dest.moving", (System.currentTimeMillis() + " " + STORE.getOrigin() + " jobs/x").getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        p.renameTo("own-dest");
        assertNotNull(record("jobs/own-dest/builds/1/build.xml"));
    }

    @Test
    void renameBeforeBuildsAreMaterialized() throws Exception {
        FreeStyleProject p = project(r, "unopened");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        wipeAndReload(r); // a fresh controller: nothing materialized yet

        p = r.jenkins.getItemByFullName("unopened", FreeStyleProject.class);
        p.renameTo("renamed");

        assertEquals(List.of(2, 1), p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        assertThat(p.getBuildByNumber(1).getLog(), containsString("hello from #1"));
        assertFalse(new File(r.jenkins.getRootDir(), "jobs/unopened").exists(), "nothing materialized at the old location");
    }

    @Test
    void buildsMadeElsewhereInterleaveWithLocalOnes() throws Exception {
        FreeStyleProject p = project(r, "interleaved");
        r.buildAndAssertSuccess(p);
        // another controller completed #3 while the shared sequence handed #2 to this one
        PersistenceStore other = STORE.as("other-controller");
        other.write("jobs/interleaved/builds/3/build.xml", STORE.read("jobs/interleaved/builds/1/build.xml").content(), PersistenceStore.ABSENT);
        try (InputStream snapshot = BLOBS.get("jobs/interleaved/builds/1/files.tgz")) {
            byte[] bytes = snapshot.readAllBytes();
            BLOBS.put("jobs/interleaved/builds/3/files.tgz", new ByteArrayInputStream(bytes), bytes.length);
        }
        p._getRuns().purgeCache(); // as M2 will do on the change event
        assertThat(p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList(), hasItem(3));

        FreeStyleBuild b2 = r.buildAndAssertSuccess(p); // JENKINS-27530 would reject #2 below a known #3
        assertEquals(2, b2.getNumber());
        assertEquals(List.of(3, 2, 1), p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        assertThat(p.getBuildByNumber(3).getLog(), containsString("hello from #1")); // a copy of #1's snapshot
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
