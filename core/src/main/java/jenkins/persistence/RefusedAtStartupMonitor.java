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

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Tells administrators which files were not saved while this controller started because another controller sharing
 * the persistence store had saved them first, so that its version stands (configuration as code applied by both, say,
 * from different versions of it). Shown until dismissed; never without a store.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class RefusedAtStartupMonitor extends AdministrativeMonitor {

    /** Keys whose save was refused while this controller started, another controller's version standing. */
    private static final Set<String> refusedWhileStarting = ConcurrentHashMap.newKeySet();

    /** Records that a save was refused while this controller started (see {@link Route#write}). */
    static void refused(String key) {
        refusedWhileStarting.add(key);
    }

    @Override
    public String getDisplayName() {
        return Messages.RefusedAtStartupMonitor_DisplayName();
    }

    @Override
    public boolean isSecurity() {
        return true; // the global configuration and users among them: what secures this controller may be another's
    }

    @Override
    public boolean isActivated() {
        return !refusedWhileStarting.isEmpty();
    }

    /** The keys of the files not saved, sorted; until dismissed. */
    public List<String> getKeys() {
        return List.copyOf(new TreeSet<>(refusedWhileStarting));
    }

    @RequirePOST
    public HttpResponse doDismiss() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        refusedWhileStarting.clear();
        return HttpResponses.redirectViaContextPath("/manage");
    }
}
