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
import hudson.model.Item;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Locates the active {@link PersistenceStore}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class PersistenceStores {

    private static final Logger LOGGER = Logger.getLogger(PersistenceStores.class.getName());

    /** The store resolved for a Jenkins, kept in its {@link Jenkins#lookup}. */
    private record Resolved(@CheckForNull PersistenceStore store) {}

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
        Resolved resolved = j.lookup.get(Resolved.class);
        if (resolved != null) {
            return resolved.store();
        }
        if (j.getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) < 0 || RESOLVING.get() != null) {
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            // resolved under the lock that loading extensions takes, not one of our own: a thread holding it (as one
            // making an extension that reads its files) may get here, and a provider may load extensions
            synchronized (ExtensionList.loadLock(j)) {
                resolved = j.lookup.get(Resolved.class);
                if (resolved == null) {
                    PersistenceStore store = null;
                    for (PersistenceStoreProvider provider : ExtensionList.lookup(PersistenceStoreProvider.class)) {
                        store = provider.create();
                        if (store != null) {
                            LOGGER.info(() -> "Using persistence store " + provider.getClass().getName());
                            break;
                        }
                    }
                    resolved = new Resolved(store);
                    j.lookup.set(Resolved.class, resolved);
                }
                return resolved.store();
            }
        } finally {
            RESOLVING.remove();
        }
    }

    /**
     * The identity the active store keeps for an item, which stays the same when the item is renamed or moved, and is
     * not given to an item created later under the same name (see {@link PersistenceStore#itemIdentity}).
     * Code keeping data about items outside {@code JENKINS_HOME} can key it by this, and by the full name when it is
     * null.
     *
     * @return the identity, or null if there is no store, the store keeps no identities, or the item is not stored
     *     (as one whose configuration was not saved yet, or one outside {@code JENKINS_HOME})
     */
    @Restricted(Beta.class)
    @CheckForNull
    public static String itemIdentity(@NonNull Item item) throws IOException {
        PersistenceStore store = get();
        if (store == null) {
            return null;
        }
        String key = keyOf(Jenkins.get().getRootDir().toPath(), item.getRootDir().toPath());
        return key == null ? null : store.itemIdentity(key);
    }

    /**
     * The store key of a directory: its path relative to {@code JENKINS_HOME}, worked out from the names alone (the
     * file system is not looked at, so links are not followed), or null if it is not below {@code JENKINS_HOME}.
     */
    @CheckForNull
    static String keyOf(@NonNull Path home, @NonNull Path dir) {
        Path base = home.toAbsolutePath().normalize();
        Path path = dir.toAbsolutePath().normalize();
        if (!path.startsWith(base) || path.equals(base)) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (Path name : base.relativize(path)) {
            if (!key.isEmpty()) {
                key.append('/');
            }
            key.append(name);
        }
        return key.toString();
    }
}
