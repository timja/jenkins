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
 * If the store has no number yet, the sequence starts from this controller's current next build number.
 */
@Restricted(NoExternalUse.class)
@Extension(ordinal = 100)
public final class StoredBuildNumberAssigner implements Job.BuildNumberAssigner {

    private static final int MAX_ATTEMPTS = 1000;

    @Override
    public int assignBuildNumber(Job<?, ?> job, SaveNextBuildNumber saveNextBuildNumber) throws IOException {
        PersistenceStores.Route route = PersistenceStores.route(new File(job.getRootDir(), "nextBuildNumber"));
        if (route == null) {
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
                PersistenceStores.seen(key, version);
                job.fastUpdateNextBuildNumber(number + 1);
                return number;
            } catch (ConflictException e) {
                // another controller (or thread) took a number first; try the next one
            }
        }
        throw new IOException("Could not assign a build number for " + job.getFullName() + " after " + MAX_ATTEMPTS + " attempts");
    }
}
