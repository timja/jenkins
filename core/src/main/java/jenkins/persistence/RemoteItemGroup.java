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
import hudson.model.Items;
import hudson.model.TopLevelItem;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * An item group (such as a folder) that can take an item another controller created in it, without reloading itself.
 * Otherwise such a creation reloads the whole group, and so every child already in it: its builds' caches,
 * triggers and, for computed folders, the computation (Target 15).
 *
 * @since TODO
 */
@Restricted(Beta.class)
public interface RemoteItemGroup {

    /**
     * Adds an item another controller created here, already loaded with {@link Items#load} (so named by the group and
     * with its {@code onLoad} done). Listeners are told by the caller.
     *
     * @throws IllegalArgumentException if the group has an item of that name already
     */
    void addRemotelyCreated(@NonNull TopLevelItem item) throws IOException;
}
