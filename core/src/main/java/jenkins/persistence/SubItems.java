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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Job;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Configuration files of a job's sub-items which plugins keep below the job's directory (Target 15): promoted-builds'
 * {@code promotions/<process>/config.xml} and matrix-project's {@code configurations/<axis>/<value>/…/config.xml}.
 * Their owners find them by listing those directories, so on a controller which did not create them (or a fresh
 * home) the directories are created when the job loads; and a change to one made elsewhere reloads its job.
 */
@Restricted(NoExternalUse.class)
public final class SubItems {

    private static final Logger LOGGER = Logger.getLogger(SubItems.class.getName());

    /** Directories below a job which hold sub-items. */
    static final List<String> CONTAINERS = List.of("promotions", "configurations");

    static final Pattern KEY = Pattern.compile(
            "((?:jobs/[^/]+)(?:/(?:jobs|branches)/[^/]+)*)/(?:promotions/[^/]+|configurations(?:/[^/]+/[^/]+)+)/config\\.xml");

    private static final int MAX_DEPTH = 12;

    private SubItems() {}

    /** The key of the {@code config.xml} of the job owning a sub-item's configuration, or null if the key is no such thing. */
    @CheckForNull
    static String ownerKey(@NonNull String key) {
        Matcher m = KEY.matcher(key);
        return m.matches() ? m.group(1) + "/config.xml" : null;
    }

    /** Creates the local directories of a job's sub-items found in the store, so that its plugins list them. */
    public static void materializeDirs(@NonNull Job<?, ?> job) {
        PersistenceStore store = PersistenceStores.get();
        String jobKey = store == null ? null : PersistenceStores.keyFor(job.getRootDir());
        if (jobKey == null) {
            return;
        }
        for (String container : CONTAINERS) {
            if (!store.owns(jobKey + "/" + container + "/x/config.xml") && !store.owns(jobKey + "/" + container + "/x/y/config.xml")) {
                continue;
            }
            try {
                walk(store, jobKey + "/" + container, 0);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not list the stored " + container + " of " + job.getFullName(), e);
            }
        }
    }

    private static void walk(PersistenceStore store, String prefix, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (String name : store.children(prefix)) {
            if (name.equals("config.xml")) {
                Util.createDirectories(PersistenceStores.fileFor(prefix + "/config.xml").getParentFile().toPath());
            } else {
                walk(store, prefix + "/" + name, depth + 1);
            }
        }
    }

    /** The local directory of a sub-item's configuration key. */
    static File dirOf(@NonNull String key) {
        return PersistenceStores.fileFor(key).getParentFile();
    }
}
