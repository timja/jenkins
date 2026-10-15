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
import hudson.model.Job;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Assigns build numbers from the {@link PersistenceStore} when it serves the job's {@code nextBuildNumber},
 * so that every controller sharing the store draws from one sequence.
 *
 * <p>Increments with a strict compare-and-set and retries on {@link ConflictException}:
 * unlike configuration, a build number must never be overwritten.
 * If the store has no number yet, the sequence starts from this controller's current next build number
 * (a home that numbered builds before it used the store is seeded by importing it, not by this class).
 */
@Restricted(NoExternalUse.class)
@Extension(ordinal = 100)
public final class StoredBuildNumberAssigner implements Job.BuildNumberAssigner {

    private static final int MAX_ATTEMPTS = 1000;

    @Override
    public int assignBuildNumber(Job<?, ?> job, SaveNextBuildNumber saveNextBuildNumber) throws IOException {
        PersistenceStores.Route route = PersistenceStores.route(new File(job.getRootDir(), "nextBuildNumber"));
        if (route == null) {
            // not shared: whichever assigner would have been used without this one
            for (Job.BuildNumberAssigner other : ExtensionList.lookup(Job.BuildNumberAssigner.class)) {
                if (other != this) {
                    return other.assignBuildNumber(job, saveNextBuildNumber);
                }
            }
            // same as Job.DefaultBuildNumberAssigner
            synchronized (job) {
                int r = job.getNextBuildNumber();
                job.fastUpdateNextBuildNumber(r + 1);
                saveNextBuildNumber.call();
                return r;
            }
        }
        PersistenceStore store = route.store();
        String key = route.key();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            int number;
            long expected;
            if (entry == null) {
                number = Math.max(1, job.getNextBuildNumber());
                expected = PersistenceStore.ABSENT;
            } else {
                number = Integer.parseInt(new String(entry.content(), StandardCharsets.UTF_8).trim());
                expected = entry.version();
            }
            try {
                long version = store.write(key, ((number + 1) + "\n").getBytes(StandardCharsets.UTF_8), expected);
                if (entry == null) {
                    checkStillStored(store, key, version, job);
                }
                PersistenceStores.seen(key, version);
                job.fastUpdateNextBuildNumber(number + 1);
                return number;
            } catch (ConflictException e) {
                // another controller (or thread) took a number first; try the next one
            }
        }
        throw new IOException("Could not assign a build number for " + job.getFullName() + " after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Undoes a counter just created if the job is no longer stored there. The counter is absent too when another
     * controller renamed (or deleted) the job and this one has not applied that yet: its sequence goes on at the new
     * name, and numbers drawn here would be handed out there as well. The move takes the job's configuration and
     * counter together, atomically: if it came first, the configuration is gone now; if it came after, it took this
     * counter along and the sequence stays one.
     */
    private static void checkStillStored(PersistenceStore store, String key, long version, Job<?, ?> job) throws IOException {
        String config = key.substring(0, key.length() - "nextBuildNumber".length()) + "config.xml";
        if (!store.owns(config) || store.read(config) != null) {
            return;
        }
        try {
            store.delete(key, version);
        } catch (ConflictException e) {
            // changed since: no longer the counter this created
        }
        throw new IOException(job.getFullName() + " was renamed or deleted on another controller");
    }

    /**
     * Writes a job's next build number to the store, but never lowers it: a controller whose own view lags (or a
     * request to lower the number) would otherwise hand out again numbers other controllers used. The generic conflict
     * policy does not apply to this key: it is a compare-and-set of the higher number.
     *
     * @return false if the route is not a {@code nextBuildNumber} key or the text is not a number (write it as usual)
     */
    @Restricted(NoExternalUse.class)
    public static boolean writeNoLower(@NonNull PersistenceStores.Route route, @NonNull String text) throws IOException {
        if (!route.key().endsWith("/nextBuildNumber")) {
            return false;
        }
        int wanted;
        try {
            wanted = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        PersistenceStore store = route.store();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(route.key());
            Integer stored = entry == null ? null : parse(entry.content());
            if (stored != null && stored >= wanted) {
                PersistenceStores.seen(route.key(), entry.version());
                return true;
            }
            try {
                long version = store.write(route.key(), (wanted + "\n").getBytes(StandardCharsets.UTF_8),
                        entry == null ? PersistenceStore.ABSENT : entry.version());
                PersistenceStores.seen(route.key(), version);
                return true;
            } catch (ConflictException e) {
                // changed meanwhile: look again
            }
        }
        throw new IOException("Could not save the next build number of " + route.key() + " after " + MAX_ATTEMPTS + " attempts");
    }

    private static Integer parse(byte[] content) {
        try {
            return Integer.parseInt(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
