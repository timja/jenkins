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

package jenkins.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ComputersElsewhereTest {

    /** An agent that never connects here: inbound, and nothing launches it. */
    private static Computer agent(JenkinsRule r, String name) throws Exception {
        DumbSlave a = new DumbSlave(name, r.createTmpDir().getPath(), new JNLPLauncher());
        a.setNumExecutors(2);
        a.setLabelString("remote");
        a.setRetentionStrategy(RetentionStrategy.NOOP);
        r.jenkins.addNode(a);
        Computer c = a.toComputer();
        assertNotNull(c);
        return c;
    }

    /** Says that "remote" is connected to "controller-b", one of its two executors running build #3 of "p". */
    public static class Fake implements ComputersElsewhere {
        @Override
        public Connection connectedElsewhere(Computer computer) {
            if (!computer.getName().equals("remote")) {
                return null;
            }
            long now = System.currentTimeMillis();
            return new Connection("controller-b", 2, List.of(new Busy("p", 3, now - 5_000, 10_000)));
        }
    }

    @TestExtension("anAgentConnectedElsewhereIsShownOnlineWithItsExecutorsThere")
    public static class Shown extends Fake {}

    @Test
    void anAgentConnectedElsewhereIsShownOnlineWithItsExecutorsThere(JenkinsRule r) throws Exception {
        r.jenkins.setNumExecutors(0);
        FreeStyleProject p = r.createFreeStyleProject("p");
        Computer c = agent(r, "remote");
        ComputersElsewhere.Connection e = c.getConnectedElsewhere();
        assertNotNull(e);
        assertEquals("controller-b", e.getWhere());
        assertEquals(1, e.countBusy());
        assertEquals(p, e.getBusy().get(0).getJob());
        assertEquals(50, e.getBusy().get(0).getProgress(), 5);

        assertEquals("symbol-computer", c.getIcon());
        assertEquals("symbol-computer", c.getIconClassName());
        assertEquals("[online]", c.getIconAltText());
        assertEquals("Online, connected to controller-b", c.getTooltip());

        JenkinsRule.WebClient wc = r.createWebClient();
        HtmlPage page = wc.goTo("");
        DomElement widget = page.getElementById("executors");
        String text = widget.asNormalizedText();
        assertThat(text, containsString("remote"));
        assertThat(text, containsString("1/2"));
        assertThat(text, not(containsString("offline")));
        assertNotNull(page.getFirstByXPath("//div[@id='executors']//a[@href='" + r.contextPath + "/job/p/3/']"),
                "the build running there is linked");

        String list = wc.goTo("computer/").getElementById("node_remote").asXml();
        assertThat(list, containsString("Online, connected to controller-b"));
        assertThat(list, not(containsString("offline")));
    }

    @TestExtension("aBuildTheUserMayNotSeeIsNotNamed")
    public static class Hidden extends Fake {}

    @Test
    void aBuildTheUserMayNotSeeIsNotNamed(JenkinsRule r) throws Exception {
        r.createFreeStyleProject("p");
        agent(r, "remote");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("alice"));
        HtmlPage page = r.createWebClient().login("alice").goTo("");
        String widget = page.getElementById("executors").asXml();
        assertThat(widget, containsString("1/2"));
        assertThat(widget, containsString("Unknown Task"));
        assertThat(widget, not(containsString("job/p")));
    }

    @TestExtension("nothingIsScheduledOnAnAgentConnectedElsewhere")
    public static class NotScheduled extends Fake {}

    @Test
    void nothingIsScheduledOnAnAgentConnectedElsewhere(JenkinsRule r) throws Exception {
        r.jenkins.setNumExecutors(0);
        Computer c = agent(r, "remote");
        assertNotNull(c.getConnectedElsewhere());
        assertTrue(c.isOffline());
        assertFalse(c.isOnline());
        FreeStyleProject p = r.createFreeStyleProject("q");
        p.setAssignedLabel(r.jenkins.getLabel("remote"));
        p.scheduleBuild2(0);
        Queue queue = r.jenkins.getQueue();
        for (int i = 0; i < 3; i++) {
            queue.maintain();
        }
        Queue.Item item = queue.getItem(p);
        assertNotNull(item, "still waiting");
        assertNull(p.getLastBuild());
        assertTrue(c.isIdle());
        assertThat(item.getWhy(), containsString("offline"));
    }

    @TestExtension("anAgentMarkedOfflineIsShownOffline")
    public static class MarkedOffline extends Fake {}

    @Test
    void anAgentMarkedOfflineIsShownOffline(JenkinsRule r) throws Exception {
        Computer c = agent(r, "remote");
        c.setTemporaryOfflineCause(new OfflineCause.UserCause(null, "maintenance"));
        assertNull(c.getConnectedElsewhere());
        assertEquals("symbol-computer-disconnected", c.getIcon());
    }

    @TestExtension("aFailingImplementationKnowsNothing")
    public static class Failing implements ComputersElsewhere {
        @Override
        public Connection connectedElsewhere(Computer computer) {
            throw new IllegalStateException("broken");
        }
    }

    @Test
    void aFailingImplementationKnowsNothing(JenkinsRule r) throws Exception {
        Computer c = agent(r, "remote");
        assertNull(c.getConnectedElsewhere());
        assertEquals("symbol-computer-offline", c.getIcon());
    }

    @Test
    void withoutAnImplementationAnAgentNotConnectedIsShownOffline(JenkinsRule r) throws Exception {
        assertTrue(r.jenkins.getExtensionList(ComputersElsewhere.class).isEmpty());
        r.jenkins.setNumExecutors(0);
        Computer c = agent(r, "remote");
        assertNull(c.getConnectedElsewhere());
        OfflineCause cause = c.getOfflineCause();
        assertEquals(cause == null ? "symbol-computer-offline" : cause.getComputerIcon(), c.getIcon());
        assertEquals(cause == null ? "[offline]" : cause.getComputerIconAltText(), c.getIconAltText());
        assertEquals(cause == null ? "" : cause.toString(), c.getTooltip());
        String widget = r.createWebClient().goTo("").getElementById("executors").asNormalizedText();
        assertThat(widget, containsString("offline"));
        assertThat(widget, not(containsString("/2")));
    }
}
