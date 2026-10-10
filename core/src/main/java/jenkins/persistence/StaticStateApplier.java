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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.model.User;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Applies changes other controllers made to static state in the shared store: global configuration, descriptors,
 * users and nodes, and whatever {@link RemoteChangeHandler}s handle. Called by {@link ItemChangeApplier}.
 */
@Restricted(NoExternalUse.class)
final class StaticStateApplier {

    private static final Logger LOGGER = Logger.getLogger(StaticStateApplier.class.getName());

    private static final Pattern USER = Pattern.compile("users/([^/]+)/config\\.xml");
    private static final Pattern NODE = Pattern.compile("nodes/([^/]+)/config\\.xml");
    private static final Pattern ROOT_XML = Pattern.compile("([^/]+)\\.xml");

    private StaticStateApplier() {}

    /**
     * @return true if the key is static state (handled, or known and ignored)
     */
    static boolean apply(@NonNull String key, boolean deleted) throws Exception {
        for (RemoteChangeHandler h : ExtensionList.lookup(RemoteChangeHandler.class)) {
            if (h.handles(key)) {
                LOGGER.fine(() -> h + " applies " + key + " changed elsewhere");
                h.changed(key, deleted);
                return true;
            }
        }
        Jenkins j = Jenkins.get();
        if (key.equals("config.xml")) {
            if (!deleted) {
                LOGGER.fine("Reloading global configuration changed elsewhere");
                j.reloadGlobalConfig();
            }
            return true;
        }
        Matcher m = USER.matcher(key);
        if (m.matches()) {
            LOGGER.fine(() -> "Applying user " + m.group(1) + " changed elsewhere");
            User.changedElsewhere(m.group(1), deleted);
            return true;
        }
        Matcher n = NODE.matcher(key);
        if (n.matches()) {
            LOGGER.fine(() -> "Applying node " + n.group(1) + " changed elsewhere");
            j.getNodesObject().changedElsewhere(n.group(1), deleted);
            return true;
        }
        Matcher r = ROOT_XML.matcher(key);
        if (r.matches()) {
            Descriptor<?> d = j.getDescriptor(r.group(1));
            if (d != null && !deleted) {
                LOGGER.fine(() -> "Reloading " + r.group(1) + " changed elsewhere");
                d.load();
            }
            return true;
        }
        return false;
    }
}
