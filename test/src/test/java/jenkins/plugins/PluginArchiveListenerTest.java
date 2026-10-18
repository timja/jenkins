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

package jenkins.plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.cli.CLICommandInvoker;
import hudson.model.UpdateCenter;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.io.FileUtils;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Plugins installed from a file or URL by an administrator are announced, and may be refused; plugin manager notices. */
@WithJenkins
class PluginArchiveListenerTest {

    private JenkinsRule r;

    @TempDir
    private Path tmp;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Archives.calls.clear();
    }

    @AfterEach
    void tearDown() {
        Archives.refuse = false;
        Notice.visible = true;
    }

    private File plugins(String name) {
        return new File(r.jenkins.getPluginManager().rootDir, name);
    }

    @Test
    void installFromTheCliIsAnnounced() {
        assertThat(new CLICommandInvoker(r, "install-plugin")
                        .withStdin(PluginArchiveListenerTest.class.getResourceAsStream("/plugins/token-macro.hpi"))
                        .invokeWithArgs("="),
                CLICommandInvoker.Matcher.succeeded());
        assertEquals(List.of("before token-macro", "installed token-macro " + plugins("token-macro.jpi")), Archives.calls);
        assertTrue(plugins("token-macro.jpi").isFile());
    }

    @Test
    void installFromTheCliMayBeRefused() {
        Archives.refuse = true;
        long before = plugins("token-macro.jpi").lastModified(); // the plugins directory is shared by tests in this JVM
        CLICommandInvoker.Result result = new CLICommandInvoker(r, "install-plugin")
                .withStdin(PluginArchiveListenerTest.class.getResourceAsStream("/plugins/token-macro.hpi"))
                .invokeWithArgs("=");
        assertThat(result, CLICommandInvoker.Matcher.failedWith(5));
        assertThat(result.stderr(), containsString("not on this instance"));
        assertEquals(List.of("before token-macro"), Archives.calls);
        assertEquals(before, plugins("token-macro.jpi").lastModified(), "not put in place");
    }

    private void upload() throws Exception {
        File plugin = tmp.resolve("htmlpublisher.jpi").toFile();
        FileUtils.copyURLToFile(PluginArchiveListenerTest.class.getResource("/plugins/htmlpublisher.jpi"), plugin);
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.goTo("pluginManager/advanced");
        HtmlForm f = page.getFormByName("uploadPlugin");
        f.getInputByName("name").setValue(plugin.getAbsolutePath());
        r.submit(f);
    }

    @Test
    void anUploadIsAnnouncedOnceInPlace() throws Exception {
        upload();
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (Archives.calls.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(List.of("before htmlpublisher", "installed htmlpublisher " + plugins("htmlpublisher.jpi")), Archives.calls);
        assertTrue(plugins("htmlpublisher.jpi").isFile());
    }

    @Test
    void anUploadMayBeRefused() throws Exception {
        Archives.refuse = true;
        long before = plugins("htmlpublisher.jpi").lastModified(); // the plugins directory is shared by tests in this JVM
        upload();
        assertEquals(List.of("before htmlpublisher"), Archives.calls);
        assertTrue(r.jenkins.getUpdateCenter().getJobs().stream().noneMatch(j -> j instanceof UpdateCenter.InstallationJob),
                "not deployed");
        assertEquals(before, plugins("htmlpublisher.jpi").lastModified());
    }

    @TestExtension({"installFromTheCliIsAnnounced", "installFromTheCliMayBeRefused", "anUploadIsAnnouncedOnceInPlace", "anUploadMayBeRefused"})
    public static class Archives implements PluginArchiveListener {
        static volatile boolean refuse;
        static final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void beforeInstall(String shortName, File archive) throws AbortException {
            calls.add("before " + shortName);
            assertTrue(archive.isFile(), "the archive to install");
            if (refuse) {
                throw new AbortException("Plugins are not on this instance to install");
            }
        }

        @Override
        public void installed(String shortName, File archive) {
            calls.add("installed " + shortName + " " + archive);
        }
    }

    @Test
    void noticesAreShownOnThePluginManagersPages() throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient();
        // (pluginManager/updates includes it too, but does not render in tests: its d:invokeBody has no body to invoke)
        for (String page : List.of("pluginManager/installed", "pluginManager/available", "pluginManager/advanced", "updateCenter/")) {
            assertNotNull(wc.goTo(page).getElementById("test-notice"), page);
        }
        Notice.visible = false;
        assertNull(wc.goTo("pluginManager/installed").getElementById("test-notice"));
    }

    @TestExtension("noticesAreShownOnThePluginManagersPages")
    public static class Notice extends PluginManagerNotice {
        static volatile boolean visible = true;

        @Override
        public boolean isVisible() {
            return visible;
        }
    }
}
