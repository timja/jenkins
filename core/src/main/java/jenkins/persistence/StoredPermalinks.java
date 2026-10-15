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
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.PeepholePermalink;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The permalinks cache of jobs whose builds are shared ({@code lastFailedBuild 123}, as in the {@code permalinks} file of
 * a build directory), kept in the store at {@code <job>/builds/permalinks} (Target 15).
 *
 * <p>With a cache per controller, a controller with a fresh home knew no permalink, and resolving one (a job page shows
 * several) went back through every build until it found a match, loading each: with 20,000 builds and none failed,
 * over a minute for the job page. Now the controller completing a build updates the shared cache (as
 * {@link PeepholePermalink}'s listener does on one controller), and the others read it.
 *
 * <p>A target is only moved back (to an older build, or to none) if the build it named is gone from the store: a
 * controller which has not seen a newer build yet would otherwise replace the right target with an older one.
 */
@Restricted(NoExternalUse.class)
@Extension(ordinal = 100)
public final class StoredPermalinks implements PeepholePermalink.Cache {

    private static final Logger LOGGER = Logger.getLogger(StoredPermalinks.class.getName());

    static final String NAME = "permalinks";

    /** Builds key ({@code jobs/x/builds}) → the stored cache, as last read or written here. */
    private static final Map<String, Map<String, Known>> caches = new ConcurrentHashMap<>();

    @Override
    public boolean isShared(@NonNull Job<?, ?> job) {
        return BuildRecords.buildsKey(job) != null;
    }

    @NonNull
    @Override
    public PermalinkTarget get(@NonNull Job<?, ?> job, @NonNull String id) {
        String builds = BuildRecords.buildsKey(job);
        if (builds == null) {
            return next().get(job, id);
        }
        Known known = cacheOf(builds).get(id);
        return known != null ? known : UNKNOWN;
    }

    @Override
    public void put(@NonNull Job<?, ?> job, @NonNull String id, @NonNull Known target) {
        put(job, id, target, 0);
    }

    private void put(@NonNull Job<?, ?> job, @NonNull String id, @NonNull Known target, int retry) {
        String builds = BuildRecords.buildsKey(job);
        if (builds == null) {
            next().put(job, id, target);
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        String key = builds + "/" + NAME;
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                PersistenceStore.Entry e = store.read(key);
                Map<String, Known> stored = parse(e);
                Known current = stored.get(id);
                if (target.equals(current)) {
                    caches.put(builds, stored);
                    return;
                }
                if (current instanceof Some(int n) && (target instanceof None || target instanceof Some(int m) && m < n)
                        && exists(store, builds, n)) {
                    // this controller has not seen #n yet (or a deletion moved the target back without us): keep it
                    LOGGER.fine(() -> "Keeping " + id + " of " + job + " at #" + n + " (not " + target + ")");
                    caches.put(builds, stored);
                    return;
                }
                stored.put(id, target);
                if (gone(job, builds, store)) {
                    // deleted (here or elsewhere) since it was resolved: writing would bring its builds' entry back
                    caches.remove(builds);
                    return;
                }
                try {
                    store.write(key, format(stored), e == null ? PersistenceStore.ABSENT : e.version());
                    caches.put(builds, stored);
                    return;
                } catch (ConflictException x) {
                    // another controller updated it meanwhile: merge again
                }
            }
        } catch (IOException | RuntimeException x) {
            LOGGER.log(Level.FINE, "Could not store " + id + " of " + job + "; kept here meanwhile", x);
        }
        try {
            if (gone(job, builds, store)) {
                caches.remove(builds);
                return;
            }
        } catch (IOException | RuntimeException x) {
            // kept here, and tried again below
        }
        caches.computeIfAbsent(builds, k -> new ConcurrentHashMap<>()).put(id, target);
        // other controllers take the shared value for granted (they ignore the record's event for permalinks): try
        // again, or they would keep the old target until the next build; a newer target stored since is kept (above)
        if (retry < MAX_RETRIES) {
            Timer.get().schedule(() -> put(job, id, target, retry + 1), 1L << Math.min(retry + 1, 6), TimeUnit.SECONDS);
        } else {
            LOGGER.warning(() -> "Could not store " + id + " of " + job + "; other controllers may show an older build until the next");
        }
    }

    private static final int MAX_RETRIES = 10;

    /**
     * Whether a build is stored: its record, or until that is published its running placeholder. The controller
     * completing a build sets permalinks to it before it publishes the record (which takes a while: the log is closed,
     * the snapshot uploaded), and the placeholder goes only once the record is there.
     */
    private static boolean exists(@NonNull PersistenceStore store, @NonNull String builds, int n) throws IOException {
        if (store.read(builds + "/" + n + "/" + BuildRecords.RECORD) != null) {
            return true;
        }
        String job = builds.substring(0, builds.length() - "builds".length());
        return store.read(job + RunningBuilds.PREFIX + "/" + n + ".xml") != null;
    }

    /**
     * Whether the job was deleted: it is no longer in its parent here, or its configuration is no longer stored. A
     * permalink resolved while a job is deleted (resolving one can take a while for a job with many builds), or a
     * retry, would otherwise store the cache again after the job's builds were removed from the store.
     */
    private static boolean gone(@NonNull Job<?, ?> job, @NonNull String builds, @NonNull PersistenceStore store) throws IOException {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // whether it exists, whoever asks
            if (job.getParent().getItem(job.getName()) != job) {
                return true;
            }
        }
        String config = builds.substring(0, builds.length() - "builds".length()) + "config.xml";
        return store.owns(config) && store.read(config) == null;
    }

    /** The cache of a job changed (or went) elsewhere: read it again when next needed. */
    static void changedElsewhere(@NonNull String builds) {
        caches.remove(builds);
    }

    /** {@code jobs/f/jobs/x/builds/permalinks} → {@code jobs/f/jobs/x/builds}; null for other keys. */
    @CheckForNull
    static String buildsKeyOf(@NonNull String key) {
        String suffix = "/builds/" + NAME;
        return key.startsWith("jobs/") && key.endsWith(suffix) ? key.substring(0, key.length() - NAME.length() - 1) : null;
    }

    private static Map<String, Known> cacheOf(String builds) {
        return caches.computeIfAbsent(builds, k -> {
            try {
                return parse(PersistenceStores.get().read(k + "/" + NAME));
            } catch (IOException | RuntimeException x) {
                LOGGER.log(Level.FINE, "Could not read the permalinks of " + k, x);
                return new ConcurrentHashMap<>();
            }
        });
    }

    static Map<String, Known> parse(@CheckForNull PersistenceStore.Entry e) {
        Map<String, Known> cache = new ConcurrentHashMap<>();
        if (e == null) {
            return cache;
        }
        for (String line : new String(e.content(), StandardCharsets.UTF_8).split("\n")) {
            int idx = line.indexOf(' ');
            if (idx <= 0) {
                continue;
            }
            try {
                int number = Integer.parseInt(line.substring(idx + 1).trim());
                cache.put(line.substring(0, idx), number == -1 ? NONE : new Some(number));
            } catch (NumberFormatException x) {
                LOGGER.fine(() -> "Ignoring " + line);
            }
        }
        return cache;
    }

    static byte[] format(Map<String, Known> cache) {
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(cache).forEach((id, k) -> sb.append(id).append(' ').append(k instanceof Some(int n) ? n : -1).append('\n'));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private PeepholePermalink.Cache next() {
        for (PeepholePermalink.Cache c : ExtensionList.lookup(PeepholePermalink.Cache.class)) {
            if (c != this) {
                return c;
            }
        }
        throw new IllegalStateException("no other permalinks cache");
    }

    static void forgetForTests() {
        caches.clear();
    }
}
