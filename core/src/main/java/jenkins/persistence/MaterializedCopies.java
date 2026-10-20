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
import hudson.util.AtomicFileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores.MaterializedCopy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The local copies of stored files handed out by {@code XmlFile.getFile()}
 * ({@link PersistenceStores#materializedCopies}).
 */
@Restricted(NoExternalUse.class)
public final class MaterializedCopies {

    private static final Logger LOGGER = Logger.getLogger(MaterializedCopies.class.getName());

    private static AutoCloseable subscription;

    /** Copies larger than this are not tracked: they are made afresh on every use, and never kept as changed locally. */
    static final int MAX_COPY_SIZE = 1024 * 1024;
    private static final Map<String, MaterializedCopy> copies = new ConcurrentHashMap<>();
    /** Keys whose copy is known to hold the stored content: no change to them has been seen since it was made. */
    private static final Set<String> current = ConcurrentHashMap.newKeySet();
    private static volatile boolean subscribed;
    /** Locks for keys, shared by keys of equal hash: a copy is not replaced by two threads at once. */
    private static final Object[] LOCKS = new Object[64];

    static {
        Arrays.setAll(LOCKS, i -> new Object());
    }

    /**
     * Brings the local copy of a stored file up to date for a caller of {@code XmlFile.getFile()}, which reads the file
     * directly. Reads the store at most once, and not at all while the copy is current (no change to the key has been
     * seen since it was made). A copy changed locally since it was made is left as it is. If the store cannot be read,
     * the file is left as it is too.
     */
    @Restricted(NoExternalUse.class)
    public static void materialize(@NonNull Route route, @NonNull File file) {
        String key = route.key();
        MaterializedCopy c = copies.get(key);
        if (c != null && subscribed && current.contains(key) && c.file().equals(file) && asMade(c)) {
            return;
        }
        synchronized (LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]) {
            current.add(key); // before reading: a change seen from now on makes it stale again
            try {
                PersistenceStore.Entry entry = route.peek();
                if (entry == null) {
                    copies.remove(key);
                    current.remove(key);
                    if (c != null && c.file().equals(file) && file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), c.content())) {
                        Files.delete(file.toPath()); // a copy of what is no longer stored, as it was made
                    }
                    return;
                }
                if (file.isFile()) {
                    byte[] disk = Files.readAllBytes(file.toPath());
                    if (Arrays.equals(disk, entry.content())) {
                        made(key, file, entry);
                        return;
                    }
                    if (c != null && c.file().equals(file) && !Arrays.equals(disk, c.content())) {
                        // changed here since it was made: not replaced, so that the change is not lost
                        LOGGER.fine(() -> file + " was changed on disk since it was copied from " + key + "; leaving it as it is");
                        current.remove(key);
                        return;
                    }
                }
                LOGGER.log(Level.FINE, () -> "Copying " + key + " to " + file);
                Files.createDirectories(file.toPath().getParent());
                // replaced at once, never truncated in place: a reader of the file sees the old copy or the new one whole
                AtomicFileWriter w = new AtomicFileWriter(file.toPath(), StandardCharsets.ISO_8859_1);
                try {
                    w.write(new String(entry.content(), StandardCharsets.ISO_8859_1)); // each byte as it is
                    w.commit();
                } finally {
                    w.abort();
                }
                made(key, file, entry);
            } catch (IOException | InvalidPathException e) {
                current.remove(key);
                LOGGER.log(Level.WARNING, "Failed to copy " + key + " to " + file + "; leaving the file as it is", e);
            }
        }
    }

    private static boolean asMade(MaterializedCopy c) {
        return c.file().lastModified() == c.modified() && c.file().length() == c.content().length;
    }

    private static void made(String key, File file, PersistenceStore.Entry entry) {
        if (entry.content().length > MAX_COPY_SIZE) {
            copies.remove(key);
            current.remove(key);
            return;
        }
        // dated a little in the past: any write after this one then has another modification time, even on a file
        // system counting in seconds, so that a change is never taken for the copy as made
        long dated = (System.currentTimeMillis() / 1000 - 10) * 1000;
        if (!file.setLastModified(dated)) {
            dated = file.lastModified();
        }
        copies.put(key, new MaterializedCopy(key, file, entry.content(), entry.version(), dated));
    }

    private MaterializedCopies() {}

    /** The copies currently handed out. */
    static Collection<MaterializedCopy> all() {
        return List.copyOf(copies.values());
    }

    /** Records that a key was written or deleted here: its copy is not known to be current any more. */
    static void changed(String key) {
        current.remove(key);
    }

    /** Stops following the changes of the store resolved before, and forgets its copies. */
    static void reset() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not unsubscribe from the previous store", e);
            }
            subscription = null;
        }
        copies.clear();
        current.clear();
    }

    /** Follows the changes of the store resolved, if any. */
    static void subscribe(@CheckForNull PersistenceStore store) {
        if (store != null) {
            try {
                // a local copy is current until its key changes: one read per change, however often it is asked for
                subscription = store.subscribe(e -> {
                    current.remove(e.key());
                    if (e.oldKey() != null) {
                        current.remove(e.oldKey());
                    }
                });
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not subscribe to changes of the persistence store; local copies are refreshed on every use", e);
                subscribed = false;
                return;
            }
            subscribed = true;
        }
    }
}
