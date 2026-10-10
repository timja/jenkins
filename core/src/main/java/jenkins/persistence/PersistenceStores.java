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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Locates the active {@link PersistenceStore} and maps files under {@code JENKINS_HOME} onto its keys.
 *
 * <p>Also remembers, per key, the version this JVM last read or wrote, so that writes can detect
 * modifications made by other controllers in the meantime.
 * What happens on such a conflict depends on {@code -Djenkins.persistence.PersistenceStores.conflicts}:
 * {@code warn} (the default) logs and overwrites, {@code fail} throws {@link ConflictException}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class PersistenceStores {

    private static final Logger LOGGER = Logger.getLogger(PersistenceStores.class.getName());

    /**
     * Conflict policy: {@code warn} or {@code fail}.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ String CONFLICTS = SystemProperties.getString(PersistenceStores.class.getName() + ".conflicts", "warn");

    private static volatile PersistenceStore testingStore;
    private static volatile Jenkins resolvedFor;
    private static volatile PersistenceStore resolvedStore;

    private static final Map<String, Long> versions = new ConcurrentHashMap<>();
    private static final AtomicLong conflicts = new AtomicLong();

    private PersistenceStores() {}

    /**
     * The active store, or null if none is configured (or extensions are not yet available).
     */
    @CheckForNull
    public static PersistenceStore get() {
        PersistenceStore testing = testingStore;
        if (testing != null) {
            return testing;
        }
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
            // Load the extension list before taking our own lock: loading extensions takes a global lock,
            // and threads holding it (instantiating extensions) may themselves read XmlFiles and get here.
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
                    resolvedStore = store;
                    versions.clear();
                    resolvedFor = j;
                }
                return resolvedStore;
            }
        } finally {
            RESOLVING.remove();
        }
    }

    /** Set while this thread resolves the store, so that providers reading files do not recurse. */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    /**
     * Finds out whether a file is served by the active store.
     *
     * @return a route to the key in the store, or null if the file lives on the local filesystem
     */
    @CheckForNull
    public static Route route(@NonNull File file) {
        PersistenceStore store = get();
        if (store == null) {
            return null;
        }
        String key = keyFor(file);
        if (key == null || !store.owns(key)) {
            return null;
        }
        return new Route(store, key);
    }

    /**
     * Computes the key for a file under {@link Jenkins#getRootDir}.
     *
     * @return the key, or null if the file is outside {@code JENKINS_HOME}
     */
    @CheckForNull
    public static String keyFor(@NonNull File file) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        Path root = j.getRootDir().toPath().toAbsolutePath().normalize();
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            return null;
        }
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /**
     * The local file corresponding to a key.
     */
    @NonNull
    public static File fileFor(@NonNull String key) {
        return new File(Jenkins.get().getRootDir(), key.replace('/', File.separatorChar));
    }

    /**
     * Records the version of a key as seen by this JVM, for example after applying a change made elsewhere.
     */
    public static void seen(@NonNull String key, long version) {
        if (version > 0) {
            versions.put(key, version);
        } else {
            versions.remove(key);
        }
    }

    /**
     * The version of a key this JVM last read or wrote, or {@link PersistenceStore#ANY} if unknown.
     */
    public static long lastSeen(@NonNull String key) {
        return versions.getOrDefault(key, PersistenceStore.ANY);
    }

    /**
     * Number of conflicting writes detected (and, under the {@code warn} policy, overwritten) by this JVM.
     */
    public static long getConflictCount() {
        return conflicts.get();
    }

    @Restricted(NoExternalUse.class)
    public static void setForTesting(@CheckForNull PersistenceStore store) {
        testingStore = store;
        versions.clear();
        conflicts.set(0);
    }

    /**
     * A file served by the store, with version tracking and the conflict policy applied.
     */
    public record Route(@NonNull PersistenceStore store, @NonNull String key) {

        @CheckForNull
        public PersistenceStore.Entry read() throws IOException {
            PersistenceStore.Entry entry = store.read(key);
            seen(key, entry == null ? PersistenceStore.ABSENT : entry.version());
            return entry;
        }

        public boolean exists() throws IOException {
            return store.read(key) != null;
        }

        public long write(@NonNull byte[] content) throws IOException {
            long expected = lastSeen(key);
            long version;
            try {
                version = store.write(key, content, expected);
            } catch (ConflictException e) {
                conflicts.incrementAndGet();
                if ("fail".equals(CONFLICTS)) {
                    throw e;
                }
                LOGGER.log(Level.WARNING, e.getMessage() + "; overwriting (last writer wins)");
                version = store.write(key, content, PersistenceStore.ANY);
            }
            seen(key, version);
            return version;
        }

        public void delete() throws IOException {
            try {
                store.delete(key, lastSeen(key));
            } catch (ConflictException e) {
                conflicts.incrementAndGet();
                if ("fail".equals(CONFLICTS)) {
                    throw e;
                }
                LOGGER.log(Level.WARNING, e.getMessage() + "; deleting anyway");
                store.delete(key, PersistenceStore.ANY);
            }
            versions.remove(key);
        }
    }
}
