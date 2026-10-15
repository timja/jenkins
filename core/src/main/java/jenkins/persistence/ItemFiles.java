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
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
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
 * Opt-in sharing of the other files plugins keep in item and user directories (Target 15), for plugins that write state
 * next to {@code config.xml} with their own file APIs (such as the avatar plugin's {@code users/<id>/avatar.png}): with {@code -Djenkins.persistence.ItemFiles.enabled=true}, every minute
 * each item's and user's directory is compared with what was last shared; changed and removed files are shared (a
 * manifest per directory, {@code <item or user>/item-files.json}, mapping paths to checksums, with the files in blob
 * storage), and other controllers write them into their own copy of the directory.
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
    /** What was last shared or taken of a directory, kept in it: the baseline of its next sync, after a restart too. */
    static final String KNOWN = ".item-files-known.json";
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
                if (attrs.isRegularFile() && attrs.size() <= MAX_SIZE && shared(rel, itemKey, store)) {
                    files.put(rel, sha256(f));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Whether a file of an item's directory, by its path relative to it, is one shared this way: what {@link #scan}
     * shares, and all that {@link #take} writes, whatever a manifest names.
     */
    static boolean shared(@NonNull String rel, @NonNull String itemKey, @NonNull PersistenceStore store) {
        String[] segments = rel.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                return false;
            }
        }
        if (segments.length > 1 && (SKIPPED.contains(segments[0]) || segments[0].startsWith("workspace"))) {
            return false;
        }
        String key = itemKey + "/" + rel;
        return !rel.equals(MANIFEST) && !rel.equals(KNOWN) && !rel.endsWith(".tmp") && !store.owns(key) && !PersistenceStores.declaredLocal(key)
                && !PersistenceStores.isKeptLocal(key);
    }

    /** Shares what changed in one item's directory since it was last shared or taken here. */
    static void publish(@NonNull Item item) throws IOException {
        publish(item.getRootDir());
    }

    /** Shares what changed in an item's or user's directory since it was last shared or taken here. */
    static void publish(@NonNull File dir) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        String itemKey = PersistenceStores.keyFor(dir);
        if (store == null || blobs == null || itemKey == null) {
            return;
        }
        Map<String, String> local = scan(dir, itemKey, store);
        Map<String, String> last = last(dir, itemKey);
        if (local.equals(last)) {
            return;
        }
        for (Map.Entry<String, String> f : local.entrySet()) {
            if (!f.getValue().equals(last.get(f.getKey()))) {
                Path p = dir.toPath().resolve(f.getKey());
                try (InputStream in = Files.newInputStream(p)) {
                    blobs.put(itemKey + "/" + BLOBS + "/" + f.getValue(), in, Files.size(p));
                }
            }
        }
        String config = itemKey + "/config.xml";
        if (store.owns(config) && store.read(config) == null) {
            return; // deleted meanwhile (here or elsewhere): its manifest is not brought back
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
                remember(dir, itemKey, local);
                LOGGER.fine(() -> "Shared the files of " + itemKey);
                return;
            } catch (ConflictException again) {
                // changed elsewhere at the same moment: apply ours on top of theirs
            }
        }
    }

    /** Writes the shared files of an item into its directory here (after a change elsewhere, or at startup). */
    static void take(@NonNull Item item) throws IOException {
        take(item.getRootDir());
    }

    /** Writes the shared files of an item's or user's directory into it here. */
    static void take(@NonNull File dir) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        String itemKey = PersistenceStores.keyFor(dir);
        if (store == null || blobs == null || itemKey == null) {
            return;
        }
        Map<String, String> shared = parse(store.read(itemKey + "/" + MANIFEST));
        Map<String, String> last = last(dir, itemKey);
        if (shared.isEmpty() && !known.containsKey(itemKey)) {
            return; // nothing shared, nothing taken before: leave the directory as it is (or absent)
        }
        Map<String, String> local = scan(dir, itemKey, store);
        // what is in sync after this: what was taken, plus what was in sync before (not what changed here meanwhile)
        Map<String, String> synced = new TreeMap<>(last);
        for (Map.Entry<String, String> f : shared.entrySet()) {
            String here = local.get(f.getKey());
            // only where this copy did not change since it was last shared or taken: a change made here, not shared yet,
            // wins (the next publication shares it)
            boolean unchangedHere = here == null ? !last.containsKey(f.getKey()) : here.equals(last.get(f.getKey()));
            if (!f.getValue().equals(here) && unchangedHere) {
                Path target = dir.toPath().resolve(f.getKey()).normalize();
                if (!target.startsWith(dir.toPath()) || !shared(f.getKey(), itemKey, store) || throughLink(dir.toPath(), target)) {
                    // never outside the item's directory, nor a file that is not shared this way (its configuration, a
                    // build's), nor through a link
                    LOGGER.warning(() -> "Not taking " + f.getKey() + " of " + itemKey + ": not a file shared this way");
                    continue;
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
                Files.deleteIfExists(dir.toPath().resolve(path)); // removed elsewhere, unchanged here
                synced.remove(path);
            }
        }
        remember(dir, itemKey, synced);
    }

    /** What was last shared or taken of a directory: as known in memory, or as kept in it (as before a restart). */
    private static Map<String, String> last(File dir, String itemKey) {
        Map<String, String> files = known.get(itemKey);
        if (files != null) {
            return files;
        }
        Path f = dir.toPath().resolve(KNOWN);
        if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                files = parse(new PersistenceStore.Entry(Files.readAllBytes(f), PersistenceStore.ANY));
                known.put(itemKey, files);
                return files;
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not read " + f, e);
            }
        }
        return Map.of();
    }

    /** Records what was shared or taken of a directory, in memory and in it. */
    private static void remember(File dir, String itemKey, Map<String, String> files) {
        known.put(itemKey, files);
        if (!dir.isDirectory()) {
            return;
        }
        try {
            Path tmp = Files.createTempFile(dir.toPath(), ".item-files-known", ".tmp");
            try {
                Files.write(tmp, toBytes(files));
                Files.move(tmp, dir.toPath().resolve(KNOWN), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not keep what was shared of " + dir, e);
        }
    }

    /** Whether a directory between an item's directory and a file in it is a link (or something else than a directory). */
    private static boolean throughLink(Path root, Path target) {
        for (Path d = target.getParent(); d != null && !d.equals(root); d = d.getParent()) {
            if (Files.isSymbolicLink(d) || Files.exists(d, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(d, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
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

    /** Whether a key is a user's manifest: {@code users/<folder>/item-files.json} → the user's directory. */
    @CheckForNull
    static File userDirOfManifest(@NonNull String key) {
        String[] segments = key.split("/");
        if (segments.length != 3 || !segments[0].equals("users") || !segments[2].equals(MANIFEST) || segments[1].startsWith(".")) {
            return null;
        }
        return new File(new File(Jenkins.get().getRootDir(), "users"), segments[1]);
    }

    /** Applies a manifest written elsewhere. */
    static void changedElsewhere(@NonNull String key) {
        if (!enabled()) {
            return;
        }
        File dir = userDirOfManifest(key);
        if (dir == null) {
            String fullName = itemOfManifest(key);
            Item item;
            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) { // whoever may see it
                item = fullName == null ? null : Jenkins.get().getItemByFullName(fullName);
            }
            if (item == null) {
                return;
            }
            dir = item.getRootDir();
        }
        try {
            take(dir);
            failed.remove(dir);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not take the shared files of " + key + "; trying again with the next publication", e);
            failed.add(dir);
        }
    }

    /** Directories whose shared files could not be taken when they changed elsewhere: taken again by the {@link Publisher}. */
    private static final Set<File> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        for (File dir : userDirs()) {
            try {
                take(dir);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not take the shared files of " + dir, e);
            }
        }
    }

    /** The directories of the users known here. */
    static java.util.List<File> userDirs() {
        java.util.List<File> dirs = new java.util.ArrayList<>();
        for (hudson.model.User u : hudson.model.User.getAll()) {
            File dir = u.getUserFolder();
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /** {@code a/b} → {@code jobs/a/jobs/b}: the key of an item's directory by its full name. */
    private static String itemKey(String fullName) {
        return "jobs/" + fullName.replace("/", "/jobs/");
    }

    /**
     * Removes the shared files of a deleted item (or a deleted folder's items), so that an item made or moved there later
     * neither takes them nor finds its destination in use; keeps the record of what was shared with a renamed or moved one.
     */
    @Extension
    public static final class Lifecycle extends ItemListener {
        @Override
        public void onDeleted(Item item) {
            PersistenceStore store = PersistenceStores.get();
            BlobStore blobs = PersistenceStores.getBlobStore();
            String itemKey = PersistenceStores.keyFor(item.getRootDir());
            if (store == null || itemKey == null) {
                return;
            }
            known.keySet().removeIf(k -> k.equals(itemKey) || k.startsWith(itemKey + "/"));
            if (PersistenceStores.isApplyingRemoteChange()) {
                return; // deleted elsewhere: that controller removes them
            }
            try {
                if (store.read(itemKey + "/" + MANIFEST) != null) {
                    store.delete(itemKey + "/" + MANIFEST, PersistenceStore.ANY);
                    if (blobs != null) {
                        blobs.delete(itemKey + "/" + BLOBS);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not remove the shared files of " + item.getFullName(), e);
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            String from = itemKey(oldFullName);
            String to = itemKey(newFullName);
            for (String k : Set.copyOf(known.keySet())) {
                if (k.equals(from) || k.startsWith(from + "/")) {
                    Map<String, String> files = known.remove(k);
                    if (files != null) {
                        known.put(to + k.substring(from.length()), files);
                    }
                }
            }
        }
    }

    /** Shares what plugins changed in item directories, every minute. */
    @Extension
    public static final class Publisher extends AsyncPeriodicWork {
        public Publisher() {
            super("Share files in item and user directories");
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
            for (File dir : Set.copyOf(failed)) {
                try {
                    take(dir);
                    failed.remove(dir);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Still cannot take the shared files of " + dir, e);
                }
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
            for (File dir : userDirs()) {
                try {
                    publish(dir);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not share the files of " + dir, e);
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }
}
