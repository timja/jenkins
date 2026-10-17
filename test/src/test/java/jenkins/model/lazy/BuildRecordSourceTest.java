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

package jenkins.model.lazy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.util.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildRecordSourceTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void buildsStoredElsewhereAreIndexedAndCreatedOnLoad() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(p);
        Source source = ExtensionList.lookupSingleton(Source.class);
        source.job = "p";
        source.template = b1.getRootDir();
        source.stored = Set.of(3);
        p._getRuns().purgeCache();

        assertThat(p._getRuns().keySet(), contains(3, 1));
        FreeStyleBuild b3 = p.getBuildByNumber(3);
        assertThat(b3, notNullValue());
        assertEquals(3, b3.getNumber());
        assertEquals(new File(p.getBuildDir(), "3"), b3.getRootDir());
        assertThat(source.retrieved, hasItem(new File(p.getBuildDir(), "3")));
        assertThat(p.getLastBuild(), is(b3));
    }

    @Test
    void sharedNumbersNeedOnlyBeUnused() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(p);
        Source source = ExtensionList.lookupSingleton(Source.class);
        source.job = "p";
        source.template = b1.getRootDir();
        source.stored = Set.of(5);
        p._getRuns().purgeCache();
        assertThat(p._getRuns().keySet(), contains(5, 1));

        // build #2 is made here while #5, made elsewhere, is already known
        source.shares = true;
        FreeStyleBuild b2 = r.buildAndAssertSuccess(p);
        assertEquals(2, b2.getNumber());
        assertThat(p._getRuns().keySet(), contains(5, 2, 1));
    }

    @Test
    void numbersBelowKnownOnesAreRefusedUnlessShared() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(p);
        Source source = ExtensionList.lookupSingleton(Source.class);
        source.job = "p";
        source.template = b1.getRootDir();
        source.stored = Set.of(5);
        p._getRuns().purgeCache();

        // as upstream: JENKINS-27530
        IOException e = assertThrows(IOException.class, p::createExecutable);
        assertThat(e.getMessage(), containsString("JENKINS-27530"));
    }

    @Test
    void noSource() throws Exception {
        assertThat(BuildRecordSource.get(), is((BuildRecordSource) null));
        FreeStyleProject p = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(p);
        p._getRuns().purgeCache();
        assertThat(p._getRuns().keySet(), contains(1));
        assertThat(p.getBuildByNumber(1), notNullValue());
    }

    @TestExtension({
        "buildsStoredElsewhereAreIndexedAndCreatedOnLoad",
        "sharedNumbersNeedOnlyBeUnused",
        "numbersBelowKnownOnesAreRefusedUnlessShared"
    })
    public static final class Source implements BuildRecordSource {
        volatile String job;
        volatile File template;
        volatile Set<Integer> stored = Set.of();
        volatile boolean shares;
        final List<File> retrieved = new CopyOnWriteArrayList<>();

        private boolean mine(Job<?, ?> j) {
            return j.getFullName().equals(job);
        }

        @NonNull
        @Override
        public Set<Integer> numbers(@NonNull Job<?, ?> j) {
            return mine(j) ? stored : Set.of();
        }

        @Override
        public <R extends Run<?, ?>> R retrieve(@NonNull Job<?, ?> j, @NonNull File dir, @NonNull ThrowingCallable<R, IOException> load)
                throws IOException {
            if (mine(j)) {
                retrieved.add(dir);
                if (!dir.exists() && stored.contains(Integer.parseInt(dir.getName()))) {
                    // as made from the stored record
                    Files.createDirectories(dir.toPath());
                    String xml = Files.readString(new File(template, "build.xml").toPath(), StandardCharsets.UTF_8);
                    Files.writeString(new File(dir, "build.xml").toPath(), xml, StandardCharsets.UTF_8);
                }
            }
            return load.call();
        }

        @Override
        public boolean sharesNumbers(@NonNull Job<?, ?> j) {
            return mine(j) && shares;
        }
    }
}
