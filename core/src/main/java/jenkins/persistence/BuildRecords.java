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
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.RunMap;
import hudson.model.Saveable;
import hudson.model.listeners.RunListener;
import hudson.model.listeners.SaveableListener;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Never part of a snapshot: the record itself, artifacts (use an external {@link jenkins.model.ArtifactManager}),
     * and state only needed while a build runs.
     */
    static final List<String> DEFAULT_EXCLUDES = List.of(RECORD, "archive/**", "program.dat", "workflow/**", "stashes/**", "**/*.tmp");

    /** Set while this thread loads a build, so that saves made while loading are not republished. */
    private static final ThreadLocal<Boolean> LOADING = new ThreadLocal<>();

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
    @Restricted(NoExternalUse.class)
    public static boolean isShared(@NonNull Job<?, ?> job) {
        return buildsKey(job) != null;
    }

    /**
     * Numbers of the builds of a job recorded in the store.
     */
    @Restricted(NoExternalUse.class)
    @NonNull
    public static Set<Integer> storedNumbers(@NonNull Job<?, ?> job) {
        Set<Integer> numbers = new TreeSet<>();
        String builds = buildsKey(job);
        if (builds == null) {
            return numbers;
        }
        try {
            for (String name : PersistenceStores.get().children(builds)) {
                try {
                    numbers.add(Integer.parseInt(name));
                } catch (NumberFormatException e) {
                    // not a build
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list stored builds of " + job.getFullName(), e);
        }
        return numbers;
    }

    /**
     * Publishes a completed build.
     */
    @Restricted(NoExternalUse.class)
    public static void publish(@NonNull Run<?, ?> run) throws IOException {
        String builds = buildsKey(run.getParent());
        if (builds == null) {
            return;
        }
        String base = builds + "/" + run.getNumber();
        File dir = run.getRootDir();
        Path record = new File(dir, RECORD).toPath();
        if (!Files.isRegularFile(record)) {
            LOGGER.warning(() -> "Not publishing " + run + ": no " + record);
            return;
        }
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
                    blobs.put(base + "/" + SNAPSHOT, in, Files.size(snapshot));
                }
            } finally {
                Files.deleteIfExists(snapshot);
            }
        }
        byte[] published = Files.readAllBytes(record);
        PersistenceStores.get().write(base + "/" + RECORD, published, PersistenceStore.ANY);
        // a save made while publishing found no record yet, so republish left it to this: include it
        for (int i = 0; i < 10; i++) {
            byte[] now = Files.readAllBytes(record);
            if (Arrays.equals(now, published)) {
                break;
            }
            PersistenceStores.get().write(base + "/" + RECORD, now, PersistenceStore.ANY);
            published = now;
        }
        LOGGER.fine(() -> "Published " + run);
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
                store.write(key, content, e.version());
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
        PersistenceStores.get().delete(base + "/" + RECORD, PersistenceStore.ANY);
        BlobStore blobs = PersistenceStores.getBlobStore();
        if (blobs != null) {
            blobs.delete(base);
        }
        LOGGER.fine(() -> "Deleted stored " + run);
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
        if (new File(dir, RECORD).isFile()) {
            PersistenceStore.Entry record = PersistenceStores.get().read(builds + "/" + number + "/" + RECORD);
            if (record == null) {
                return; // deleted since; the deletion event follows
            }
            LOGGER.fine(() -> "Refreshing " + job.getFullName() + " #" + number + " changed elsewhere");
            writeRecord(dir, record.content());
            runs.unload(number);
        } else {
            LOGGER.fine(() -> "Recognizing " + job.getFullName() + " #" + number + " completed elsewhere");
            Files.createDirectories(dir.toPath());
            runs.recognizeNumber(number);
        }
        PeepholePermalink.changedElsewhere(job, number, false);
    }

    /**
     * Brings this controller up to date after another controller deleted a build.
     * Listeners are not notified: cleanup of external data happened where the build was deleted.
     */
    static void deletedElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        if (runs == null) {
            return;
        }
        LOGGER.fine(() -> "Forgetting " + job.getFullName() + " #" + number + " deleted elsewhere");
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
            try {
                publish(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to publish " + run, e);
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

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Republisher extends SaveableListener {

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (!(o instanceof Run<?, ?> run) || run.isLogUpdated() || LOADING.get() != null || PersistenceStores.isApplyingRemoteChange()) {
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
