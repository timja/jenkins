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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.TokenBasedRememberMeServices2;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.util.Protector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import jenkins.security.CryptoConfidentialKey;
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

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> false) {
        @Override
        public boolean storesConfidentialKeys() {
            return true;
        }
    };

    @Test
    void loginAlwaysIssuesTheSignedCookie(JenkinsRule r) throws Exception {
        boolean old = TokenBasedRememberMeServices2.ALWAYS_REMEMBER;
        try {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            r.jenkins.setSecurityRealm(realm);
            TokenBasedRememberMeServices2.ALWAYS_REMEMBER = true; // set from the script console, after the realm
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
    void anApiTokenRequestGetsNoSignedCookie(JenkinsRule r) throws Exception {
        boolean old = TokenBasedRememberMeServices2.ALWAYS_REMEMBER;
        try {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            r.jenkins.setSecurityRealm(realm);
            TokenBasedRememberMeServices2.ALWAYS_REMEMBER = true;
            User alice = realm.createAccount("alice", "alice-password-1");
            JenkinsRule.WebClient wc = r.createWebClient().withBasicApiToken(alice);
            String who = wc.goTo("whoAmI/api/json", "application/json").getWebResponse().getContentAsString();
            assertTrue(who.contains("\"name\":\"alice\""), who);
            // it would outlive the token: signed with the user's seed, which revoking the token leaves alone
            assertNull(wc.getCookieManager().getCookie(AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY));
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

    @Test
    void aFormRenderedInOneSessionCanBeSubmittedInAnother(JenkinsRule r) throws Exception {
        boolean old = DefaultCrumbIssuer.EXCLUDE_SESSION_ID;
        try {
            // the default crumb is bound to the session too; the cluster's own crumb issuer is tested in cluster-plugin
            DefaultCrumbIssuer.EXCLUDE_SESSION_ID = true;
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            r.jenkins.setSecurityRealm(realm);
            User carol = realm.createAccount("carol", "carol-password-1");
            String hash = carol.getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword();
            JenkinsRule.WebClient wc = r.createWebClient().login("carol", "carol-password-1");
            HtmlForm rendered = wc.goTo("user/carol/security/").getFormByName("config");
            // the same page submitted to another controller: the browser has a session there, not this one
            Set<Cookie> other = r.createWebClient().login("carol", "carol-password-1").getCookieManager().getCookies();
            for (Cookie c : wc.getCookieManager().getCookies()) {
                if (c.getName().startsWith("JSESSIONID")) {
                    wc.getCookieManager().removeCookie(c);
                }
            }
            other.stream().filter(c -> c.getName().startsWith("JSESSIONID")).forEach(wc.getCookieManager()::addCookie);
            r.submit(rendered);
            assertEquals(hash, User.getById("carol", false).getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword(), "kept the existing password");
        } finally {
            DefaultCrumbIssuer.EXCLUDE_SESSION_ID = old;
        }
    }

    @Test
    void aFormRenderedBeforeThePasswordChangedDoesNotBringTheOldOneBack(JenkinsRule r) throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        r.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        realm.createAccount("admin", "admin-password-1");
        User dave = realm.createAccount("dave", "dave-password-1");
        JenkinsRule.WebClient admin = r.createWebClient().login("admin", "admin-password-1");
        HtmlForm rendered = admin.goTo("user/dave/security/").getFormByName("config");

        // dave changes it since
        HtmlForm own = r.createWebClient().login("dave", "dave-password-1").goTo("user/dave/security/").getFormByName("config");
        own.getInputByName("user.password").setValue("dave-password-2");
        own.getInputByName("user.password2").setValue("dave-password-2");
        r.submit(own);
        String hash = dave.getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword();

        admin.setThrowExceptionOnFailingStatusCode(false);
        assertEquals(400, r.submit(rendered).getWebResponse().getStatusCode());
        assertEquals(hash, User.getById("dave", false).getProperty(HudsonPrivateSecurityRealm.Details.class).getPassword(), "not the old one");
        r.createWebClient().login("dave", "dave-password-2"); // still works
    }

    @Test
    void protectedValuesUseAKeyEveryControllerHas(JenkinsRule r) throws Exception {
        String protectedValue = Protector.protect("a value");
        assertEquals("a value", Protector.unprotect(protectedValue));
        assertNotNull(STORE.read("secrets/hudson.util.Protector.shared"), "the key is shared with the other controllers");
        // as another controller, whose JVM has a key of its own, reads it: with the shared key
        byte[] value = Base64.getDecoder().decode(protectedValue);
        Cipher cipher = new CryptoConfidentialKey(Protector.class, "shared").decrypt(Arrays.copyOfRange(value, 0, 16));
        String plain = new String(cipher.doFinal(Arrays.copyOfRange(value, 16, value.length)), StandardCharsets.UTF_8);
        assertTrue(plain.startsWith("a value"), plain);
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
