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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.model.AbstractItem;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Saveable;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.HudsonPrivateSecurityRealm;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.security.ApiTokenProperty;
import org.htmlunit.FailingHttpStatusCodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;

/**
 * A save conflicting with a change made meanwhile by another controller never overwrites it: it is merged onto it by a
 * {@link StoredConfigReconciler} if one can, else refused, and the object reloaded from what is stored.
 */
@WithJenkins
class RefuseAndReloadTest {

    static volatile InMemoryPersistenceStore STORE;

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Reconciler.reloads.clear();
        ReasonReconciler.reasons.clear();
        FieldReconciler.window.set(null);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void changeElsewhere(String key, String from, String to) throws Exception {
        String stored = STORE.text(key);
        assertThat(stored, containsString(from));
        STORE.as("other").write(key, stored.replace(from, to).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
    }

    private static void reloadNow(FreeStyleProject p, PersistenceStore.Entry entry) throws IOException {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            PersistenceStores.applyingRemoteChange(() -> {
                StoredReloads.reload(p.getConfigFile(), p, entry);
                return null;
            });
        }
    }

    @Test
    void whatWasReadBeforeASaveHereIsNotReloadedOverIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("reload-stale");
        p.setDescription("before");
        // read to be reloaded (after a merged save, say), and saved here before that reload is done
        PersistenceStore.Entry read = STORE.read("jobs/reload-stale/config.xml");
        p.setDescription("saved here since");
        reloadNow(p, read);
        assertEquals("saved here since", p.getDescription(), "not undone by the older content");
    }

    @Test
    void aSaveWhileAnItemIsReloadedMergesAgainstWhatWasReloaded() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("reload-window");
        p.setDescription("original");
        String key = "jobs/reload-window/config.xml";
        // another controller allows concurrent builds and then disallows them again; the first is applied here (as
        // below, not by the events of those changes)
        PersistenceStore.Entry allowed;
        STORE.setLosingEvents(true);
        try {
            changeElsewhere(key, "<concurrentBuild>false</concurrentBuild>", "<concurrentBuild>true</concurrentBuild>");
            allowed = STORE.read(key);
            changeElsewhere(key, "<concurrentBuild>true</concurrentBuild>", "<concurrentBuild>false</concurrentBuild>");
        } finally {
            STORE.setLosingEvents(false);
        }
        // while the reconciler applies it, as it finishes (an item's onLoad, say), this controller disables the item
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread save = new Thread(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                p.disable();
            } catch (Exception e) {
                failure.set(e);
            }
        }, "save");
        FieldReconciler.window.set(() -> {
            save.start();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) { // until saved, or waiting for the reload to be done
                ThreadInfo info = threads.getThreadInfo(new long[] {save.getId()}, true, false)[0];
                if (info == null || info.getThreadState() == Thread.State.TERMINATED
                        || info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(p)) {
                    return;
                }
                assertTrue(System.nanoTime() < deadline, "the save neither happened nor waited: " + info);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        });
        reloadNow(p, allowed);
        save.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(save.isAlive());
        assertNull(failure.get());
        String stored = STORE.text(key);
        assertThat(stored, containsString("<disabled>true</disabled>"));
        assertThat("the other controller's later change stands: what was reloaded is not taken for this one's change",
                stored, containsString("<concurrentBuild>false</concurrentBuild>"));
        await(StoredReloads::isIdle);
        await(() -> !p.isConcurrentBuild());
        assertTrue(p.isDisabled());
    }

    @Test
    void aReconcilerIsToldWhetherAChangeMadeElsewhereOrTheVersionStoredAfterASaveHereIsReloaded() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("reason");
        p.setDescription("original");
        String key = "jobs/reason/config.xml";
        STORE.setLosingEvents(true); // the changes are applied here as below, not by their events
        try {
            changeElsewhere(key, "<description>original</description>", "<description>theirs</description>");
            reloadNow(p, STORE.read(key));
            assertEquals(List.of(StoredConfigReconciler.Reason.CHANGED_ELSEWHERE), ReasonReconciler.reasons);
            assertEquals("theirs", p.getDescription());
            changeElsewhere(key, "<description>theirs</description>", "<description>theirs again</description>");
            assertThrows(ConflictException.class, () -> p.setDescription("mine"));
        } finally {
            STORE.setLosingEvents(false);
        }
        await(() -> ReasonReconciler.reasons.size() == 2);
        assertEquals(List.of(StoredConfigReconciler.Reason.CHANGED_ELSEWHERE, StoredConfigReconciler.Reason.AFTER_SAVE_HERE),
                ReasonReconciler.reasons);
        await(StoredReloads::isIdle);
        assertEquals("theirs again", p.getDescription());
    }

    @Test
    void aSaveOverAChangeMadeElsewhereIsRefusedAndTheObjectReloaded() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("background");
        p.setDescription("original");
        String key = "jobs/background/config.xml";
        long refused = PersistenceStores.getRefusedCount();
        changeElsewhere(key, "<description>original</description>", "<description>theirs</description>");
        synchronized (p) { // the reload waits for the object's monitor
            assertThrows(ConflictException.class, () -> p.setDescription("mine"));
            assertEquals("mine", p.getDescription());
        }
        assertEquals(refused + 1, PersistenceStores.getRefusedCount());
        assertThat(STORE.text(key), containsString("<description>theirs</description>"));
        await(() -> "theirs".equals(p.getDescription()));
        await(StoredReloads::isIdle);
        // reloaded at that version: a later save is not refused, and does not store the refused value
        p.save();
        assertThat(STORE.text(key), containsString("<description>theirs</description>"));
        p.setDescription("later");
        assertThat(STORE.text(key), containsString("<description>later</description>"));
    }

    @Test
    void aFormSubmittedOverAChangeMadeElsewhereFails() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("form");
        p.setDescription("original");
        Race.job = p;
        FailingHttpStatusCodeException e = assertThrows(FailingHttpStatusCodeException.class,
                () -> r.createWebClient().goTo("refuse-race/", "text/plain"));
        assertTrue(e.getStatusCode() >= 400, "an error for the user: " + e.getStatusCode());
        assertThat(STORE.text("jobs/form/config.xml"), containsString("<description>theirs</description>"));
        await(() -> "theirs".equals(p.getDescription()));
    }

    @Test
    void theSameChangeMadeOnBothSidesIsNoConflict() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("same");
        p.setDescription("original");
        changeElsewhere("jobs/same/config.xml", "<description>original</description>", "<description>both</description>");
        long refused = PersistenceStores.getRefusedCount();
        p.setDescription("both");
        assertEquals(refused, PersistenceStores.getRefusedCount());
        p.setDescription("next");
        assertThat(STORE.text("jobs/same/config.xml"), containsString("<description>next</description>"));
    }

    @Test
    void aSaveOverADeletionElsewhereIsRefused() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleted");
        synchronized (p) { // before the deletion is applied here
            STORE.as("other").delete("jobs/deleted/config.xml", PersistenceStore.ANY);
            assertThrows(ConflictException.class, () -> p.setDescription("mine"));
        }
        assertEquals(null, STORE.read("jobs/deleted/config.xml"), "not created again");
    }

    @Test
    void aSaveRefusedWhileStartingDoesNotFailTheStartup() throws Exception {
        // two controllers starting at once apply the same configuration as code: both create the user admin
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        User admin = realm.createAccount("admin", "admin-password-1");
        String token = admin.getProperty(ApiTokenProperty.class).generateNewToken("t").plainValue;
        String key = "users/" + admin.getUserFolder().getName() + "/config.xml";
        String stored = STORE.text(key);
        assertThat(stored, containsString("</user>"));
        long refused = PersistenceStores.getRefusedCount();
        Field initLevel = Jenkins.class.getDeclaredField("initLevel");
        initLevel.setAccessible(true);
        synchronized (admin) { // before the change made elsewhere is applied here
            STORE.as("other").write(key, stored.replace("</user>", "<description>theirs</description></user>")
                    .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            initLevel.set(r.jenkins, InitMilestone.SYSTEM_CONFIG_LOADED); // as configuration as code runs
            try {
                realm.createAccount("admin", "admin-password-1"); // saves the user: refused, without failing
            } finally {
                initLevel.set(r.jenkins, InitMilestone.COMPLETED);
            }
        }
        assertEquals(refused + 1, PersistenceStores.getRefusedCount());
        assertThat("what was stored first stands", STORE.text(key), containsString("<description>theirs</description>"));
        // administrators are told which files were not saved
        RefusedAtStartupMonitor monitor = ExtensionList.lookupSingleton(RefusedAtStartupMonitor.class);
        assertTrue(monitor.isActivated());
        assertEquals(List.of(key), monitor.getKeys());
        assertThat(r.createWebClient().goTo("manage").getWebResponse().getContentAsString(), containsString(key));
        await(() -> "theirs".equals(User.getById("admin", false).getDescription()));
        assertTrue(User.getById("admin", false).getProperty(ApiTokenProperty.class).matchesPassword(token),
                "reloaded as at startup: its API token still works");

        // once started, a refused save fails as before
        await(StoredReloads::isIdle);
        synchronized (admin) {
            changeElsewhere(key, "<description>theirs</description>", "<description>again</description>");
            assertThrows(ConflictException.class, () -> realm.createAccount("admin", "admin-password-1"));
        }
        assertEquals(List.of(key), monitor.getKeys(), "only what was refused while starting");
        JenkinsRule.WebClient wc = r.createWebClient();
        org.htmlunit.WebRequest dismiss = new org.htmlunit.WebRequest(new java.net.URL(r.getURL(), monitor.getUrl() + "/dismiss"),
                org.htmlunit.HttpMethod.POST);
        wc.addCrumb(dismiss);
        wc.getPage(dismiss);
        assertFalse(monitor.isActivated(), "dismissed");
    }

    @Test
    void aUserChangedElsewhereKeepsWorkingApiTokens() throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        User alice = realm.createAccount("alice", "alice-password-1");
        String token = alice.getProperty(ApiTokenProperty.class).generateNewToken("t").plainValue;
        String key = "users/" + alice.getUserFolder().getName() + "/config.xml";
        STORE.as("other").write(key, STORE.text(key).replace("</user>", "<description>theirs</description></user>")
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        await(() -> "theirs".equals(User.getById("alice", false).getDescription()));
        assertTrue(User.getById("alice", false).getProperty(ApiTokenProperty.class).matchesPassword(token));
        String who = r.createWebClient().withBasicCredentials("alice", token)
                .goTo("whoAmI/api/json", "application/json").getWebResponse().getContentAsString();
        assertThat(who, containsString("\"name\":\"alice\""));
    }

    @Test
    void aReconcilerMergesDisjointChangesAndTheObjectGetsBoth() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("merge");
        p.setDescription("original");
        String key = "jobs/merge/config.xml";
        int merges = Reconciler.merges.get();
        changeElsewhere(key, "<concurrentBuild>false</concurrentBuild>", "<concurrentBuild>true</concurrentBuild>");
        p.setDescription("mine");
        assertEquals(merges + 1, Reconciler.merges.get());
        String stored = STORE.text(key);
        assertThat(stored, containsString("<description>mine</description>"));
        assertThat(stored, containsString("<concurrentBuild>true</concurrentBuild>"));
        // reloaded, by the reconciler, to have the other change too
        await(() -> Reconciler.reloads.contains(key));
        await(p::isConcurrentBuild);
        assertEquals("mine", p.getDescription());
        await(StoredReloads::isIdle);
        p.setDescription("after");
        assertThat(STORE.text(key), containsString("<concurrentBuild>true</concurrentBuild>"));
    }

    @Test
    void deletingWhatWasMovedElsewhereMeanwhileIsRefused() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("moving");
        STORE.setLosingEvents(true); // the move is not applied here (yet)
        try {
            STORE.as("other").move("jobs/moving", "jobs/moved", List.of());
            assertThrows(ConflictException.class, p::delete);
            assertTrue(p.getRootDir().isDirectory(), "nothing deleted here");
            assertSame(p, r.jenkins.getItem("moving"));
            assertThat(STORE.text("jobs/moved/config.xml"), containsString("<project>"));
        } finally {
            STORE.setLosingEvents(false);
        }
    }

    @Test
    void deletingWhatIsStoredStillWorks() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleting");
        p.delete();
        assertNull(STORE.read("jobs/deleting/config.xml"));
        assertFalse(p.getRootDir().exists());
        assertNull(r.jenkins.getItem("deleting"));
    }

    @Test
    void anItemIsReloadedWithoutHoldingItsLockWhileItsBuildsAreLoaded() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("locks");
        p.setDescription("before");
        String key = "jobs/locks/config.xml";
        changeElsewhere(key, "<description>before</description>", "<description>elsewhere</description>");
        PersistenceStore.Entry entry = STORE.read(key);
        // a build being created holds the job's builds lock (LazyBuildMixIn.newBuild, which a Pipeline's executable is
        // made with, unlike a freestyle project's, under the job's lock) and asks for the job's lock to be numbered (as
        // Job.DefaultBuildNumberAssigner does); meanwhile the change made elsewhere is reloaded, which must not hold the
        // job's lock while it waits for the builds lock (LazyBuildMixIn.onLoad)
        Assigner.reload = new Thread(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                PersistenceStores.applyingRemoteChange(() -> {
                    StoredReloads.reload(p.getConfigFile(), p, entry);
                    return null;
                });
            } catch (Exception e) {
                Assigner.failure.set(e.toString());
            }
        }, "reload");
        Assigner.job = p;
        AtomicReference<FreeStyleBuild> built = new AtomicReference<>();
        Thread build = new Thread(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                built.set(p.getLazyBuildMixIn().newBuild());
            } catch (Exception e) {
                Assigner.failure.set(e.toString());
            }
        }, "build");
        build.start();
        build.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(build.isAlive(), "the build is made");
        Assigner.reload.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(Assigner.reload.isAlive(), "the reload is done");
        assertNull(Assigner.failure.get());
        assertEquals(1, built.get().getNumber());
        assertEquals("elsewhere", p.getDescription());
    }

    /**
     * Numbers builds of {@link #job} as the default does (taking the job's lock), after starting {@link #reload} and
     * waiting until it waits for the builds lock this thread holds; unless that thread holds the job's lock: then this
     * would deadlock, which is recorded as the failure instead, and the lock is not taken.
     */
    @TestExtension("anItemIsReloadedWithoutHoldingItsLockWhileItsBuildsAreLoaded")
    public static final class Assigner implements Job.BuildNumberAssigner {
        static volatile Job<?, ?> job;
        static volatile Thread reload;
        static final AtomicReference<String> failure = new AtomicReference<>();

        @Override
        public int assignBuildNumber(Job<?, ?> j, SaveNextBuildNumber saveNextBuildNumber) throws IOException {
            if (j == job && reload != null && reload.getState() == Thread.State.NEW) {
                reload.start();
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (true) {
                    ThreadInfo info = threads.getThreadInfo(new long[] {reload.getId()}, true, false)[0];
                    if (info == null || info.getThreadState() == Thread.State.TERMINATED) {
                        break; // reloaded without waiting for the builds lock: fine too
                    }
                    if (info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                            && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(((FreeStyleProject) j).getLazyBuildMixIn())) {
                        for (MonitorInfo held : info.getLockedMonitors()) {
                            if (held.getIdentityHashCode() == System.identityHashCode(j)) {
                                failure.set("the reload holds the job's lock while it waits for the builds lock: a build starting deadlocks");
                                synchronized (Assigner.class) {
                                    return j.getNextBuildNumber(); // not taking the job's lock, which would deadlock
                                }
                            }
                        }
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        failure.set("the reload never waited for the builds lock: " + info);
                        break;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
            }
            synchronized (j) {
                int n = j.getNextBuildNumber();
                j.fastUpdateNextBuildNumber(n + 1);
                saveNextBuildNumber.call();
                return n;
            }
        }
    }

    /**
     * Merges changes to an item called merge, keeping the description saved here and the rest as stored; reloads it
     * itself, recording that.
     */
    @TestExtension
    public static final class Reconciler implements StoredConfigReconciler {
        static final List<String> reloads = new CopyOnWriteArrayList<>();
        static final AtomicInteger merges = new AtomicInteger();

        @Override
        public byte[] merge(@NonNull String key, @NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
            if (!key.equals("jobs/merge/config.xml")) {
                return null;
            }
            String description = new String(mine, StandardCharsets.UTF_8).replaceAll("(?s).*(<description>[^<]*</description>).*", "$1");
            merges.incrementAndGet();
            return new String(theirs, StandardCharsets.UTF_8).replaceAll("<description>[^<]*</description>", description)
                    .getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry) throws java.io.IOException {
            String key = PersistenceStores.keyFor(file.getPath());
            if (!"jobs/merge/config.xml".equals(key)) {
                return false;
            }
            assertTrue(PersistenceStores.isApplyingRemoteChange());
            ((FreeStyleProject) o).setConcurrentBuild(new String(entry.content(), StandardCharsets.UTF_8).contains("<concurrentBuild>true"));
            reloads.add(key);
            return true;
        }
    }

    /** Records why the item called reason is reloaded, and leaves it to the default. */
    @TestExtension("aReconcilerIsToldWhetherAChangeMadeElsewhereOrTheVersionStoredAfterASaveHereIsReloaded")
    public static final class ReasonReconciler implements StoredConfigReconciler {
        static final List<Reason> reasons = new CopyOnWriteArrayList<>();

        @Override
        public boolean reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry, @NonNull Reason reason) {
            if ("jobs/reason/config.xml".equals(PersistenceStores.keyFor(file.getPath()))) {
                reasons.add(reason);
            }
            return false;
        }
    }

    /**
     * As a form submission: another controller changes the description, and before that is applied here (the item's
     * lock is held, so the reload waits), this controller saves its own.
     */
    @TestExtension
    public static final class Race implements UnprotectedRootAction {
        static volatile FreeStyleProject job;

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
            return "refuse-race";
        }

        public HttpResponse doIndex() throws Exception {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                synchronized (job) {
                    changeElsewhere("jobs/" + job.getName() + "/config.xml", "<description>original</description>", "<description>theirs</description>");
                    job.setDescription("mine");
                }
            }
            return HttpResponses.text("saved");
        }
    }

    /**
     * For items called reload-*: merges three ways, element by element (description, concurrentBuild, disabled), and
     * reloads those fields from the stored content, as a field-by-field reconciler does; then runs {@link #window}, once,
     * as such a reconciler may run more (an item's onLoad, say) before it returns.
     */
    @TestExtension
    public static final class FieldReconciler implements StoredConfigReconciler {
        static final AtomicReference<Runnable> window = new AtomicReference<>();
        private static final List<String> ELEMENTS = List.of("description", "concurrentBuild", "disabled");

        private static boolean handles(String key) {
            return key != null && key.startsWith("jobs/reload-");
        }

        private static String value(String xml, String element) {
            Matcher m = Pattern.compile("<" + element + ">([^<]*)</" + element + ">").matcher(xml);
            return m.find() ? m.group(1) : null;
        }

        @Override
        public byte[] merge(@NonNull String key, @NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
            if (!handles(key)) {
                return null;
            }
            String b = new String(base, StandardCharsets.UTF_8);
            String m = new String(mine, StandardCharsets.UTF_8);
            String merged = new String(theirs, StandardCharsets.UTF_8);
            for (String e : ELEMENTS) {
                String v = value(m, e);
                if (v != null && !v.equals(value(b, e))) {
                    merged = merged.replaceFirst("<" + e + ">[^<]*</" + e + ">", Matcher.quoteReplacement("<" + e + ">" + v + "</" + e + ">"));
                }
            }
            return merged.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean reload(@NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry) throws IOException {
            if (!handles(PersistenceStores.keyFor(file.getPath()))) {
                return false;
            }
            String stored = new String(entry.content(), StandardCharsets.UTF_8);
            try {
                synchronized (o) {
                    set(AbstractItem.class, "description", o, value(stored, "description"));
                    set(AbstractProject.class, "concurrentBuild", o, Boolean.parseBoolean(value(stored, "concurrentBuild")));
                    set(AbstractProject.class, "disabled", o, Boolean.parseBoolean(value(stored, "disabled")));
                }
            } catch (ReflectiveOperationException e) {
                throw new IOException(e);
            }
            Runnable w = window.getAndSet(null);
            if (w != null) {
                w.run();
            }
            return true;
        }

        private static void set(Class<?> c, String name, Object o, Object value) throws ReflectiveOperationException {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        }
    }

    @TestExtension
    public static final class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE = new InMemoryPersistenceStore();
        }
    }
}
