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
import hudson.XmlFile;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.User;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import jenkins.model.Loadable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The {@link Loadable} objects which hold the content of stored files, so that a change made to one of those files by
 * another controller can be applied to the object holding it here, through its own {@link Loadable#load}: a plugin's
 * singleton which reads its file once, say, with no {@link RemoteChangeHandler} of its own.
 *
 * <p>An object is recorded against a key when it is read from that key ({@link XmlFile#unmarshal}) or written to it
 * ({@link XmlFile#write}); the last one recorded wins, so the live object, which writes its file each time it is
 * saved, takes the key back from a copy read once. Objects are held weakly. Objects core applies changes to in their
 * own way (items, users, nodes, descriptors, the global configuration) are not recorded.
 */
@Restricted(NoExternalUse.class)
public final class LoadedObjects {

    /** An object holding a stored file's content, with the file it was read from or written to. */
    record Holder(@NonNull XmlFile file, @NonNull Saveable object) {}

    private record Ref(@NonNull XmlFile file, @NonNull WeakReference<Saveable> object) {}

    private static final Map<String, Ref> holders = new ConcurrentHashMap<>();

    private LoadedObjects() {}

    /** Records that an object holds the content of a key, read from or written to {@code file}, if it is one to record. */
    @Restricted(NoExternalUse.class)
    public static void loaded(@NonNull String key, @NonNull XmlFile file, @NonNull Object o) {
        if (!(o instanceof Loadable) || !(o instanceof Saveable s) || o instanceof Item || o instanceof User
                || o instanceof Node || o instanceof Descriptor || o instanceof Jenkins) {
            return;
        }
        Ref old = holders.get(key);
        if (old != null && old.object().get() == s) {
            return;
        }
        holders.put(key, new Ref(file, new WeakReference<>(s)));
        holders.values().removeIf(r -> r.object().get() == null);
    }

    /** The object holding the content of a key here, or null. */
    @CheckForNull
    static Holder of(@NonNull String key) {
        Ref r = holders.get(key);
        Saveable s = r == null ? null : r.object().get();
        return s == null ? null : new Holder(r.file(), s);
    }
}
