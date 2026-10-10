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

package jenkins.triggers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleProject;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TriggerCoordinatorTest {

    @Test
    void coordinatorDecidesWhichTriggersRunHere(JenkinsRule r) throws Exception {
        FreeStyleProject mine = r.createFreeStyleProject("mine");
        mine.addTrigger(new TimerTrigger("* * * * *"));
        FreeStyleProject theirs = r.createFreeStyleProject("theirs");
        theirs.addTrigger(new TimerTrigger("* * * * *"));

        // addTrigger does not start triggers; a reload or restart would
        mine.getTrigger(TimerTrigger.class).start(mine, true);
        theirs.getTrigger(TimerTrigger.class).start(theirs, true);

        Trigger.checkTriggers(Calendar.getInstance());
        r.waitUntilNoActivity();

        assertNotNull(mine.getLastBuild(), "trigger allowed here ran");
        assertNull(theirs.getLastBuild(), "trigger left to another controller did not run");
        assertThat(Coordinator.asked, containsAll("mine", "theirs"));
    }

    private static org.hamcrest.Matcher<Iterable<? extends String>> containsAll(String... names) {
        return org.hamcrest.Matchers.containsInAnyOrder(Arrays.asList(names).toArray(new String[0]));
    }

    @TestExtension("coordinatorDecidesWhichTriggersRunHere")
    public static class Coordinator implements TriggerCoordinator {
        static final List<String> asked = new ArrayList<>();

        @Override
        public boolean shouldRun(TriggeredItem item, Trigger<?> trigger, Calendar tick) {
            asked.add(item.getName());
            return !item.getName().equals("theirs");
        }
    }
}
