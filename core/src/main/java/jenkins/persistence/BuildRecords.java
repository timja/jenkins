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
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.RunMap;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.listeners.SaveableListener;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import jenkins.model.PeepholePermalink;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.util.ThrowingCallable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shares completed builds between controllers.
 *
 * <p>When a build completes, the controller which ran it publishes a snapshot of its build directory
 * to the {@link BlobStore} ({@code jobs/<item>/builds/<N>/files.tgz}) and then its {@code build.xml}
 * to the {@link PersistenceStore} ({@code jobs/<item>/builds/<N>/build.xml}).
 * The record is written last, so a record in the store means a complete build.
 * Other controllers list stored builds alongside local ones, and extract the snapshot into the local
 * build directory the first time the build is loaded, so that every reader of the build directory works unchanged.
 *
 * <p>The records are written directly by this class, not routed through {@link XmlFile}:
 * a running build saves its {@code build.xml} locally as usual, and only the completed record is shared.
 * A store takes part by answering {@link PersistenceStore#storesBuildRecords}.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class BuildRecords {

    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());
    /** Stores warned about, once each. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    static final String RECORD = "build.xml";
    static final String SNAPSHOT = "files.tgz";
    /** The stand-in snapshot of a build closed as lost: its own key, so that it never replaces a real snapshot. */
    static final String LOST_SNAPSHOT = "lost.tgz";

    /**
     * Never part of a snapshot: the record itself, artifacts (use an external {@link jenkins.model.ArtifactManager}),
     * and state only needed while a build runs.
     */
    static final List<String> DEFAULT_EXCLUDES = List.of(RECORD, "archive/**", "program.dat", "workflow/**", "stashes/**", "**/*.tmp");

    /** Set while this thread loads a build, so that saves made while loading are not republished. */
    private static final ThreadLocal<Boolean> LOADING = new ThreadLocal<>();

    /**
     * The version of each build record this controller last wrote, read or was told of, by job ({@code jobs/p/builds})
     * and number: what it knows to be that job's, so that a deletion applied once the name is taken again removes only
     * that ({@link ItemDeleted}).
     */
    private static final Map<String, Map<Integer, Long>> seenRecords = new ConcurrentHashMap<>();

    /** Notes the version of a build record seen; {@link PersistenceStore#ABSENT} (or less) when it is gone. */
    static void seen(@NonNull String recordKey, long version) {
        RecordKey record = RecordKey.parse(recordKey);
        if (record == null) {
            return;
        }
        String builds = record.jobKey() + "/builds";
        if (version > PersistenceStore.ABSENT) {
            seenRecords.computeIfAbsent(builds, k -> new ConcurrentHashMap<>()).put(record.number(), version);
        } else {
            Map<Integer, Long> seen = seenRecords.get(builds);
            if (seen != null) {
                seen.remove(record.number());
            }
        }
    }

    private BuildRecords() {}

    /**
     * Leaves files out of build snapshots, for example because a plugin stores them elsewhere.
     */
    @Restricted(Beta.class)
    public interface Exclusion extends ExtensionPoint {
        /**
         * @return Ant-style patterns relative to the build directory
         */
        @NonNull
        Collection<String> excludes(@NonNull Run<?, ?> run);
    }

    /**
     * The key below which the builds of a job are recorded, or null if build records are not shared.
     */
    @CheckForNull
    static String buildsKey(@NonNull Job<?, ?> job) {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return null;
        }
        String jobKey = PersistenceStores.keyFor(job.getRootDir());
        if (jobKey == null || !store.storesBuildRecords(jobKey)) {
            return null;
        }
        if (!store.owns(jobKey + "/nextBuildNumber")) {
            // controllers numbering builds each on their own would publish the same numbers over each other's
            if (WARNED.add(store.getClass().getName())) {
                LOGGER.warning(() -> store.getClass().getName() + " stores build records but not build numbers; builds are not shared");
            }
            return null;
        }
        return jobKey + "/builds";
    }

    /**
     * Whether builds of this job are shared, so that build numbers taken by other controllers may appear at any time.
     */
    @Restricted(Beta.class) // e.g. importing a file-based home
    public static boolean isShared(@NonNull Job<?, ?> job) {
        return buildsKey(job) != null;
    }

    /**
     * Numbers of the builds of a job recorded in the store; empty if they cannot be listed (logged).
     */
    @Restricted(Beta.class)
    @NonNull
    public static Set<Integer> storedNumbers(@NonNull Job<?, ?> job) {
        try {
            return listStoredNumbers(job);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list stored builds of " + job.getFullName(), e);
            return new TreeSet<>();
        }
    }

    /**
     * Numbers of the builds of a job recorded in the store, for callers that must tell "none" from "unknown", such as
     * an import deciding what to publish: publishing overwrites what is stored.
     *
     * @throws IOException if the store cannot list them
     */
    @Restricted(Beta.class) // e.g. importing a file-based home
    @NonNull
    public static Set<Integer> listStoredNumbers(@NonNull Job<?, ?> job) throws IOException {
        Set<Integer> numbers = new TreeSet<>();
        String builds = buildsKey(job);
        if (builds == null) {
            return numbers;
        }
        for (String name : PersistenceStores.get().children(builds)) {
            try {
                numbers.add(Integer.parseInt(name));
            } catch (NumberFormatException e) {
                // not a build
            }
        }
        return numbers;
    }

    /**
     * Publishes a completed build.
     */
    @Restricted(Beta.class) // e.g. importing a file-based home
    public static void publish(@NonNull Run<?, ?> run) throws IOException {
        publish(run, false);
    }

    /**
     * @param filesChanged whether only files of the build directory changed (not its record): the record is then marked
     *     so that it gets a new version, and other controllers fetch the new snapshot
     */
    private static void publish(@NonNull Run<?, ?> run, boolean filesChanged) throws IOException {
        String builds = buildsKey(run.getParent());
        if (builds == null) {
            return;
        }
        String base = builds + "/" + run.getNumber();
        synchronized (deleted) {
            publishing.merge(base, 1, Integer::sum);
        }
        try {
            publish(run, base);
        } finally {
            synchronized (deleted) {
                publishing.computeIfPresent(base, (k, n) -> n == 1 ? null : n - 1);
            }
        }
    }

    private static void publish(Run<?, ?> run, String base) throws IOException {
        File dir = run.getRootDir();
        Path record = new File(dir, RECORD).toPath();
        if (!Files.isRegularFile(record)) {
            LOGGER.warning(() -> "Not publishing " + run + ": no " + record);
            return;
        }
        // held while it ran here (none when publishing a build for another controller): what makes the publication its
        // owner's, checked as the snapshot goes into place and as the record is written
        RunningBuilds.Placeholder lease = PersistenceStores.get().leases() == null ? null : RunningBuilds.ownedPlaceholder(run);
        // uploaded under a name of its own first, which a controller that lost the build cannot put over the owner's
        String upload = lease == null ? SNAPSHOT : SNAPSHOT + "." + lease.getEpoch();
        // before the snapshot: a file added while it is made and uploaded then differs from this, and is published later
        String signature = signature(dir);
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            List<String> excludes = new ArrayList<>(DEFAULT_EXCLUDES);
            for (Exclusion exclusion : ExtensionList.lookup(Exclusion.class)) {
                excludes.addAll(exclusion.excludes(run));
            }
            Path snapshot = Files.createTempFile("build-snapshot", ".tgz");
            try {
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
                    new FilePath(dir).tar(out, new OwnFiles(new DirScanner.Glob("**", String.join(",", excludes), false, LinkOption.NOFOLLOW_LINKS)));
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException().initCause(e);
                }
                try (InputStream in = Files.newInputStream(snapshot)) {
                    blobs.put(base + "/" + upload, in, Files.size(snapshot));
                }
            } finally {
                Files.deleteIfExists(snapshot);
            }
            if (lease != null) {
                // the upload may take long; the lease is checked now, so only a moment remains for it to be lost in
                if (!PersistenceStores.get().leases().holds(lease.getKey(), lease.getEpoch())) {
                    blobs.delete(base + "/" + upload);
                    throw new LeaseLostException(lease.getKey(), lease.getEpoch());
                }
                blobs.move(base + "/" + upload, base + "/" + SNAPSHOT);
            }
        }
        if (isDeleted(base, run)) {
            discardSnapshot(base);
            LOGGER.fine(() -> "Not publishing " + run + ": deleted while being published");
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        try {
            byte[] published = Files.readAllBytes(record);
            PersistenceStore.Entry stored = filesChanged ? store.read(base + "/" + RECORD) : null;
            if (stored != null) {
                // only the files changed: mark the stored record (telling other controllers to fetch the snapshot again),
                // rather than writing this controller's copy over an edit made elsewhere since
                byte[] marked = (new String(stored.content(), StandardCharsets.UTF_8).replaceAll("\n<!-- files updated \\d+ -->$", "")
                        + "\n<!-- files updated " + System.currentTimeMillis() + " -->").getBytes(StandardCharsets.UTF_8);
                try {
                    store.write(base + "/" + RECORD, marked, stored.version());
                } catch (ConflictException e) {
                    LOGGER.fine(() -> run + " changed meanwhile; its files are published with the next sweep");
                    return;
                }
                recentlyPublished.put(base, new Published(new java.lang.ref.WeakReference<>(run), signature, System.currentTimeMillis()));
                LOGGER.fine(() -> "Published files added to " + run);
                return;
            }
            publishRecord(lease, base + "/" + RECORD, published);
            recentlyPublished.put(base, new Published(new java.lang.ref.WeakReference<>(run), signature, System.currentTimeMillis()));
            // a save made while publishing found no record yet, so republish left it to this: include it
            for (int i = 0; i < 10; i++) {
                byte[] now = Files.readAllBytes(record);
                if (Arrays.equals(now, published)) {
                    break;
                }
                publishRecord(lease, base + "/" + RECORD, now);
                published = now;
            }
        } catch (NoSuchFileException e) {
            if (!isDeleted(base, run)) {
                throw e;
            } // else its directory went with it: unpublished below
        }
        // checked once every write is done: deleted while we wrote it (a build discarder runs as soon as builds
        // complete), its deletion removed what was written before, and this removes what was written after
        if (isDeleted(base, run)) {
            PersistenceStores.get().delete(base + "/" + RECORD, PersistenceStore.ANY);
            discardSnapshot(base);
            LOGGER.fine(() -> "Unpublished " + run + ": deleted while being published");
            return;
        }
        LOGGER.fine(() -> "Published " + run);
    }

    /** Writes a build record, while holding the build's lease if it has one (the store checks it with the write). */
    private static void publishRecord(@CheckForNull RunningBuilds.Placeholder lease, String key, byte[] content) throws IOException {
        if (lease == null) {
            seen(key, PersistenceStores.get().write(key, content, PersistenceStore.ANY));
        } else {
            seen(key, PersistenceStores.get().writeIfHeld(lease.getKey(), lease.getEpoch(), key, content, PersistenceStore.ANY));
        }
    }

    /** A build published here recently, and the signature of its directory when it was. */
    private record Published(java.lang.ref.WeakReference<Run<?, ?>> run, String signature, long at) {}

    static final Map<String, Published> recentlyPublished = new java.util.concurrent.ConcurrentHashMap<>();

    /** How long after publishing a build files added to its directory are still published (Target 12). */
    static final long LATE_FILES_MILLIS = java.util.concurrent.TimeUnit.HOURS.toMillis(1);

    /** Paths, sizes and modification times of what a snapshot would hold. */
    static String signature(File dir) {
        StringBuilder b = new StringBuilder();
        Path root = dir.toPath();
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(f -> {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                if (rel.equals(RECORD) || rel.startsWith("archive/") || rel.startsWith("workflow/") || rel.startsWith("stashes/")
                        || rel.equals("program.dat") || rel.endsWith(".tmp")) {
                    return;
                }
                b.append(rel).append(' ').append(f.toFile().length()).append(' ').append(f.toFile().lastModified()).append('\n');
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            return "";
        }
        return b.toString();
    }

    /**
     * Republishes recently completed builds whose directories changed without their record being saved, as when a
     * plugin adds a file to a completed build: their snapshots would otherwise miss it on other controllers.
     */
    @Restricted(NoExternalUse.class)
    @Extension
    public static final class LateFiles extends hudson.model.AsyncPeriodicWork {
        public LateFiles() {
            super("Files added to completed builds");
        }

        @Override
        public long getRecurrencePeriod() {
            return java.util.concurrent.TimeUnit.SECONDS.toMillis(Long.getLong(BuildRecords.class.getName() + ".lateFilesPeriodSeconds", 60));
        }

        @Override
        protected void execute(hudson.model.TaskListener listener) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Published> e : recentlyPublished.entrySet()) {
                Published p = e.getValue();
                Run<?, ?> run = p.run().get();
                if (run == null || now - p.at() > LATE_FILES_MILLIS || isDeleted(e.getKey(), run)
                        || !new File(run.getRootDir(), RECORD).isFile()) { // e.g. its job was deleted
                    recentlyPublished.remove(e.getKey());
                    continue;
                }
                if (!signature(run.getRootDir()).equals(p.signature())) {
                    try {
                        LOGGER.fine(() -> "Republishing " + run + ": files were added to it after it was published");
                        publish(run, true);
                    } catch (IOException | RuntimeException x) {
                        LOGGER.log(Level.WARNING, "Could not republish " + run, x);
                    }
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    /**
     * Publishes a build which can no longer run anywhere (its controller died, nothing could resume it) as aborted, so
     * that it stays in the history with a console saying what happened, rather than vanishing (Target 12).
     */
    @Restricted(NoExternalUse.class)
    public static void publishLost(@NonNull Job<?, ?> job, int number, long startTime, @NonNull String reason) throws IOException {
        String builds = buildsKey(job);
        if (builds == null) {
            return;
        }
        String base = builds + "/" + number;
        PersistenceStore store = PersistenceStores.get();
        if (store.read(base + "/" + RECORD) != null) {
            return; // published meanwhile (e.g. it completed after all)
        }
        Class<?> buildClass = buildClassOf(job);
        if (buildClass == null) {
            LOGGER.warning(() -> "Cannot tell the build class of " + job + "; not closing #" + number);
            return;
        }
        String tag = Run.XSTREAM2.getMapper().serializedClass(buildClass);
        String record = "<?xml version='1.1' encoding='UTF-8'?>\n<" + tag + ">\n  <actions/>\n  <timestamp>" + startTime + "</timestamp>\n"
                + "  <startTime>" + startTime + "</startTime>\n  <result>ABORTED</result>\n  <duration>0</duration>\n"
                + "  <charset>UTF-8</charset>\n  <keepLog>false</keepLog>\n  <completed>true</completed>\n</" + tag + ">";
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            Path dir = Files.createTempDirectory("lost-build");
            Path snapshot = Files.createTempFile("lost-build", ".tgz");
            try {
                Files.writeString(dir.resolve("log"), reason + "\nFinished: ABORTED\n", StandardCharsets.UTF_8);
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
                    new FilePath(dir.toFile()).tar(out, new DirScanner.Glob("**", null, false));
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException().initCause(e);
                }
                try (InputStream in = Files.newInputStream(snapshot)) {
                    blobs.put(base + "/" + LOST_SNAPSHOT, in, Files.size(snapshot));
                }
            } finally {
                Files.deleteIfExists(snapshot);
                Util.deleteRecursive(dir.toFile());
            }
        }
        try {
            store.write(base + "/" + RECORD, record.getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
            LOGGER.info(() -> "Closed " + job.getFullName() + " #" + number + " as aborted: " + reason);
            writtenElsewhere(job, number); // this controller's own write raises no event here
        } catch (ConflictException e) {
            LOGGER.fine(() -> job.getFullName() + " #" + number + " was published meanwhile");
            if (blobs != null) {
                blobs.delete(base + "/" + LOST_SNAPSHOT); // its owner's real record and snapshot stand
            }
        }
    }

    @CheckForNull
    private static Class<?> buildClassOf(Job<?, ?> job) {
        for (Class<?> c = job.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getBuildClass");
                m.setAccessible(true);
                return (Class<?>) m.invoke(job);
            } catch (NoSuchMethodException e) {
                // look further up
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        if (job instanceof jenkins.model.lazy.LazyBuildMixIn.LazyLoadingJob<?, ?> lazy) {
            try {
                java.lang.reflect.Method m = jenkins.model.lazy.LazyBuildMixIn.class.getDeclaredMethod("getBuildClass");
                m.setAccessible(true);
                return (Class<?>) m.invoke(lazy.getLazyBuildMixIn());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Builds deleted here, by key, with when, so that a publication still in progress does not bring them back. Kept for
     * {@link #DELETED_MILLIS} rather than up to a count: however many builds are deleted meanwhile (a discarder sweeping
     * a big job), a publication stalled in its upload still finds its build's marker. A marker applies only to a build
     * that started before it: a job made again under the name (its next build number moved away with a rename) can
     * have a build of that number again, which is not the one deleted. The marker of a build being published is kept
     * however old, until a later deletion after the publication: one stalled for longer still finds it.
     */
    private static final LinkedHashMap<String, Long> deleted = new LinkedHashMap<>();

    /** Publications in progress, by build key: how many. Guarded by {@link #deleted}. */
    private static final Map<String, Integer> publishing = new HashMap<>();

    static /* non-final for tests */ long DELETED_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static boolean isDeleted(String base, Run<?, ?> run) {
        synchronized (deleted) {
            Long marked = deleted.get(base);
            return marked != null && marked >= run.getTimeInMillis();
        }
    }

    private static void markDeleted(String base) {
        long now = System.currentTimeMillis();
        synchronized (deleted) {
            deleted.remove(base);
            deleted.put(base, now);
            for (var it = deleted.entrySet().iterator(); it.hasNext(); ) {
                var marker = it.next();
                if (marker.getValue() >= now - DELETED_MILLIS) {
                    break; // in the order marked: the rest are newer
                }
                if (!publishing.containsKey(marker.getKey())) {
                    it.remove();
                }
            }
        }
    }

    private static void discardSnapshot(String base) throws IOException {
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            blobs.delete(base);
        }
    }

    /**
     * Republishes the record of a build already published, after it was modified (for example its description).
     */
    @Restricted(NoExternalUse.class)
    public static void republish(@NonNull Run<?, ?> run) throws IOException {
        String builds = buildsKey(run.getParent());
        if (builds == null) {
            return;
        }
        String key = builds + "/" + run.getNumber() + "/" + RECORD;
        PersistenceStore store = PersistenceStores.get();
        byte[] content = Files.readAllBytes(new File(run.getRootDir(), RECORD).toPath());
        for (int attempt = 1; ; attempt++) {
            PersistenceStore.Entry e = store.read(key);
            if (e == null) {
                return; // not published yet (publish will include this change), or deleted meanwhile: not brought back
            }
            try {
                // only over the record read: a build deleted elsewhere since must not be written again
                seen(key, store.write(key, content, e.version()));
                break;
            } catch (ConflictException x) {
                if (attempt == 5) {
                    throw x;
                }
                // changed or deleted since it was read: read it again
            }
        }
        LOGGER.fine(() -> "Republished " + run);
    }

    /**
     * Removes a build from the stores.
     */
    @Restricted(NoExternalUse.class)
    public static void delete(@NonNull Run<?, ?> run) throws IOException {
        String builds = buildsKey(run.getParent());
        if (builds == null) {
            return;
        }
        String base = builds + "/" + run.getNumber();
        markDeleted(base);
        PersistenceStores.get().delete(base + "/" + RECORD, PersistenceStore.ANY);
        seen(base + "/" + RECORD, PersistenceStore.ABSENT);
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            blobs.delete(base);
        }
        LOGGER.fine(() -> "Deleted stored " + run);
    }

    /**
     * Removes every build record, snapshot and running placeholder of a job, as when the job is deleted.
     * The job's {@code nextBuildNumber} is kept, so that a job recreated with the same name never reuses a number.
     * Should the job be made again under the same name while this runs, the snapshots of builds it publishes once the
     * records are deleted are kept; one it records in the moment before is deleted with the rest.
     */
    @Restricted(NoExternalUse.class)
    public static void deleteAll(@NonNull Job<?, ?> job) throws IOException {
        String builds = buildsKey(job);
        if (builds == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        Set<String> numbers = store.children(builds);
        for (String number : numbers) {
            store.delete(builds + "/" + number + "/" + RECORD, PersistenceStore.ANY);
        }
        String running = builds.substring(0, builds.length() - "builds".length()) + RunningBuilds.PREFIX;
        for (String name : store.children(running)) {
            store.delete(running + "/" + name, PersistenceStore.ANY);
        }
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            if (recreated(job)) {
                // made again meanwhile: only the snapshots of the records deleted above, not those of the new job's builds
                for (String number : numbers) {
                    blobs.delete(builds + "/" + number);
                }
            } else {
                blobs.delete(builds);
            }
        }
        seenRecords.remove(builds);
        RunningBuilds.invalidate(builds.substring(0, builds.length() - "/builds".length()));
        LOGGER.fine(() -> "Deleted stored builds of " + job.getFullName());
    }

    /**
     * Removes the build records, snapshots and running placeholders of a deleted job numbered below {@code below},
     * as {@link #deleteAll} does for all of them, when a job was made again under the same name meanwhile:
     * that job's builds are numbered from where the deleted one stopped, so they are kept.
     * Snapshots below the cutoff that have no record (a publication cut short, or an earlier cleanup that failed
     * partway) are left, as the blob store cannot be listed; they are never shown, as a build needs its record.
     */
    private static void deleteBelow(@NonNull Job<?, ?> job, int below) throws IOException {
        String builds = buildsKey(job);
        if (builds == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        for (String number : store.children(builds)) {
            if (numberOf(number) < below) {
                store.delete(builds + "/" + number + "/" + RECORD, PersistenceStore.ANY);
                seen(builds + "/" + number + "/" + RECORD, PersistenceStore.ABSENT);
                if (blobs != null) {
                    blobs.delete(builds + "/" + number);
                }
            }
        }
        String running = builds.substring(0, builds.length() - "builds".length()) + RunningBuilds.PREFIX;
        for (String name : store.children(running)) {
            if (numberOf(name) < below) {
                store.delete(running + "/" + name, PersistenceStore.ANY);
            }
        }
        RunningBuilds.invalidate(builds.substring(0, builds.length() - "/builds".length()));
        LOGGER.fine(() -> "Deleted stored builds of " + job.getFullName() + " below #" + below);
    }

    /**
     * Removes the build records of a deleted job that this controller saw, where still stored at the version it saw,
     * with their snapshots, when a job is at the same name again by the time this controller applies a deletion made
     * elsewhere. What is stored there now under a number the deleted job had too, a build of a job renamed to the name
     * (which keeps its numbers) or of a new job, has another version, and is kept; the controller which made the
     * deletion removed the rest. Running placeholders are removed below {@code below}, as {@link #deleteBelow} does.
     */
    private static void deleteSeen(@NonNull Job<?, ?> job, int below) throws IOException {
        String builds = buildsKey(job);
        if (builds == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        BlobStore blobs = PersistenceStores.getBlobStore();
        Map<Integer, Long> seen = seenRecords.getOrDefault(builds, Map.of());
        seenRecords.remove(builds);
        for (Map.Entry<Integer, Long> record : seen.entrySet()) {
            String key = builds + "/" + record.getKey() + "/" + RECORD;
            PersistenceStore.Entry now = store.read(key);
            if (now == null || now.version() != record.getValue()) {
                continue; // deleted (with its snapshot), or not what this controller saw: kept
            }
            try {
                store.delete(key, record.getValue());
            } catch (ConflictException e) {
                continue; // changed since: kept, with its snapshot
            }
            if (blobs != null) {
                blobs.delete(builds + "/" + record.getKey());
            }
        }
        String running = builds.substring(0, builds.length() - "builds".length()) + RunningBuilds.PREFIX;
        for (String name : store.children(running)) {
            if (numberOf(name) < below) {
                store.delete(running + "/" + name, PersistenceStore.ANY);
            }
        }
        RunningBuilds.invalidate(builds.substring(0, builds.length() - "/builds".length()));
        LOGGER.fine(() -> "Deleted stored builds of " + job.getFullName() + " seen here");
    }

    /**
     * The build number a stored name starts with ({@code 5}, {@code 5.xml}), or {@link Integer#MAX_VALUE} if none, or if
     * it is larger than any build number: never taken for a lower number, which a cleanup by number would delete.
     */
    static int numberOf(String name) {
        int end = 0;
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 18) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Long.parseLong(name.substring(0, end)), Integer.MAX_VALUE);
    }

    /**
     * Creates the local directory of a build which so far exists only in the stores.
     *
     * @param dir the build directory, named after the build number
     * @return true if the build was found in the store and {@code dir} now holds its {@code build.xml}
     */
    @Restricted(NoExternalUse.class)
    public static boolean materialize(@NonNull Job<?, ?> job, @NonNull File dir) throws IOException {
        String builds = buildsKey(job);
        if (builds == null) {
            return false;
        }
        String base = builds + "/" + dir.getName();
        PersistenceStore.Entry record = PersistenceStores.get().read(base + "/" + RECORD);
        if (record == null) {
            return false;
        }
        BlobStore blobs = PersistenceStores.getBlobStore();
        InputStream snapshot = null;
        if (blobs != null) {
            snapshot = blobs.get(base + "/" + SNAPSHOT);
            if (snapshot == null) {
                snapshot = blobs.get(base + "/" + LOST_SNAPSHOT); // closed as lost: only the stand-in
            }
            if (snapshot == null) {
                // a record is stored only once its snapshot is: the snapshot is moving (a rename in progress), so the
                // build is left unmaterialized rather than taken as complete without its files; it is retrieved again
                // once the move is applied. (A record stored before a blob store was configured would stay hidden.)
                LOGGER.fine(() -> "Snapshot of " + base + " not there (yet); not materialized");
                return false;
            }
        }
        Files.createDirectories(dir.toPath());
        if (snapshot != null) {
            try (InputStream in = snapshot) {
                new FilePath(dir).untarFrom(in, FilePath.TarCompression.GZIP);
                removeLinks(dir.toPath());
            } catch (InterruptedException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
        }
        // last, as on the publishing side: a local build.xml means the directory is complete
        writeRecord(dir, record.content());
        seen(base + "/" + RECORD, record.version());
        LOGGER.fine(() -> "Materialized " + job.getFullName() + " #" + dir.getName());
        return true;
    }

    private static void writeRecord(File dir, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(dir.toPath(), RECORD, ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, new File(dir, RECORD).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, new File(dir, RECORD).toPath(), StandardCopyOption.REPLACE_EXISTING); // as AtomicFileWriter does
        }
    }

    /**
     * Parsed key of a build record: {@code jobs/f/jobs/j/builds/5/build.xml} → job key {@code jobs/f/jobs/j}, number 5.
     */
    record RecordKey(String jobKey, int number) {
        @CheckForNull
        static RecordKey parse(String key) {
            String[] segments = key.split("/");
            int n = segments.length;
            if (n < 5 || !segments[n - 1].equals(RECORD) || !segments[n - 3].equals("builds")) {
                return null;
            }
            try {
                return new RecordKey(String.join("/", Arrays.copyOf(segments, n - 3)), Integer.parseInt(segments[n - 2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @CheckForNull
    private static RunMap<?> runsOf(Job<?, ?> job) {
        return job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy ? lazy.getLazyBuildMixIn()._getRuns() : null;
    }

    /**
     * Brings this controller up to date after another controller published or republished a build record.
     * A new build is only added to the index; it is materialized when first loaded.
     */
    static void writtenElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        String builds = buildsKey(job);
        if (runs == null || builds == null) {
            return;
        }
        File dir = new File(job.getBuildDir(), String.valueOf(number));
        // under the lock materialization holds (RunMap.retrieve): one in progress is waited for and then refreshed,
        // rather than taken for not there yet and left with the record it read before this change
        synchronized (runs) {
            if (new File(dir, RECORD).isFile()) {
                PersistenceStore.Entry record = PersistenceStores.get().read(builds + "/" + number + "/" + RECORD);
                if (record == null) {
                    return; // deleted since; the deletion event follows
                }
                LOGGER.fine(() -> "Refreshing " + job.getFullName() + " #" + number + " changed elsewhere");
                // its files may have changed too (a later snapshot): drop the local copy, materialised again when next needed
                runs.unload(number);
                Util.deleteRecursive(dir);
                Files.createDirectories(dir.toPath());
                runs.recognizeNumber(number);
            } else {
                LOGGER.fine(() -> "Recognizing " + job.getFullName() + " #" + number + " completed elsewhere");
                Files.createDirectories(dir.toPath());
                // a reference left unloadable (its materialization failed, or found no snapshot yet) is retried
                runs.unload(number);
                runs.recognizeNumber(number);
            }
        }
        PeepholePermalink.changedElsewhere(job, number, false);
    }

    /** Whether a record of the build is stored. */
    static boolean isStored(@NonNull Job<?, ?> job, int number) throws IOException {
        String builds = buildsKey(job);
        return builds != null && PersistenceStores.get().read(builds + "/" + number + "/" + RECORD) != null;
    }

    /**
     * A build's record was moved elsewhere along with its job, whose move this controller has applied: a copy
     * materialized here is refreshed, as for a change, since an edit made elsewhere before the move found no record
     * at the old name here. A build not materialized here is read from the store when needed, as it is now.
     */
    static void movedElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        if (new File(new File(job.getBuildDir(), String.valueOf(number)), RECORD).isFile()) {
            writtenElsewhere(job, number);
        }
    }

    /**
     * Brings this controller up to date after another controller deleted a build.
     * Listeners are not notified: cleanup of external data happened where the build was deleted.
     */
    static void deletedElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        String builds = buildsKey(job);
        if (runs == null || builds == null) {
            return;
        }
        Run<?, ?> loaded = runs.getLoadedBuilds().get(number);
        if (loaded != null && loaded.isBuilding()) {
            return; // running here, so not what was deleted elsewhere: a build of an earlier job of this name, say
        }
        if (PersistenceStores.get().read(builds + "/" + number + "/" + RECORD) != null) {
            // stored again since: the event is stale (the job was renamed here and this name reused, say), or the record
            // was published again as it was deleted, which is resolved by another deletion
            return;
        }
        LOGGER.fine(() -> "Forgetting " + job.getFullName() + " #" + number + " deleted elsewhere");
        forgetLocalCopy(job, number);
    }

    /** Drops this controller's copy of a build: the loaded object, and its directory. */
    static void forgetLocalCopy(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        if (runs == null) {
            return;
        }
        runs.forget(number);
        Util.deleteRecursive(new File(job.getBuildDir(), String.valueOf(number)));
        PeepholePermalink.changedElsewhere(job, number, true);
    }

    /**
     * Scans a build directory for its own files only: symbolic links, and anything outside the directory, are left
     * out, so that a snapshot never carries what a link points to elsewhere on this controller, nor a link to recreate
     * on another one.
     */
    static final class OwnFiles extends DirScanner {
        private static final long serialVersionUID = 1L;
        private final DirScanner scanner;

        OwnFiles(DirScanner scanner) {
            this.scanner = scanner;
        }

        @Override
        public void scan(File dir, FileVisitor visitor) throws IOException {
            Path root = dir.toPath().toRealPath();
            scanner.scan(dir, new FileVisitor() {
                @Override
                public void visit(File f, String relativePath) throws IOException {
                    Path path = f.toPath();
                    if (!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root)) {
                        visitor.visit(f, relativePath);
                    } else {
                        LOGGER.fine(() -> "Leaving " + f + " out of the build snapshot: a link, or outside " + root);
                    }
                }

                @Override
                public boolean understandsSymlink() {
                    return true;
                }

                @Override
                public void visitSymlink(File link, String target, String relativePath) {
                    LOGGER.fine(() -> "Leaving link " + link + " out of the build snapshot");
                }
            });
        }
    }

    /** Deletes any symbolic links below a directory just materialized: a build's files are never links. */
    static void removeLinks(Path dir) throws IOException {
        List<Path> links;
        try (Stream<Path> paths = Files.walk(dir)) {
            links = paths.filter(Files::isSymbolicLink).toList();
        }
        for (Path link : links) {
            LOGGER.warning(() -> "Removing link " + link + " from a materialized build snapshot");
            Files.delete(link);
        }
    }

    /**
     * Loads a build; saves made meanwhile (for example data migrations) are not republished.
     */
    @Restricted(NoExternalUse.class)
    public static <V, T extends Throwable> V loading(ThrowingCallable<V, T> body) throws T {
        Boolean old = LOADING.get();
        LOADING.set(Boolean.TRUE);
        try {
            return body.call();
        } finally {
            if (old == null) {
                LOADING.remove();
            }
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Publisher extends RunListener<Run<?, ?>> {

        @Override
        public void onFinalized(Run<?, ?> run) {
            if (PersistenceStores.isApplyingRemoteChange()) {
                return;
            }
            if (RunningBuilds.wasLost(run) || !RunningBuilds.owns(run)) {
                // another controller has taken it over and will publish it
                LOGGER.warning(() -> "Not publishing " + run + ": it is owned by another controller");
                RunningBuilds.lost(run); // fenced until discarded: a save of this copy meanwhile is not republished
                RunningBuilds.discard(run);
                return;
            }
            try {
                publish(run);
            } catch (LeaseLostException e) {
                // taken over while it was being published: the new owner publishes it; nothing of it is left from here
                LOGGER.warning(() -> "Not publishing " + run + ": " + e.getMessage());
                RunningBuilds.lost(run);
                RunningBuilds.discard(run);
                return;
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to publish " + run, e);
            }
            // after the record exists, so other controllers always see one or the other
            try {
                RunningBuilds.finished(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to remove running placeholder of " + run, e);
            }
        }

        @Override
        public void onDeleted(Run<?, ?> run) {
            if (PersistenceStores.isApplyingRemoteChange()) {
                return;
            }
            try {
                delete(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to delete stored " + run, e);
            }
        }
    }

    /** Whether a job deleted elsewhere has been made again since. */
    private static boolean recreated(Job<?, ?> job) throws IOException {
        String builds = buildsKey(job);
        return builds != null && PersistenceStores.get().read(builds.substring(0, builds.length() - "builds".length()) + "config.xml") != null;
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class ItemDeleted extends ItemListener {

        /**
         * The next build number of each job as its deletion starts: a job made again elsewhere meanwhile, under the same
         * name, may be loaded into the same object before the deletion is over, with the new job's number.
         */
        private final Map<Job<?, ?>, Integer> nextBuildNumbers = Collections.synchronizedMap(new WeakHashMap<>());

        @Override
        public void onCheckDelete(Item item) {
            if (item instanceof Job<?, ?> job) {
                // where the deletion is made, its configuration is still stored: so is its sequence, which other
                // controllers' builds advance (the field only this controller's). Where it is applied, the stored one
                // may be a new job's already
                nextBuildNumbers.put(job, PersistenceStores.isApplyingRemoteChange()
                        ? job.getNextBuildNumber() : Math.max(job.getNextBuildNumber(), storedNextBuildNumber(job)));
            }
        }

        /** The job's next build number as stored, or 0 if unknown. */
        private static int storedNextBuildNumber(Job<?, ?> job) {
            String builds = buildsKey(job);
            if (builds == null) {
                return 0;
            }
            try {
                PersistenceStore.Entry entry = PersistenceStores.get().read(builds.substring(0, builds.length() - "builds".length()) + "nextBuildNumber");
                return entry == null ? 0 : Integer.parseInt(new String(entry.content(), StandardCharsets.UTF_8).trim());
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not read the stored next build number of " + job.getFullName(), e);
                return 0;
            }
        }

        @Override
        public void onDeleted(Item item) {
            // folders delete their children one by one, so each job gets here
            if (!(item instanceof Job<?, ?> job)) {
                return;
            }
            Integer next = nextBuildNumbers.remove(job);
            try {
                if (recreated(job)) {
                    if (PersistenceStores.isApplyingRemoteChange()) {
                        // something is at the name again, made or renamed there after the controller which deleted the
                        // job removed its builds: what is left of the deleted job are records published late, from
                        // here (or seen from elsewhere), known by version. A job renamed there keeps its numbers, so
                        // a number tells nothing
                        deleteSeen(job, next != null ? next : job.getNextBuildNumber());
                    } else {
                        // made again elsewhere while this controller deleted the job's directory: renaming a job to the
                        // name fails while the deleted job's records are there, and the new job's builds are numbered
                        // from where this one stopped, so only those below are this job's
                        deleteBelow(job, next != null ? next : job.getNextBuildNumber());
                    }
                    return;
                }
                // also when the deletion was made elsewhere: a build that completed here before this controller
                // applied it published a record after that controller had cleaned up (idempotent)
                deleteAll(job);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to delete stored builds of " + job.getFullName(), e);
            }
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Republisher extends SaveableListener {

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Run<?, ?> stale && RunningBuilds.wasDiscarded(stale) && !PersistenceStores.isApplyingRemoteChange()) {
                // a copy of a build lost to another controller, discarded here but still referenced, saved its build.xml
                // again: never published, and dropped again, as it would stand for the new owner's record here
                RunningBuilds.discardAgain(stale);
                return;
            }
            if (!(o instanceof Run<?, ?> run) || run.isLogUpdated() || LOADING.get() != null || PersistenceStores.isApplyingRemoteChange()
                    || RunningBuilds.wasLost(run)) {
                return;
            }
            try {
                republish(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to republish " + run, e);
            }
        }
    }
}
