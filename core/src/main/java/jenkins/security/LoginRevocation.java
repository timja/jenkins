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

package jenkins.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Makes signing out end that login everywhere it is in use, such as on every controller sharing users behind one
 * load balancer, where each controller creates its own HTTP session from the login's remember-me cookie.
 *
 * <p>A <em>login</em> is identified by its id: the Base64url SHA-256 of the remember-me cookie value issued at sign-in
 * ({@code TokenBasedRememberMeServices2}). Core records the id in each HTTP session it creates from that cookie, and
 * in the session the sign-in created.
 * <ul>
 * <li>On sign-out, core calls {@link #revoke} for the session's login id, if any, before invalidating the session.
 * <li>Core rejects a remember-me cookie whose login id {@linkplain #isRevoked is revoked}, and on each request
 *     drops the authentication of a session whose login id is revoked (the request is then anonymous, and the cookie
 *     is cleared).
 * </ul>
 * Only that login ends: the user's other browsers, with other cookies, stay signed in (unlike a change of the
 * user's seed, which ends all of them).
 *
 * <p>With no implementation sign-out is local, as upstream.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface LoginRevocation extends ExtensionPoint {

    /**
     * Revokes a login, on every controller, until it would have expired anyway.
     *
     * @param loginId the login's id
     * @param username who signed out
     * @param expiresAt when the login's remember-me cookie expires, in milliseconds since the epoch: the revocation
     *     need not be kept after that
     */
    void revoke(@NonNull String loginId, @NonNull String username, long expiresAt) throws IOException;

    /**
     * Whether a login was revoked, here or on another controller. Called on every authenticated request: must answer
     * from memory (no remote I/O), kept up to date asynchronously.
     */
    boolean isRevoked(@NonNull String loginId);

    /** Whether any implementation has revoked the login. */
    static boolean anyRevoked(@NonNull String loginId) {
        for (LoginRevocation r : ExtensionList.lookup(LoginRevocation.class)) {
            if (r.isRevoked(loginId)) {
                return true;
            }
        }
        return false;
    }
}
