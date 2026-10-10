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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
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
    };
    static final InMemoryBlobStore BLOBS = new InMemoryBlobStore();

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
