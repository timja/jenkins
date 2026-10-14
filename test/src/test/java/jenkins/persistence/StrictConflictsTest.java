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
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.htmlunit.FailingHttpStatusCodeException;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The conflict policy (Target 15, round 4): a save made after someone else changed the same setting is refused (a user
 * submitting a form reloads), rather than merged keeping this side; changes to other settings are still merged.
 */
@WithJenkins
class StrictConflictsTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private static void awaitApplied() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!ItemChangeApplier.isIdle() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    /**
     * In a web request, as a form submission is: another controller changes the description, and before that is applied
     * here (the item's lock is held, so the reload waits), this controller saves its own.
     */
    private static void raceOnDescription(JenkinsRule r, FreeStyleProject p, String theirs) throws Exception {
        Race.job = p;
        Race.theirs = theirs;
        r.createWebClient().goTo("strict-race/", "text/plain");
    }

    @TestExtension
    public static final class Race implements hudson.model.UnprotectedRootAction {
        static volatile FreeStyleProject job;
        static volatile String theirs;

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "strict-race";
        }

        public org.kohsuke.stapler.HttpResponse doIndex() throws Exception {
            try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                synchronized (job) {
                    String key = "jobs/" + job.getName() + "/config.xml";
                    String stored = new String(STORE.read(key).content(), StandardCharsets.UTF_8);
                    STORE.as("other-controller").write(key, stored.replaceAll("<description>[^<]*</description>",
                            "<description>" + theirs + "</description>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                    job.setDescription("mine");
                }
            }
            return org.kohsuke.stapler.HttpResponses.text("saved");
        }
    }

    @Test
    void sameSettingChangedMeanwhileIsRefused(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("strict");
        p.setDescription("original");
        long refused = PersistenceStores.getRefusedCount();
        FailingHttpStatusCodeException e = org.junit.jupiter.api.Assertions.assertThrows(FailingHttpStatusCodeException.class,
                () -> raceOnDescription(r, p, "theirs"));
        assertEquals(refused + 1, PersistenceStores.getRefusedCount());
        assertThat(new String(STORE.read("jobs/strict/config.xml").content(), StandardCharsets.UTF_8),
                containsString("<description>theirs</description>"));
        assertTrue(e.getStatusCode() >= 400, "an error for the user: " + e.getStatusCode());
        // the job in memory got the refused value: it is reloaded, so that a later save (not refused: no request)
        // does not store it after all
        awaitApplied();
        assertEquals("theirs", p.getDescription());
        p.save();
        assertThat(new String(STORE.read("jobs/strict/config.xml").content(), StandardCharsets.UTF_8),
                containsString("<description>theirs</description>"));
    }

    @Test
    void aSaveOutsideAWebRequestIsRefusedTooAndTheirsReloaded(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("background");
        p.setDescription("original");
        String key = "jobs/background/config.xml";
        String stored = new String(STORE.read(key).content(), StandardCharsets.UTF_8);
        STORE.as("other-controller").write(key, stored.replace("<description>original</description>", "<description>theirs</description>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        synchronized (p) { // before that is applied here
            org.junit.jupiter.api.Assertions.assertThrows(ConflictException.class, () -> p.setDescription("mine"));
        }
        awaitApplied();
        assertThat(new String(STORE.read(key).content(), StandardCharsets.UTF_8), containsString("<description>theirs</description>"));
        assertEquals("theirs", p.getDescription(), "reloaded from the store, not left with the refused value");
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
