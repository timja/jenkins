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
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Exclusive, expiring ownership of running builds, so that exactly one controller works on a build at a time.
 * Obtained from {@link PersistenceStore#leases}.
 *
 * <p>A lease belongs to the {@linkplain PersistenceStore#getOrigin origin} of the store instance which acquired it
 * and lapses when that origin is no longer {@linkplain PersistenceStore#liveOrigins alive}, as decided by the store
 * (for example by heartbeats), not by the clock of the holder.
 * Each acquisition returns a new, higher <em>epoch</em>, which holders use as a fencing token.
 *
 * @see RunningBuilds
 * @since TODO
 */
@Restricted(Beta.class)
public interface BuildLeases {

    /**
     * Acquires the lease on a key for this origin, if it is free, lapsed, or already held by this origin.
     *
     * @return the epoch now held (unchanged if this origin already held it), or 0 if another live origin holds it
     */
    long acquire(@NonNull String key) throws IOException;

    /**
     * Whether this origin still holds the lease with this epoch.
     * False only on evidence from the store that it does not (another epoch, another holder, no lease); a store which
     * cannot tell (being unreachable, say) throws: the build is then not taken for lost, which would stop it.
     */
    boolean holds(@NonNull String key, long epoch) throws IOException;

    /**
     * Gives up a lease, if held with this epoch.
     */
    void release(@NonNull String key, long epoch) throws IOException;
}
