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
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import java.nio.charset.StandardCharsets;
import jenkins.model.PeepholePermalink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The permalinks cache of a job whose builds are shared is in the store: a controller which did not run the builds
 * resolves permalinks without going through every build.
 */
@WithJenkins
class StoredPermalinksTest {

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
        StoredPermalinks.forgetForTests();
    }

    private static void awaitApplied() throws InterruptedException {
        for (int i = 0; i < 500 && !ItemChangeApplier.isIdle(); i++) {
            Thread.sleep(10);
        }
        assertTrue(ItemChangeApplier.isIdle(), "changes applied");
    }

    private static String stored() throws Exception {
        PersistenceStore.Entry e = STORE.read("jobs/p/builds/permalinks");
        return e == null ? "" : new String(e.content(), StandardCharsets.UTF_8);
    }

    @Test
    void permalinksAreSharedAndResolvedWithoutGoingThroughEveryBuild() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        for (int i = 0; i < 5; i++) {
            r.buildAndAssertSuccess(p);
        }
        assertNull(p.getLastFailedBuild());
        assertEquals(5, p.getLastSuccessfulBuild().getNumber());
        assertThat(stored(), containsString("lastFailedBuild -1"));
        assertThat(stored(), containsString("lastSuccessfulBuild 5"));

        // as on a controller which did not run them: nothing loaded, nothing cached here
        p._getRuns().purgeCache();
        StoredPermalinks.forgetForTests();
        assertNull(p.getLastFailedBuild(), "known to be none, from the store");
        assertEquals(5, p.getLastSuccessfulBuild().getNumber());
        assertThat("builds loaded to resolve them", p._getRuns().getLoadedBuilds().size(), lessThanOrEqualTo(1));

        // a controller which has not seen #5 yet cannot move a target back
        PeepholePermalink.Cache cache = hudson.ExtensionList.lookupFirst(PeepholePermalink.Cache.class);
        cache.put(p, "lastSuccessfulBuild", new PeepholePermalink.Cache.Some(4));
        assertThat(stored(), containsString("lastSuccessfulBuild 5"));
        assertEquals(new PeepholePermalink.Cache.Some(5), cache.get(p, "lastSuccessfulBuild"));

        // deleting #5 does (its record is gone)
        p.getBuildByNumber(5).delete();
        assertEquals(4, p.getLastSuccessfulBuild().getNumber());
        assertThat(stored(), containsString("lastSuccessfulBuild 4"));

        // a change made elsewhere is read again
        STORE.as("other-controller").write("jobs/p/builds/permalinks", "lastSuccessfulBuild 3\n".getBytes(StandardCharsets.UTF_8), STORE.read("jobs/p/builds/permalinks").version());
        awaitApplied();
        assertEquals(new PeepholePermalink.Cache.Some(3), cache.get(p, "lastSuccessfulBuild"));
        assertEquals(PeepholePermalink.Cache.UNKNOWN, cache.get(p, "lastFailedBuild"), "the other controller's cache knew nothing else");
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
