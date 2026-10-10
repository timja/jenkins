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
import hudson.ExtensionPoint;
import hudson.model.Job;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Takes over a build which was running on a controller that is gone, so that it continues here.
 *
 * <p>{@link RunningBuilds} calls adopters with the build's lease already acquired by this controller.
 * Adoption happens in two steps, so that it can be abandoned if the previous owner turns out to have
 * moved on in the meantime: {@link #prepare} fetches what is needed without starting anything;
 * once this controller has been recorded as the owner, {@link Adoption#resume} continues the build.
 * If no adopter takes a build, its placeholder is removed.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface BuildAdopter extends ExtensionPoint {

    /**
     * Prepares to adopt a build.
     *
     * @param placeholder the build's placeholder as last written by its owner, including any
     *     {@linkplain RunningBuilds#update properties} the owner recorded
     * @return an adoption to complete, or null if this adopter cannot resume this build
     */
    @CheckForNull
    Adoption prepare(@NonNull Job<?, ?> job, @NonNull RunningBuilds.Placeholder placeholder) throws IOException;

    /**
     * A prepared adoption.
     */
    interface Adoption {
        /**
         * Continues the build on this controller, which is now its owner with the given epoch.
         */
        void resume(long epoch) throws IOException;

        /**
         * Discards what {@link BuildAdopter#prepare} fetched, as the adoption did not go ahead.
         */
        void abandon();
    }
}
