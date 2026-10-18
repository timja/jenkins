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
import hudson.ExtensionPoint;
import java.nio.file.FileSystems;
import java.util.Collection;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Files under {@code JENKINS_HOME}, beyond core's default shared set, that are shared through the
 * {@link PersistenceStore} when one is active: for example extra per-item files a plugin keeps in item directories,
 * such as {@code jobs/**}{@code /scm-revision-hash.xml}. An input of core's routing; {@link LocalFiles} wins over it.
 *
 * <p>Core's default shared set is {@code jobs/**}{@code /config.xml} (excluding anything below a {@code builds}
 * directory), the root {@code config.xml} and root {@code *.xml}, {@code users/*}{@code /config.xml},
 * {@code nodes/*}{@code /config.xml} and {@code log/*.xml}. Confidential keys are shared as {@code secrets/<id>}
 * by the confidential store itself, when the store {@linkplain PersistenceStore#storesConfidentialKeys stores them};
 * nothing else in {@code secrets/}, nothing in a build directory ({@code builds/<number>/…}) and no
 * {@code nextBuildNumber} is ever routed, whatever the patterns say.
 *
 * <p>Only files first read after {@link hudson.init.InitMilestone#EXTENSIONS_AUGMENTED} can be shared.
 * Routing changes only when the {@link LocalFiles}/{@link SharedFiles} extensions change (a plugin is installed)
 * or a cloud agent is added or removed.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface SharedFiles extends ExtensionPoint {

    /**
     * Glob patterns over paths relative to {@code JENKINS_HOME}, with {@code /} as separator, as
     * {@link FileSystems#getDefault()}{@code .getPathMatcher("glob:…")} takes them. Must not change while the
     * extension is registered, and must not match keys reserved for direct use (first segment starting with {@code _}).
     */
    @NonNull
    Collection<String> patterns();
}
