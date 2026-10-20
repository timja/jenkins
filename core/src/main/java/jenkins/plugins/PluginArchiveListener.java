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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.io.File;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Told about plugin archives an administrator installs from a file or URL rather than from an update site (whose
 * downloads go through {@code UpdateCenter.UpdateCenterConfiguration}): the CLI's {@code install-plugin} from a file,
 * URL or standard input, and an archive uploaded or deployed from a URL on the plugin manager's advanced page.
 * Lets e.g. several controllers sharing their configuration refuse such an install, or apply it on each of them.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface PluginArchiveListener extends ExtensionPoint {

    /**
     * Called before the archive replaces any existing one; for an upload, before it is deployed.
     *
     * @param shortName the plugin's {@code Short-Name}
     * @param archive the downloaded archive, not yet in place
     * @throws AbortException to refuse the install, with the reason as its message
     */
    default void beforeInstall(@NonNull String shortName, @NonNull File archive) throws AbortException {}

    /**
     * Called once the archive is in place as {@code plugins/<shortName>.jpi} (and before it is loaded, if at all).
     * A listener that fails is logged, and the install goes on.
     */
    default void installed(@NonNull String shortName, @NonNull File archive) {}

    static ExtensionList<PluginArchiveListener> all() {
        return ExtensionList.lookup(PluginArchiveListener.class);
    }
}
