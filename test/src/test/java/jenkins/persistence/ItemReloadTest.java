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
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An item changed elsewhere is reloaded field by field (Target 15): a change made here meanwhile, by a setter which does
 * not hold the item's lock (as plugins' setters do not), is not lost.
 */
@WithJenkins
class ItemReloadTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(
            key -> key.startsWith("jobs/") && key.endsWith("/config.xml") && !key.contains("/builds/"));

    private JenkinsRule r;
    private PersistenceStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        other = STORE.as("other-controller");
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

    /** The stored config with a display name added, as another controller would write it. */
    private byte[] withDisplayName(String key, String displayName) throws IOException {
        String xml = new String(STORE.read(key).content(), StandardCharsets.UTF_8);
        return xml.replaceFirst("(<project[^>]*>)", "$1<displayName>" + displayName + "</displayName>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void onlyWhatChangedIsCopied() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.addProperty(new Prop("x"));
        Prop prop = p.getProperty(Prop.class);
        other.write("jobs/p/config.xml", withDisplayName("jobs/p/config.xml", "remote"), PersistenceStore.ANY);
        awaitApplied();
        assertEquals("remote", p.getDisplayName());
        assertThat("the properties did not change, so are the same objects", p.getProperty(Prop.class), sameInstance(prop));
    }

    @Test
    void aSetterRunningMeanwhileKeepsItsChange() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("q");
        p.addProperty(new Prop("x"));
        Prop prop = p.getProperty(Prop.class);
        Prop.reading = new CountDownLatch(1);
        Prop.release = new CountDownLatch(1);
        try {
            other.write("jobs/q/config.xml", withDisplayName("jobs/q/config.xml", "remote"), PersistenceStore.ANY);
            // the applier is reading the new version (blocked in the middle of it)...
            assertTrue(Prop.reading.await(30, TimeUnit.SECONDS));
            // ...when a plugin's setter changes a field here and saves, without the item's lock
            Thread setter = new Thread(() -> {
                try {
                    prop.setValue("local");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            setter.start();
            setter.join(TimeUnit.SECONDS.toMillis(10)); // before the fix it waited for the reload, then wrote the old value
            Prop.release.countDown();
            setter.join(TimeUnit.SECONDS.toMillis(30));
        } finally {
            Prop.reading = null;
            Prop.release.countDown();
        }
        awaitApplied();
        assertEquals("remote", p.getDisplayName());
        assertEquals("local", p.getProperty(Prop.class).value);
        String stored = new String(STORE.read("jobs/q/config.xml").content(), StandardCharsets.UTF_8);
        assertThat(stored, containsString("<displayName>remote</displayName>"));
        assertThat(stored, containsString("<value>local</value>"));
    }

    public static final class Prop extends JobProperty<Job<?, ?>> {
        /** While set, reading a Prop (unmarshalling it) waits for {@link #release}, having counted this down. */
        static volatile CountDownLatch reading;
        static volatile CountDownLatch release;

        String value;

        Prop(String value) {
            this.value = value;
        }

        /** As plugins' setters are: assign, then save the owner, without its lock. */
        void setValue(String value) throws IOException {
            this.value = value;
            owner.save();
        }

        private Object readResolve() throws InterruptedException {
            CountDownLatch r = reading;
            if (r != null) {
                r.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return this;
        }

        @TestExtension
        public static final class DescriptorImpl extends JobPropertyDescriptor {}
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
