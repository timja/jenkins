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
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractItem;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Opt-in sharing of the other files plugins keep in item directories (Target 15), for plugins that write state next to
 * {@code config.xml} with their own file APIs: with {@code -Djenkins.persistence.ItemFiles.enabled=true}, every minute
 * each item's directory is compared with what was last shared; changed and removed files are shared (a manifest per item,
 * {@code <item>/item-files.json}, mapping paths to checksums, with the files in blob storage), and other controllers
 * write them into their own copy of the directory.
 *
 * <p>Left out: what the store holds already ({@code config.xml} and the like), builds, workspaces, sub-items, files
 * declared per controller ({@link LocalFiles}, {@link PerController}), and files larger than {@link #MAX_SIZE}. The last
 * writer of a file wins; plugins that cache a file's content in memory see a change made elsewhere only once they read
 * it again (as when it is changed on disk on a single controller).
 */
@Restricted(NoExternalUse.class)
public final class ItemFiles {

    private static final Logger LOGGER = Logger.getLogger(ItemFiles.class.getName());

    static boolean enabled() {
        return SystemProperties.getBoolean(ItemFiles.class.getName() + ".enabled");
    }

    static final long MAX_SIZE = SystemProperties.getLong(ItemFiles.class.getName() + ".maxSize", 1024L * 1024);
    static final String MANIFEST = "item-files.json";
    static final String BLOBS = "item-files";
    /** Directories of an item's directory never shared this way. */
    static final Set<String> SKIPPED = Set.of("builds", "workspace", "running", "jobs", "branches", "promotions", "configurations",
            "indexing", "lastStable", "lastSuccessful");

    /** What was last shared or taken here, per item key: path → checksum. */
    private static final Map<String, Map<String, String>> known = new java.util.concurrent.ConcurrentHashMap<>();

    private ItemFiles() {}

    /** For tests: as a controller that never shared or took an item's files (another JVM). */
    static void forgetForTests() {
        known.clear();
    }

    /** The files to share of one item: relative path → checksum. */
    static Map<String, String> scan(@NonNull File dir, @NonNull String itemKey, @NonNull PersistenceStore store) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path root = dir.toPath();
        if (!Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                if (d.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = d.getFileName().toString();
                return d.getParent().equals(root) && (SKIPPED.contains(name) || name.startsWith("workspace")) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                String key = itemKey + "/" + rel;
                if (attrs.isRegularFile() && attrs.size() <= MAX_SIZE && !rel.equals(MANIFEST) && !rel.endsWith(".tmp")
                        && !store.owns(key) && !PersistenceStores.declaredLocal(key)) {
                    files.put(rel, sha256(f));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Shares what changed in one item's directory since it was last shared or taken here. */
    static void publish(@NonNull Item item) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        String itemKey = PersistenceStores.keyFor(item.getRootDir());
        if (store == null || blobs == null || itemKey == null) {
            return;
        }
        Map<String, String> local = scan(item.getRootDir(), itemKey, store);
        Map<String, String> last = known.getOrDefault(itemKey, Map.of());
        if (local.equals(last)) {
            return;
        }
        for (Map.Entry<String, String> f : local.entrySet()) {
            if (!f.getValue().equals(last.get(f.getKey()))) {
                Path p = item.getRootDir().toPath().resolve(f.getKey());
                try (InputStream in = Files.newInputStream(p)) {
                    blobs.put(itemKey + "/" + BLOBS + "/" + f.getValue(), in, Files.size(p));
                }
            }
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            PersistenceStore.Entry e = store.read(itemKey + "/" + MANIFEST);
            Map<String, String> shared = parse(e);
            for (Map.Entry<String, String> f : local.entrySet()) {
                if (!f.getValue().equals(last.get(f.getKey()))) {
                    shared.put(f.getKey(), f.getValue()); // changed here
                }
            }
            for (String gone : last.keySet()) {
                if (!local.containsKey(gone) && last.get(gone).equals(shared.get(gone))) {
                    shared.remove(gone); // removed here, and not changed elsewhere since
                }
            }
            try {
                store.write(itemKey + "/" + MANIFEST, toBytes(shared), e == null ? PersistenceStore.ABSENT : e.version());
                known.put(itemKey, local);
                LOGGER.fine(() -> "Shared the files of " + item.getFullName());
                return;
            } catch (ConflictException again) {
                // changed elsewhere at the same moment: apply ours on top of theirs
            }
        }
    }

    /** Writes the shared files of an item into its directory here (after a change elsewhere, or at startup). */
    static void take(@NonNull Item item) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        String itemKey = PersistenceStores.keyFor(item.getRootDir());
        if (store == null || blobs == null || itemKey == null) {
            return;
        }
        Map<String, String> shared = parse(store.read(itemKey + "/" + MANIFEST));
        Map<String, String> local = scan(item.getRootDir(), itemKey, store);
        Map<String, String> last = known.getOrDefault(itemKey, Map.of());
        // what is in sync after this: what was taken, plus what was in sync before (not what changed here meanwhile)
        Map<String, String> synced = new TreeMap<>(last);
        for (Map.Entry<String, String> f : shared.entrySet()) {
            String here = local.get(f.getKey());
            // only where this copy did not change since it was last shared or taken: a change made here, not shared yet,
            // wins (the next publication shares it)
            boolean unchangedHere = here == null ? !last.containsKey(f.getKey()) : here.equals(last.get(f.getKey()));
            if (!f.getValue().equals(here) && unchangedHere) {
                Path target = item.getRootDir().toPath().resolve(f.getKey()).normalize();
                if (!target.startsWith(item.getRootDir().toPath())) {
                    continue; // never outside the item's directory
                }
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), ".item-file", ".tmp");
                try (InputStream in = blobs.get(itemKey + "/" + BLOBS + "/" + f.getValue())) {
                    if (in == null) {
                        Files.deleteIfExists(tmp);
                        continue;
                    }
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                synced.put(f.getKey(), f.getValue());
            } else if (f.getValue().equals(here)) {
                synced.put(f.getKey(), here);
            }
        }
        for (String path : last.keySet()) {
            if (!shared.containsKey(path) && last.get(path).equals(local.get(path))) {
                Files.deleteIfExists(item.getRootDir().toPath().resolve(path)); // removed elsewhere, unchanged here
                synced.remove(path);
            }
        }
        known.put(itemKey, synced);
    }

    private static Map<String, String> parse(@CheckForNull PersistenceStore.Entry e) {
        Map<String, String> files = new TreeMap<>();
        if (e != null) {
            JSONObject o = JSONObject.fromObject(new String(e.content(), StandardCharsets.UTF_8));
            for (Object k : o.keySet()) {
                files.put((String) k, o.getString((String) k));
            }
        }
        return files;
    }

    private static byte[] toBytes(Map<String, String> files) {
        JSONObject o = new JSONObject();
        o.putAll(files);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(Path f) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Whether a key is an item's manifest: {@code jobs/a[/jobs/b…]/item-files.json}. */
    @CheckForNull
    static String itemOfManifest(@NonNull String key) {
        if (!key.endsWith("/" + MANIFEST)) {
            return null;
        }
        String itemConfig = key.substring(0, key.length() - MANIFEST.length()) + "config.xml";
        java.util.List<String> names = ItemChangeApplier.itemPath(itemConfig);
        return names == null ? null : String.join("/", names);
    }

    /** Applies a manifest written elsewhere. */
    static void changedElsewhere(@NonNull String key) {
        if (!enabled()) {
            return;
        }
        String fullName = itemOfManifest(key);
        Item item = fullName == null ? null : Jenkins.get().getItemByFullName(fullName);
        if (item != null) {
            try {
                take(item);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not take the shared files of " + fullName, e);
            }
        }
    }

    /** A fresh home takes every item's shared files. */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void takeAll() {
        if (!enabled() || PersistenceStores.get() == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        store.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN && e.key().endsWith("/" + MANIFEST)
                    && !store.getOrigin().equals(e.origin())) {
                jenkins.util.Timer.get().submit(() -> changedElsewhere(e.key()));
            }
        });
        for (Item item : Jenkins.get().allItems()) {
            if (item instanceof AbstractItem) {
                try {
                    take(item);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not take the shared files of " + item.getFullName(), e);
                }
            }
        }
    }

    /** Shares what plugins changed in item directories, every minute. */
    @Extension
    public static final class Publisher extends AsyncPeriodicWork {
        public Publisher() {
            super("Share files in item directories");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(SystemProperties.getLong(ItemFiles.class.getName() + ".periodSeconds", 60L));
        }

        @Override
        protected void execute(TaskListener listener) {
            if (!enabled() || PersistenceStores.get() == null) {
                return;
            }
            for (Item item : Jenkins.get().allItems()) {
                if (item instanceof AbstractItem) {
                    try {
                        publish(item);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Could not share the files of " + item.getFullName(), e);
                    }
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }
}
