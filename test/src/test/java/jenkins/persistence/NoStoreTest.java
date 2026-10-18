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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.XmlFile;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Without a store, files are where they always were.
 */
@WithJenkins
class NoStoreTest {

    @Test
    void filesStayOnDisk(JenkinsRule r) throws Exception {
        assertNull(PersistenceStores.get());
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("on disk");
        File config = new File(r.jenkins.getRootDir(), "jobs/p/config.xml");
        assertNull(StoreRouting.route(config));
        assertThat(Files.readString(config.toPath()), containsString("<description>on disk</description>"));
        assertThat(Files.readString(config.toPath()), not(containsString("itemUuid")));
        XmlFile xml = Items.getConfigFile(p);
        assertEquals(config, xml.getFile());
        assertTrue(xml.exists());
        r.jenkins.setSystemMessage("local");
        assertThat(Files.readString(new File(r.jenkins.getRootDir(), "config.xml").toPath()), containsString("local"));
        xml.delete();
        assertFalse(config.exists());
    }
}
