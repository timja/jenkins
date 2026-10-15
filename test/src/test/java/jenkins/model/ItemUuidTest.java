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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** An item's identity survives renames and configuration updates, and copies get their own (Target 15). */
@WithJenkins
class ItemUuidTest {

    @Test
    void survivesRenamesAndUpdates(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("before");
        String id = p.getItemUuid();
        assertNotNull(UUID.fromString(id));
        assertThat(p.getConfigFile().asString(), containsString("<itemUuid>" + id + "</itemUuid>"));
        p.renameTo("after");
        assertEquals(id, p.getItemUuid());
        assertSame(p, ItemUuids.get(id));
        // configuration posted without it, as through config.xml in the REST API
        p.updateByXml(new StreamSource(new ByteArrayInputStream("<project><description>posted</description></project>".getBytes(StandardCharsets.UTF_8))));
        assertEquals("posted", p.getDescription());
        assertEquals(id, p.getItemUuid());
        r.jenkins.reload();
        assertEquals(id, r.jenkins.getItemByFullName("after").getItemUuid(), "kept in its configuration");
    }

    @Test
    void copiesAndImportsGetTheirOwn(JenkinsRule r) throws Exception {
        FreeStyleProject src = r.createFreeStyleProject("src");
        FreeStyleProject copy = (FreeStyleProject) r.jenkins.copy((hudson.model.TopLevelItem) src, "copy");
        assertNotEquals(src.getItemUuid(), copy.getItemUuid());
        assertThat(copy.getConfigFile().asString(), not(containsString(src.getItemUuid())));
        // the source's configuration posted as a new job
        FreeStyleProject imported = (FreeStyleProject) r.jenkins.createProjectFromXML("imported",
                new ByteArrayInputStream(src.getConfigFile().asString().getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(src.getItemUuid(), imported.getItemUuid());
        assertSame(src, ItemUuids.get(src.getItemUuid()));
        assertSame(imported, ItemUuids.get(imported.getItemUuid()));
    }

    @Test
    void theIdentityOfAnItemItsCreatorCannotSeeIsNotTaken(JenkinsRule r) throws Exception {
        FreeStyleProject secret = r.createFreeStyleProject("secret");
        String id = secret.getItemUuid();
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.CREATE).everywhere().to("alice")
                .grant(Item.READ, Item.CONFIGURE).onPaths("mine").to("alice"));
        byte[] xml = ("<project><itemUuid>" + id + "</itemUuid></project>").getBytes(StandardCharsets.UTF_8);
        FreeStyleProject mine;
        try (ACLContext ctx = ACL.as2(User.getById("alice", true).impersonate2())) {
            mine = (FreeStyleProject) r.jenkins.createProjectFromXML("mine", new ByteArrayInputStream(xml));
        }
        assertNotEquals(id, mine.getItemUuid(), "one of its own");
        assertSame(secret, ItemUuids.get(id));
        // nor by posting it as the configuration of a job of theirs
        try (ACLContext ctx = ACL.as2(User.getById("alice", true).impersonate2())) {
            mine.updateByXml(new StreamSource(new ByteArrayInputStream(xml)));
        }
        assertNotEquals(id, mine.getItemUuid());
        assertSame(secret, ItemUuids.get(id));
        assertSame(mine, ItemUuids.get(mine.getItemUuid()));
    }

    @Test
    void itemsSavedBeforeGetOneFromTheirName(JenkinsRule r) throws Exception {
        FreeStyleProject p = (FreeStyleProject) r.jenkins.createProjectFromXML("legacy",
                new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8)));
        p.save();
        String xml = p.getConfigFile().asString().replaceAll("<itemUuid>[^<]*</itemUuid>", "");
        java.nio.file.Files.writeString(p.getConfigFile().getFile().toPath(), xml);
        r.jenkins.reload();
        String derived = r.jenkins.getItemByFullName("legacy").getItemUuid();
        assertEquals(UUID.nameUUIDFromBytes("jenkins-item:legacy".getBytes(StandardCharsets.UTF_8)).toString(), derived,
                "the same on every controller deriving it");
    }
}
