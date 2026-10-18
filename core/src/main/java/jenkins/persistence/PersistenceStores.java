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
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.ThrowingCallable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Locates the active {@link PersistenceStore} and maps files under {@code JENKINS_HOME} onto its keys.
 * Files routed to it are read and written through a {@link Route}.
 *
 * <p>Internal to core except for the members not marked {@link NoExternalUse}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class PersistenceStores {

    private static final Logger LOGGER = Logger.getLogger(PersistenceStores.class.getName());

    private static volatile Jenkins resolvedFor;
    private static volatile PersistenceStore resolvedStore;

    private PersistenceStores() {}

    /** Set while this thread resolves the store, so that providers reading files do not recurse. */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    /**
     * The active store, or null if none is configured (or extensions are not yet available).
     * The first non-null store of the {@link PersistenceStoreProvider}s, resolved once.
     */
    @Restricted(Beta.class)
    @CheckForNull
    public static PersistenceStore get() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        if (resolvedFor == j) {
            return resolvedStore;
        }
        if (j.getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) < 0 || RESOLVING.get() != null) {
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            // looked up before taking our own lock: loading extensions takes a global lock, and threads holding it
            // (instantiating extensions) may themselves read files and get here
            ExtensionList<PersistenceStoreProvider> providers = ExtensionList.lookup(PersistenceStoreProvider.class);
            providers.size();
            synchronized (PersistenceStores.class) {
                if (resolvedFor != j) {
                    PersistenceStore store = null;
                    for (PersistenceStoreProvider provider : providers) {
                        store = provider.create();
                        if (store != null) {
                            LOGGER.info(() -> "Using persistence store " + provider.getClass().getName());
                            break;
                        }
                    }
                    resolved(store);
                    resolvedFor = j;
                }
                return resolvedStore;
            }
        } finally {
            RESOLVING.remove();
        }
    }

    private static void resolved(@CheckForNull PersistenceStore store) {
        MaterializedCopies.reset();
        StoredVersions.clear();
        resolvedStore = store;
        MaterializedCopies.subscribe(store);
    }

    /** Set while this thread applies a change made by another controller. */
    private static final ThreadLocal<Boolean> APPLYING_REMOTE = new ThreadLocal<>();

    /**
     * Runs code which brings this controller up to date with a change already made in the store by another controller.
     * Within it, modifications of stored keys are not written back to the store (they are already there);
     * only local state such as directories and in-memory objects is updated.
     */
    @Restricted(Beta.class)
    public static <V, T extends Throwable> V applyingRemoteChange(@NonNull ThrowingCallable<V, T> body) throws T {
        Boolean old = APPLYING_REMOTE.get();
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            return body.call();
        } finally {
            if (old == null) {
                APPLYING_REMOTE.remove();
            }
        }
    }

    /**
     * Whether this thread is {@linkplain #applyingRemoteChange applying a remote change}.
     */
    @Restricted(Beta.class)
    public static boolean isApplyingRemoteChange() {
        return APPLYING_REMOTE.get() != null;
    }

    /**
     * Computes the key for a file under {@link Jenkins#getRootDir}, whether or not it is routed to the store.
     *
     * @return the key, or null if the file is outside {@code JENKINS_HOME}
     */
    @Restricted(Beta.class)
    @CheckForNull
    public static String keyFor(@NonNull File file) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Path root = j.getRootDir().toPath().toAbsolutePath().normalize();
        Path path;
        try {
            path = file.toPath().toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!path.startsWith(root) || path.equals(root)) {
            return null;
        }
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /**
     * The local file corresponding to a key.
     *
     * @throws IllegalArgumentException if a segment of the key names no file in its parent directory (see
     *     {@link #isSafeKey}): keys come from other controllers, or from anything else writing to the store, and must
     *     never become a path outside {@code JENKINS_HOME}
     */
    @Restricted(NoExternalUse.class)
    @NonNull
    public static File fileFor(@NonNull String key) {
        if (!isSafeKey(key)) {
            throw new IllegalArgumentException("Not a key naming a file: " + key);
        }
        return new File(Jenkins.get().getRootDir(), key.replace('/', File.separatorChar));
    }

    /**
     * Whether one segment of a store key can name a file in the directory of the key's parent. Keys come from other
     * controllers, or from anything else writing to the store: a segment such as {@code ..} must never become a path.
     */
    @Restricted(NoExternalUse.class)
    public static boolean isSafeName(@CheckForNull String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0 && name.indexOf(':') < 0;
    }

    /** Whether every segment of a store key {@linkplain #isSafeName can name a file}. */
    @Restricted(NoExternalUse.class)
    public static boolean isSafeKey(@NonNull String key) {
        for (String segment : key.split("/", -1)) {
            if (!isSafeName(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A local copy of a stored file handed out by {@code XmlFile.getFile()} (read-only for core).
     * Code that writes such copies back (such as a plugin's write-back of files changed behind core's back) uses
     * {@code content} as the merge base and {@code modified} to tell a copy changed locally from one as made.
     *
     * @param key the stored key
     * @param file the local copy
     * @param content the content the copy was made from
     * @param version the version of that content
     * @param modified the modification time the copy was given when made
     */
    @Restricted(Beta.class)
    public record MaterializedCopy(@NonNull String key, @NonNull File file, @NonNull byte[] content, long version, long modified) {}

    /**
     * The local copies of stored files currently handed out, at most one per key; empty if no store is active.
     * A copy changed on disk since it was made is left as it is by {@code XmlFile.getFile()} (it is never written
     * back by core) until it holds the stored content again.
     */
    @Restricted(Beta.class)
    @NonNull
    public static Collection<MaterializedCopy> materializedCopies() {
        return MaterializedCopies.all();
    }
}
