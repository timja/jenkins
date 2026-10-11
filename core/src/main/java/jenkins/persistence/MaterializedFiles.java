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
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Local copies of stored files handed out by {@code XmlFile.getFile()}, written back when changed (Target 15).
 *
 * <p>Plugins that write a configuration file directly ({@code getFile()} plus their own writer) used to change only the
 * local copy, which nothing read back: the change was lost, and the next {@code getFile()} overwrote it with the stored
 * content. Now a changed copy is written to the store, merged with what was stored meanwhile (against the content the
 * copy was made from): at the next {@code getFile()} of that file, before the copy would be refreshed, and every
 * minute. Other controllers then apply it as any other change. The object in memory here, if any, is not reloaded:
 * as on a single controller, a file written behind an object's back takes effect when it is next loaded.
 */
@Restricted(NoExternalUse.class)
public final class MaterializedFiles {

    private static final Logger LOGGER = Logger.getLogger(MaterializedFiles.class.getName());

    /** Copies larger than this are not tracked (their content would be held in memory as the merge base). */
    static final int MAX_SIZE = 1024 * 1024;

    /** A copy as made: its file, the content it was given, the version of that content, its modification time. */
    record Copy(File file, byte[] content, long version, long modified) {}

    private static final Map<String, Copy> copies = new ConcurrentHashMap<>();

    private MaterializedFiles() {}

    /** Records that {@code file} now holds {@code entry} of {@code key}. */
    public static void materialized(@NonNull String key, @NonNull File file, @NonNull PersistenceStore.Entry entry) {
        if (entry.content().length > MAX_SIZE) {
            copies.remove(key);
            return;
        }
        copies.put(key, new Copy(file, entry.content(), entry.version(), file.lastModified()));
    }

    /**
     * Writes the local copy of a routed file back to the store if it was changed since it was made.
     *
     * @return whether it was written
     */
    public static boolean writeBack(@NonNull PersistenceStores.Route route) {
        Copy c = copies.get(route.key());
        if (c == null || !c.file().isFile() || c.file().lastModified() == c.modified()) {
            return false;
        }
        try {
            byte[] mine = Files.readAllBytes(c.file().toPath());
            if (Arrays.equals(mine, c.content())) {
                copies.put(route.key(), new Copy(c.file(), c.content(), c.version(), c.file().lastModified()));
                return false;
            }
            byte[] written = write(route, c, mine);
            if (written == null) {
                return false;
            }
            if (!Arrays.equals(written, mine)) {
                Files.write(c.file().toPath(), written); // merged with a change made elsewhere: the copy has both
            }
            PersistenceStore.Entry now = route.peek();
            if (now != null) {
                materialized(route.key(), c.file(), now);
            }
            LOGGER.info(() -> "Wrote " + route.key() + " back to the store: it was changed directly on disk here");
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write " + c.file() + " back to the store", e);
            return false;
        }
    }

    /** @return what was written, or null if nothing was */
    @CheckForNull
    private static byte[] write(PersistenceStores.Route route, Copy c, byte[] mine) throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            PersistenceStore.Entry theirs = route.peek();
            if (theirs == null) {
                return null; // deleted meanwhile: the copy is stale
            }
            byte[] content = mine;
            if (theirs.version() != c.version()) {
                XmlMerge.Result merged = XmlMerge.merge(c.content(), mine, theirs.content());
                if (merged == null) {
                    LOGGER.warning(() -> route.key() + " was changed both on disk here and elsewhere, and cannot be merged; keeping this change");
                } else {
                    content = merged.merged();
                }
            }
            try {
                route.store().write(route.key(), content, theirs.version());
                return content;
            } catch (ConflictException again) {
                // changed again meanwhile: merge against that
            }
        }
        return null;
    }

    /** Writes changed copies back every minute (and forgets copies of files no longer stored). */
    @Restricted(NoExternalUse.class)
    @Extension
    public static final class WriteBack extends AsyncPeriodicWork {
        public WriteBack() {
            super("Files changed directly on disk");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(Long.getLong(MaterializedFiles.class.getName() + ".periodSeconds", 60));
        }

        @Override
        protected void execute(TaskListener listener) {
            for (Map.Entry<String, Copy> e : copies.entrySet()) {
                PersistenceStores.Route route = PersistenceStores.route(e.getValue().file());
                if (route == null) {
                    copies.remove(e.getKey());
                } else {
                    writeBack(route);
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }
}
