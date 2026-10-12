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
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Files under {@code JENKINS_HOME} which each controller of a cluster keeps to itself, never shared through a
 * {@link PersistenceStore} (Target 15): declared by the code that writes them, instead of a list kept by the store.
 * See also {@link PerController} for classes.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface LocalFiles extends ExtensionPoint {

    /**
     * Glob patterns over paths relative to {@code JENKINS_HOME}, with {@code /} as separator, as
     * {@link FileSystems#getDefault()}{@code .getPathMatcher("glob:…")} takes them: e.g. {@code queue.xml},
     * {@code jobs/**}{@code /my-plugin-state.xml}.
     */
    @NonNull
    Collection<String> patterns();

    /** Whether any {@link LocalFiles} declares the key. */
    @Restricted(NoExternalUse.class)
    static boolean declares(@NonNull String key) {
        Path path = Path.of(key);
        for (PathMatcher m : matchers()) {
            if (m.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> matchers() {
        List<PathMatcher> result = new ArrayList<>();
        for (LocalFiles l : ExtensionList.lookup(LocalFiles.class)) {
            for (String p : l.patterns()) {
                result.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            }
        }
        return result;
    }

    /** Core's own: the queue as saved at shutdown, node monitors' data, update-center settings, install state. */
    @Restricted(NoExternalUse.class)
    @Extension
    final class Core implements LocalFiles {
        @NonNull
        @Override
        public Collection<String> patterns() {
            return List.of("queue.xml", "nodeMonitors.xml", "hudson.model.UpdateCenter.xml", "jenkins.install.*",
                    "users/users.xml", "users/*/apiTokenStats.xml"); // the user index and each controller's own token usage
        }
    }
}
