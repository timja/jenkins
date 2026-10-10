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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.TokenBasedRememberMeServices2;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.http.Cookie;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;

/**
 * Browser sessions that do not depend on the controller's own HTTP session.
 */
@WithJenkins
class StatelessSessionsTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> false);

    @Test
    void loginAlwaysIssuesTheSignedCookie(JenkinsRule r) throws Exception {
        boolean old = TokenBasedRememberMeServices2.ALWAYS_REMEMBER;
        TokenBasedRememberMeServices2.ALWAYS_REMEMBER = true;
        try {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            r.jenkins.setSecurityRealm(realm); // components are created with the setting
            realm.createAccount("alice", "alice-password-1");
            JenkinsRule.WebClient wc = r.createWebClient().login("alice", "alice-password-1"); // without "Keep me signed in"
            Cookie remember = wc.getCookieManager().getCookie(AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY);
            assertNotNull(remember, "issued without ticking 'Keep me signed in'");

            // a browser that only has the cookie (no session here, as on another controller) is signed in
            JenkinsRule.WebClient elsewhere = r.createWebClient();
            elsewhere.getCookieManager().addCookie(remember);
            String who = elsewhere.goTo("whoAmI/api/json", "application/json").getWebResponse().getContentAsString();
            assertTrue(who.contains("\"name\":\"alice\""), who);
        } finally {
            TokenBasedRememberMeServices2.ALWAYS_REMEMBER = old;
        }
    }

    @Test
    void protectedPasswordSurvivesAnotherSession(JenkinsRule r) throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        User bob = realm.createAccount("bob", "bob-password-1");
        String hash = bob.getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword();

        // rendered in one session...
        JenkinsRule.WebClient first = r.createWebClient().login("bob", "bob-password-1");
        HtmlForm rendered = first.goTo("user/bob/security/").getFormByName("config");
        String protectedPassword = rendered.getInputByName("user.password").getValueAttribute();
        assertTrue(protectedPassword.length() > 20, "a protected value, not the password");
        // ...and submitted in another session (as to another controller), unchanged
        JenkinsRule.WebClient second = r.createWebClient().login("bob", "bob-password-1");
        HtmlForm form = second.goTo("user/bob/security/").getFormByName("config");
        form.getInputByName("user.password").setValue(protectedPassword);
        form.getInputByName("user.password2").setValue(protectedPassword);
        r.submit(form);
        assertEquals(hash, User.getById("bob", false).getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword(), "kept the existing password");
        r.createWebClient().login("bob", "bob-password-1"); // still works
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
