/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

package hudson.security;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.ExtensionList;
import hudson.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.security.HMACConfidentialKey;
import jenkins.security.ImpersonatingUserDetailsService2;
import jenkins.security.LastGrantedAuthoritiesProperty;
import jenkins.security.LoginRevocation;
import jenkins.security.seed.UserSeedProperty;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * {@link TokenBasedRememberMeServices} with modification so as not to rely
 * on the user password being available.
 *
 * <p>
 * This allows remember-me to work with security realms where the password
 * is never available in clear text.
 *
 * @author Kohsuke Kawaguchi
 * @see TokenBasedRememberMeServices
 */
@Restricted(NoExternalUse.class)
public class TokenBasedRememberMeServices2 extends AbstractRememberMeServices {

    private static final Logger LOGGER = Logger.getLogger(TokenBasedRememberMeServices2.class.getName());

    /**
     * Escape hatch for the check on the maximum date for the expiration duration of the remember me cookie
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* Script Console modifiable */ boolean SKIP_TOO_FAR_EXPIRATION_DATE_CHECK =
            SystemProperties.getBoolean(TokenBasedRememberMeServices2.class.getName() + ".skipTooFarExpirationDateCheck");

    /**
     * Duration that a remember-me cookie stays valid, overriding Spring Security's 14-day default.
     * Configured via the system property
     * {@code hudson.security.TokenBasedRememberMeServices2.tokenValidity}; the value is parsed as a
     * duration (e.g. {@code 14d}, {@code 60m}, or a bare number interpreted as seconds).
     * When unset or not a positive duration, the Spring Security default is used.
     * Values larger than {@link #MAX_TOKEN_VALIDITY} are capped to that maximum of 1-year.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* Script Console modifiable */ Duration TOKEN_VALIDITY =
            SystemProperties.getDuration(TokenBasedRememberMeServices2.class.getName() + ".tokenValidity", ChronoUnit.SECONDS);

    /**
     * Upper bound (1 year) for {@link #TOKEN_VALIDITY}. A configured validity larger than this
     * is capped to this value to avoid effectively non-expiring remember-me cookies.
     */
    public static final Duration MAX_TOKEN_VALIDITY = Duration.ofDays(365);

    /**
     * Decorate {@link UserDetailsService} so that we can use information stored in
     * {@link LastGrantedAuthoritiesProperty}.
     * <p>
     * We wrap by {@link ImpersonatingUserDetailsService2} in other places too,
     * so this is possibly redundant, but there are many {@link AbstractPasswordBasedSecurityRealm#loadUserByUsername2(String)}
     * implementations that do not do it, so doing it helps retrofit old plugins to benefit from
     * the user impersonation improvements. Plus multiple {@link ImpersonatingUserDetailsService2}
     * do not incur any real performance penalty.
     * <p>
     * {@link TokenBasedRememberMeServices} needs to be used in conjunction with {@link RememberMeAuthenticationProvider}
     * (see {@link AbstractPasswordBasedSecurityRealm#createSecurityComponents})
     * and both need to use the same key and various security plugins need to do the same.
     */
    @SuppressWarnings("deprecation")
    public TokenBasedRememberMeServices2(UserDetailsService userDetailsService) {
        super(Jenkins.get().getSecretKey(), new ImpersonatingUserDetailsService2(userDetailsService));
    }

    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        // read on each login, not once; not for credentials sent with each request (an API token, say): the cookie,
        // signed with the user's seed, would outlive the token being revoked
        return (ALWAYS_REMEMBER && request.getHeader("Authorization") == null) || super.rememberMeRequested(request, parameter);
    }

    /**
     * Whether to issue the remember-me cookie on every login, as if "Keep me signed in" were always checked.
     * With several controllers behind one load balancer sharing users and keys, this signed cookie authenticates
     * a browser on whichever controller its next request reaches, without a shared session.
     * Requests authenticating with an {@code Authorization} header (an API token, a password) get no cookie this way.
     * A form rendered by one controller and submitted to another also needs a crumb issuer whose crumbs are not bound
     * to the HTTP session (one shared by the controllers, or {@code DefaultCrumbIssuer.EXCLUDE_SESSION_ID}).
     * Every browser sign-in is then remembered for {@link #TOKEN_VALIDITY}, also for users who did not ask for it; signing
     * out ends it everywhere only where a {@link LoginRevocation} is registered.
     */
    @Restricted(Beta.class)
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* Script Console modifiable */ boolean ALWAYS_REMEMBER =
            SystemProperties.getBoolean(TokenBasedRememberMeServices2.class.getName() + ".alwaysRemember");

    /** Session (and request) attribute: the id of the login a session belongs to, see {@link LoginRevocation}. */
    static final String LOGIN_ID = LoginRevocation.class.getName() + ".loginId";
    /** Session (and request) attribute: when that login's cookie expires, in milliseconds since the epoch. */
    static final String LOGIN_EXPIRES = LoginRevocation.class.getName() + ".expiresAt";

    /** The id of a login: the Base64url SHA-256 of its remember-me cookie's value. */
    private String loginId(String[] tokens) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(encodeCookie(tokens).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records in the session a sign-in made which login it is, where something can revoke logins. */
    private void recordLogin(HttpServletRequest request, String[] tokens, long expiryTime) {
        if (ExtensionList.lookup(LoginRevocation.class).isEmpty()) {
            return;
        }
        HttpSession session = request.getSession();
        session.setAttribute(LOGIN_ID, loginId(tokens));
        session.setAttribute(LOGIN_EXPIRES, expiryTime);
    }

    /** Removes from the session which login it belonged to: a sign-in made in it may be of no login, or another. */
    static void forgetLogin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(LOGIN_ID);
            session.removeAttribute(LOGIN_EXPIRES);
        }
    }

    /**
     * Ends the login a session belongs to everywhere, as when its user signs out: on every controller, through the
     * {@link LoginRevocation}s. Does nothing for a session of no login (none was remembered) or with none registered.
     */
    @Restricted(NoExternalUse.class)
    public static void revokeLogin(@CheckForNull HttpSession session, @NonNull String username) {
        Object id = session == null ? null : session.getAttribute(LOGIN_ID);
        if (!(id instanceof String loginId)) {
            return;
        }
        long expiresAt = session.getAttribute(LOGIN_EXPIRES) instanceof Long l ? l : System.currentTimeMillis() + MAX_TOKEN_VALIDITY.toMillis();
        for (LoginRevocation r : ExtensionList.lookup(LoginRevocation.class)) {
            try {
                r.revoke(loginId, username, expiresAt);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not end the login of " + username + " everywhere", e);
            }
        }
    }

    /** Whether the login a session belongs to was revoked, here or elsewhere. */
    static boolean isRevoked(@NonNull HttpSession session) {
        return session.getAttribute(LOGIN_ID) instanceof String loginId && LoginRevocation.anyRevoked(loginId);
    }

    protected String makeTokenSignature(long tokenExpiryTime, String username) {
        String userSeed;
        if (UserSeedProperty.DISABLE_USER_SEED) {
            userSeed = "no-seed";
        } else {
            User user = User.getById(username, true);
            UserSeedProperty userSeedProperty = user.getProperty(UserSeedProperty.class);
            if (userSeedProperty == null) {
                // if you want to filter out the user seed property, you should consider using the DISABLE_USER_SEED instead
                return "no-prop";
            }
            userSeed = userSeedProperty.getSeed();
        }
        String token = String.join(":", username, Long.toString(tokenExpiryTime), userSeed, getKey());
        return MAC.mac(token);
    }

    @Override
    public void onLoginSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication successfulAuthentication) {
        // Exit if the principal hasn't asked to be remembered
        if (!rememberMeRequested(request, getParameter())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Did not send remember-me cookie (principal did not set parameter '" +
                        getParameter() + "')");
            }

            return;
        }

        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null && j.isDisableRememberMe()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Did not send remember-me cookie because 'Remember Me' is disabled in " +
                        "security configuration (principal did set parameter '" + getParameter() + "')");
            }
            // TODO log warning when receiving remember-me request despite the feature being disabled?
            return;
        }

        // TODO is it really still necessary to reimplement all of the below, or could we simply override rememberMeRequested?

        long expiryTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(getTokenValiditySeconds());
        String username = successfulAuthentication.getName();

        String signatureValue = makeTokenSignature(expiryTime, username);
        int tokenLifetime = calculateLoginLifetime(request, successfulAuthentication);
        String[] tokens = { username, Long.toString(expiryTime), signatureValue };
        setCookie(tokens, tokenLifetime, request, response);
        recordLogin(request, tokens, expiryTime);

        if (logger.isDebugEnabled()) {
            logger.debug("Added remember-me cookie for user '" + username + "', expiry: '" + new Date(expiryTime)
                            + "'");
        }
    }

    /**
     * Calculates the validity period in seconds for a newly generated remember-me login.
     * After this period (from the current time) the remember-me login will be considered
     * expired. This method allows customization based on request parameters supplied with
     * the login or information in the {@code Authentication} object. The default value
     * is just the token validity period property, {@code tokenValiditySeconds}.
     * <p>
     * The returned value will be used to work out the expiry time of the token and will
     * also be used to set the {@code maxAge} property of the cookie.
     *
     * See SEC-485.
     * @param request the request passed to onLoginSuccess
     * @param authentication the successful authentication object.
     * @return the lifetime in seconds.
     */
    protected int calculateLoginLifetime(HttpServletRequest request, Authentication authentication) {
        return getTokenValiditySeconds();
    }

    @Override
    protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request, HttpServletResponse response) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            // as this filter could be called during restart, this corrects at least the symptoms
            throw new InvalidCookieException("Jenkins is not yet running");
        }
        if (j.isDisableRememberMe()) {
            cancelCookie(request, response);
            throw new InvalidCookieException("rememberMe is disabled");
        }
        if (cookieTokens.length != 3) {
            throw new InvalidCookieException(
                    "Cookie token did not contain 3" + " tokens, but contained '" + Arrays.asList(cookieTokens) + "'");
        }
        long tokenExpiryTime = getTokenExpiryTime(cookieTokens);
        if (isTokenExpired(tokenExpiryTime)) {
            throw new InvalidCookieException("Cookie token[1] has expired (expired on '" + new Date(tokenExpiryTime)
                    + "'; current time is '" + new Date() + "')");
        }
        // Check the user exists. Defer lookup until after expiry time checked, to
        // possibly avoid expensive database call.
        UserDetails userDetails = getUserDetailsService().loadUserByUsername(cookieTokens[0]);
        Objects.requireNonNull(userDetails, "UserDetailsService " + getUserDetailsService()
                + " returned null for username " + cookieTokens[0] + ". " + "This is an interface contract violation");
        // Check signature of token matches remaining details. Must do this after user
        // lookup, as we need the DAO-derived password. If efficiency was a major issue,
        // just add in a UserCache implementation, but recall that this method is usually
        // only called once per HttpSession - if the token is valid, it will cause
        // SecurityContextHolder population, whilst if invalid, will cause the cookie to
        // be cancelled.
        String expectedTokenSignature = makeTokenSignature(tokenExpiryTime, userDetails.getUsername());
        if (!equals(expectedTokenSignature, cookieTokens[2])) {
            throw new InvalidCookieException("Cookie token[2] contained signature '" + cookieTokens[2]
                    + "' but expected '" + expectedTokenSignature + "'");
        }
        if (!ExtensionList.lookup(LoginRevocation.class).isEmpty()) {
            String loginId = loginId(cookieTokens);
            if (LoginRevocation.anyRevoked(loginId)) {
                cancelCookie(request, response);
                throw new InvalidCookieException("This login was signed out of");
            }
            request.setAttribute(LOGIN_ID, loginId); // recorded in the session it makes
            request.setAttribute(LOGIN_EXPIRES, tokenExpiryTime);
        }
        return userDetails;
    }

    private long getTokenExpiryTime(String[] cookieTokens) {
        try {
            return Long.parseLong(cookieTokens[1]);
        } catch (NumberFormatException nfe) {
            throw new InvalidCookieException(
                    "Cookie token[1] did not contain a valid number (contained '" + cookieTokens[1] + "')");
        }
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "TODO needs triage")
    @Override
    protected Authentication createSuccessfulAuthentication(HttpServletRequest request, UserDetails userDetails) {
        Authentication auth = super.createSuccessfulAuthentication(request, userDetails);

        // Ensure this session is linked to the user's seed
        if (!UserSeedProperty.DISABLE_USER_SEED) {
            User user = User.get2(auth);
            UserSeedProperty userSeed = user.getProperty(UserSeedProperty.class);
            String sessionSeed = userSeed.getSeed();
            request.getSession().setAttribute(UserSeedProperty.USER_SESSION_SEED, sessionSeed);
        }
        if (request.getAttribute(LOGIN_ID) instanceof String loginId) {
            request.getSession().setAttribute(LOGIN_ID, loginId);
            request.getSession().setAttribute(LOGIN_EXPIRES, request.getAttribute(LOGIN_EXPIRES));
        }

        return auth;
    }

    /**
     * In addition to the expiration requested by {@link TokenBasedRememberMeServices#isTokenExpired}, we also check the expiration is not too far in the future.
     * Especially to detect maliciously crafted cookie.
     */
    protected boolean isTokenExpired(long tokenExpiryTimeMs) {
        long nowMs = System.currentTimeMillis();
        long maxExpirationMs = TimeUnit.SECONDS.toMillis(getTokenValiditySeconds()) + nowMs;
        if (!SKIP_TOO_FAR_EXPIRATION_DATE_CHECK && tokenExpiryTimeMs > maxExpirationMs) {
            // attempt to use a cookie that has more than the maximum allowed expiration duration
            // was either created before a change of configuration or maliciously crafted
            long diffMs = tokenExpiryTimeMs - maxExpirationMs;
            LOGGER.log(Level.WARNING, "Attempt to use a cookie with an expiration duration larger than the one configured (delta of: {0} ms)", diffMs);
            return true;
        }
        // Check it has not expired
        if (tokenExpiryTimeMs < nowMs) {
            return true;
        }
        return false;
    }

    @Override
    protected int getTokenValiditySeconds() {
        Duration configured = TOKEN_VALIDITY;
        if (configured != null) {
            long seconds = configured.toSeconds();
            if (seconds <= 0) {
                LOGGER.log(Level.WARNING, "Ignoring non-positive remember-me token validity ({0}); using the default", configured);
            } else if (seconds > MAX_TOKEN_VALIDITY.toSeconds()) {
                LOGGER.log(Level.WARNING, "Capping remember-me token validity ({0}) to the maximum of {1}", new Object[] {configured, MAX_TOKEN_VALIDITY});
                return (int) MAX_TOKEN_VALIDITY.toSeconds();
            } else {
                return (int) seconds;
            }
        }
        return super.getTokenValiditySeconds();
    }

    @VisibleForTesting
    @Override
    protected String getCookieName() {
        return super.getCookieName();
    }

    /**
     * Constant time comparison to prevent against timing attacks.
     */
    private static boolean equals(String expected, String actual) {
        byte[] expectedBytes = bytesUtf8(expected);
        byte[] actualBytes = bytesUtf8(actual);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private static byte[] bytesUtf8(String s) {
        return s != null ? Utf8.encode(s) : null;
    }

    /**
     * Used to compute the token signature securely.
     */
    private static final HMACConfidentialKey MAC = new HMACConfidentialKey(TokenBasedRememberMeServices.class, "mac");

}
