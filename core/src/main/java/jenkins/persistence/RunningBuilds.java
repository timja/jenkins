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
import hudson.model.Action;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Makes builds running on one controller visible on the others, as placeholders, until their record is {@linkplain BuildRecords published}.
 *
 * <p>When a build starts, its controller writes {@code jobs/<item>/running/<N>.xml} (origin, controller name, start time, display name)
 * and deletes it once the build completes.
 * Other controllers show placeholders through {@link RunningElsewhereAction}.
 * If the store reports which origins are {@linkplain PersistenceStore#liveOrigins alive},
 * placeholders left behind by a controller which died are removed by whichever controller notices first.
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class RunningBuilds {

    private static final Logger LOGGER = Logger.getLogger(RunningBuilds.class.getName());

    static final String PREFIX = "running";
    private static final String SUFFIX = ".xml";
    private static final String STOP_SUFFIX = ".stop";

    /**
     * How this controller is named in placeholders; defaults to the host name.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ String CONTROLLER_NAME = SystemProperties.getString(RunningBuilds.class.getName() + ".controllerName", hostName());

    /** How often stale placeholders are looked for, in seconds. */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ int CLEANUP_PERIOD = SystemProperties.getInteger(RunningBuilds.class.getName() + ".cleanupPeriod", 30);

    /** Placeholders per job key, kept up to date by change events; loaded from the store on first use. */
    private static final Map<String, List<Placeholder>> cache = new ConcurrentHashMap<>();

    private RunningBuilds() {}

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * A build running on another controller.
     */
    @ExportedBean(defaultVisibility = 2)
    public static final class Placeholder {
        private final String key;
        private final int number;
        private final String origin;
        private final String controller;
        private final String displayName;
        private final long startTime;
        private final long epoch;
        private final Map<String, String> properties;
        private final long version;

        Placeholder(String key, int number, String origin, String controller, String displayName, long startTime,
                long epoch, Map<String, String> properties, long version) {
            this.key = key;
            this.number = number;
            this.origin = origin;
            this.controller = controller;
            this.displayName = displayName;
            this.startTime = startTime;
            this.epoch = epoch;
            this.properties = Map.copyOf(properties);
            this.version = version;
        }

        Placeholder withOwner(String origin, String controller, long epoch, Map<String, String> properties) {
            return new Placeholder(key, number, origin, controller, displayName, startTime, epoch, properties, version);
        }

        /** The lease epoch of the owner when it wrote this placeholder, or 0 if the store has no leases. */
        /** Its key in the store, which is also the key of its lease. */
        @NonNull
        public String getKey() {
            return key;
        }

        public long getEpoch() {
            return epoch;
        }

        /** Properties recorded by the owner through {@link RunningBuilds#update}, such as where its save points are. */
        @NonNull
        public Map<String, String> getProperties() {
            return properties;
        }

        @Exported
        public int getNumber() {
            return number;
        }

        /** The {@link PersistenceStore#getOrigin} of the controller running the build. */
        @Exported
        public String getOrigin() {
            return origin;
        }

        /** The name of the controller running the build. */
        @Exported
        public String getController() {
            return controller;
        }

        @Exported
        public String getDisplayName() {
            return displayName;
        }

        /** When the build started, in milliseconds since the epoch. */
        @Exported
        public long getStartTime() {
            return startTime;
        }

        /** {@link #getStartTime} as a date, for views. */
        public Date getStartDate() {
            return new Date(startTime);
        }

        byte[] toBytes() throws IOException {
            Properties p = new Properties();
            p.setProperty("origin", origin);
            p.setProperty("controller", controller);
            p.setProperty("displayName", displayName);
            p.setProperty("startTime", Long.toString(startTime));
            p.setProperty("epoch", Long.toString(epoch));
            properties.forEach((k, v) -> p.setProperty("x." + k, v));
            StringWriter w = new StringWriter();
            p.store(w, null);
            return w.toString().getBytes(StandardCharsets.UTF_8);
        }

        @CheckForNull
        static Placeholder fromBytes(String key, int number, byte[] content, long version) {
            Properties p = new Properties();
            try {
                p.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
                Map<String, String> extra = new TreeMap<>();
                for (String name : p.stringPropertyNames()) {
                    if (name.startsWith("x.")) {
                        extra.put(name.substring(2), p.getProperty(name));
                    }
                }
                return new Placeholder(key, number, p.getProperty("origin", ""), p.getProperty("controller", "?"),
                        p.getProperty("displayName", "#" + number), Long.parseLong(p.getProperty("startTime", "0")),
                        Long.parseLong(p.getProperty("epoch", "0")), extra, version);
            } catch (IOException | NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Malformed placeholder " + key, e);
                return null;
            }
        }
    }

    /**
     * The key below which placeholders of a job live, or null if builds of the job are not shared.
     */
    @CheckForNull
    private static String runningKey(@NonNull Job<?, ?> job) {
        String builds = BuildRecords.buildsKey(job);
        return builds == null ? null : builds.substring(0, builds.length() - "builds".length()) + PREFIX;
    }

    /**
     * The job key of a placeholder key: {@code jobs/f/jobs/j/running/5.xml} → {@code jobs/f/jobs/j}.
     *
     * @return null if the key is not a placeholder
     */
    @CheckForNull
    static String jobKeyOf(@NonNull String key) {
        String[] segments = key.split("/");
        int n = segments.length;
        if (n < 4 || !segments[n - 2].equals(PREFIX) || !segments[n - 1].matches("[0-9]+\\Q" + SUFFIX + "\\E")) {
            return null;
        }
        return String.join("/", Arrays.copyOf(segments, n - 2));
    }

    /**
     * Forgets cached placeholders of a job after a change event.
     */
    static void invalidate(@NonNull String jobKey) {
        cache.remove(jobKey);
    }

    /**
     * Forgets cached placeholders of the job at a key and of any job below it, as after the item is moved here
     * (this controller does not apply its own move events).
     */
    static void invalidateBelow(@NonNull String key) {
        cache.keySet().removeIf(k -> k.equals(key) || k.startsWith(key + "/"));
    }

    @NonNull
    private static List<Placeholder> load(@NonNull PersistenceStore store, @NonNull String runningKey) {
        List<Placeholder> placeholders = new ArrayList<>();
        try {
            for (String name : store.children(runningKey)) {
                if (!name.endsWith(SUFFIX)) {
                    continue;
                }
                int number;
                try {
                    number = Integer.parseInt(name.substring(0, name.length() - SUFFIX.length()));
                } catch (NumberFormatException e) {
                    continue;
                }
                String key = runningKey + "/" + name;
                PersistenceStore.Entry entry = store.read(key);
                Placeholder p = entry == null ? null : Placeholder.fromBytes(key, number, entry.content(), entry.version());
                if (p != null) {
                    placeholders.add(p);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list running builds below " + runningKey, e);
        }
        placeholders.sort(Comparator.comparingInt(Placeholder::getNumber).reversed());
        return List.copyOf(placeholders);
    }

    @NonNull
    private static List<Placeholder> all(@NonNull Job<?, ?> job) {
        PersistenceStore store = PersistenceStores.get();
        String runningKey = runningKey(job);
        if (store == null || runningKey == null) {
            return List.of();
        }
        String jobKey = runningKey.substring(0, runningKey.length() - PREFIX.length() - 1);
        return cache.computeIfAbsent(jobKey, k -> load(store, runningKey));
    }

    /**
     * Builds of a job running on other controllers, newest first,
     * excluding any whose completed record this controller already knows.
     */
    @NonNull
    public static List<Placeholder> elsewhere(@NonNull Job<?, ?> job) {
        return elsewhere(job, all(job));
    }

    /** Jobs whose placeholders are being loaded for {@link #elsewhereCached}, by job key. */
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();

    /**
     * {@link #elsewhere}, without asking the store, for callers under the queue lock: from what is cached, or null if
     * nothing is. The job's placeholders are then loaded off the lock, and the queue looked at again.
     */
    @CheckForNull
    static List<Placeholder> elsewhereCached(@NonNull Job<?, ?> job) {
        String runningKey = runningKey(job);
        if (PersistenceStores.get() == null || runningKey == null) {
            return List.of();
        }
        String jobKey = jobKeyOfRunning(runningKey);
        List<Placeholder> cached = cache.get(jobKey);
        if (cached != null) {
            return elsewhere(job, cached);
        }
        if (loading.add(jobKey)) {
            Timer.get().submit(() -> {
                try {
                    all(job);
                } finally {
                    loading.remove(jobKey);
                }
                if (cache.containsKey(jobKey)) { // else not loaded (the store is unreachable): asked again at the next maintenance
                    Queue.getInstance().scheduleMaintenance();
                }
            });
        }
        return null;
    }

    @NonNull
    private static List<Placeholder> elsewhere(@NonNull Job<?, ?> job, @NonNull List<Placeholder> all) {
        PersistenceStore store = PersistenceStores.get();
        List<Placeholder> result = new ArrayList<>();
        for (Placeholder p : all) {
            if (store != null && p.origin.equals(store.getOrigin())) {
                continue; // running here, so a real build
            }
            if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy && lazy.getLazyBuildMixIn()._getRuns().knows(p.number)) {
                continue; // completed meanwhile
            }
            result.add(p);
        }
        return result;
    }

    /** Builds owned by this controller: placeholder key → the placeholder as last written here. */
    private static final Map<String, Placeholder> owned = new ConcurrentHashMap<>();
    /** Builds this controller has lost to another one: placeholder keys. */
    private static final Set<String> lost = ConcurrentHashMap.newKeySet();
    /**
     * Copies of builds lost to another controller and discarded here, by identity: one still referenced (by a plugin,
     * or a Pipeline's execution) may be saved after its key is no longer {@link #lost}, and must never be published.
     */
    private static final Set<Run<?, ?>> discarded = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @CheckForNull
    private static String placeholderKey(@NonNull Run<?, ?> run) {
        String runningKey = runningKey(run.getParent());
        return runningKey == null ? null : runningKey + "/" + run.getNumber() + SUFFIX;
    }

    private static String jobKeyOfRunning(String runningKey) {
        return runningKey.substring(0, runningKey.length() - PREFIX.length() - 1);
    }

    static void started(@NonNull Run<?, ?> run) throws IOException {
        String key = placeholderKey(run);
        if (key == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        BuildLeases leases = store.leases();
        long epoch = 0;
        if (leases != null) {
            epoch = leases.acquire(key);
            if (epoch == 0) {
                // another live controller owns this very build: it must not run here too
                LOGGER.warning(() -> "Another controller holds the lease on " + key + "; stopping " + run + " here");
                lost(run);
                return;
            }
        }
        Placeholder p = new Placeholder(key, run.getNumber(), store.getOrigin(), CONTROLLER_NAME, run.getDisplayName(), run.getStartTimeInMillis(),
                epoch, Map.of(), PersistenceStore.ABSENT);
        long version = store.write(key, p.toBytes(), PersistenceStore.ANY);
        owned.put(key, new Placeholder(key, p.number, p.origin, p.controller, p.displayName, p.startTime, epoch, Map.of(), version));
        String jobKey = jobKeyOfRunning(runningKey(run.getParent()));
        invalidate(jobKey);
        // a stop requested between the placeholder's write and its registration above was not taken as ours
        if (store.read(jobKey + "/" + PREFIX + "/" + run.getNumber() + STOP_SUFFIX) != null) {
            stopRequested(jobKey, run.getNumber());
        }
    }

    /**
     * Whether this controller may still work on and publish a build it started or adopted.
     * False once another controller has taken it over (for example after this one was partitioned).
     */
    public static boolean owns(@NonNull Run<?, ?> run) {
        String key = placeholderKey(run);
        if (key == null) {
            return true;
        }
        if (lost.contains(key)) {
            return false;
        }
        Placeholder p = owned.get(key);
        BuildLeases leases = PersistenceStores.get().leases();
        if (p == null || leases == null) {
            return true; // not tracked (e.g. started before leases were available)
        }
        try {
            return leases.holds(key, p.epoch);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot check the lease on " + key + "; assuming it is still held", e);
            return true;
        }
    }

    /**
     * Records properties of a running build in its placeholder, for example where its latest save point is.
     * Conditional on this controller still owning the build: the write is a compare-and-set on the placeholder
     * as this controller last wrote it, so it fails if another controller has adopted the build since.
     *
     * @throws ConflictException if the build is no longer owned here; the caller should stop working on it
     *     (see {@link #lost})
     */
    public static void update(@NonNull Run<?, ?> run, @NonNull Map<String, String> properties) throws IOException {
        String key = placeholderKey(run);
        if (key != null && lost.contains(key)) {
            throw new ConflictException(key, PersistenceStore.ANY, PersistenceStore.ANY); // no longer owned here (lost forgets it)
        }
        Placeholder p = key == null ? null : owned.get(key);
        if (p == null) {
            return;
        }
        if (lost.contains(key) || !owns(run)) {
            throw new ConflictException(key, p.version, PersistenceStore.ANY);
        }
        Placeholder next = p.withOwner(p.origin, p.controller, p.epoch, properties);
        long version;
        try {
            version = PersistenceStores.get().writeIfHeld(key, p.epoch, key, next.toBytes(), p.version);
        } catch (LeaseLostException e) {
            throw new ConflictException(key, p.version, PersistenceStore.ANY); // taken over since it was checked
        }
        owned.put(key, new Placeholder(key, p.number, p.origin, p.controller, p.displayName, p.startTime, p.epoch, properties, version));
    }

    /**
     * Stops this controller working on a build which another controller has taken over:
     * interrupts it, and makes sure it is neither published nor its placeholder removed from here.
     * Its local copy is discarded once it has stopped.
     */
    public static void lost(@NonNull Run<?, ?> run) {
        String key = placeholderKey(run);
        if (key == null || !lost.add(key)) {
            return;
        }
        owned.remove(key);
        LOGGER.warning(() -> "Ownership of " + run + " was lost to another controller; stopping it here");
        Executor executor = run.getExecutor();
        if (executor != null && run.isLogUpdated()) { // not once completed: only fenced then
            executor.interrupt(Result.ABORTED, new OwnershipLost());
        }
    }

    /** Cause of interruption of a build taken over by another controller. */
    public static final class OwnershipLost extends CauseOfInterruption {
        @Override
        public String getShortDescription() {
            return "Another controller took over this build";
        }
    }

    /**
     * Removes the placeholder of a build which completed here; called once its record is published.
     */
    static void finished(@NonNull Run<?, ?> run) throws IOException {
        String key = placeholderKey(run);
        if (key == null) {
            return;
        }
        Placeholder p = owned.remove(key);
        PersistenceStore store = PersistenceStores.get();
        store.delete(key, p == null ? PersistenceStore.ANY : p.version);
        store.delete(key.substring(0, key.length() - SUFFIX.length()) + STOP_SUFFIX, PersistenceStore.ANY);
        BuildLeases leases = store.leases();
        if (leases != null && p != null) {
            leases.release(key, p.epoch);
        }
        invalidate(jobKeyOfRunning(runningKey(run.getParent())));
    }

    /**
     * Whether a build was lost to another controller, so it must not be published from here.
     */
    static boolean wasLost(@NonNull Run<?, ?> run) {
        String key = placeholderKey(run);
        return key != null && lost.contains(key);
    }

    /**
     * Discards the local copy of a build lost to another controller, once it has stopped here.
     * The other controller publishes it when it completes, and this controller then picks up that record.
     */
    static void discard(@NonNull Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        int number = run.getNumber();
        String key = placeholderKey(run);
        discard(job, number, key, run, 0);
    }

    /**
     * Whether this is a copy of a build discarded here, lost to another controller: a save of it is not published, and
     * the local copy it wrote is discarded again ({@link #discardAgain}).
     */
    static boolean wasDiscarded(@NonNull Run<?, ?> run) {
        return discarded.contains(run);
    }

    /**
     * Discards again the local copy of a build which a {@linkplain #wasDiscarded discarded} copy saved: its
     * {@code build.xml} would otherwise stand for the new owner's record here.
     */
    static void discardAgain(@NonNull Run<?, ?> run) {
        LOGGER.fine(() -> "A discarded copy of " + run + " was saved; discarding the local copy again");
        discard(run.getParent(), run.getNumber(), null, run, 0);
    }

    private static void discard(Job<?, ?> job, int number, String key, Run<?, ?> run, int attempt) {
        Timer.get().schedule(() -> {
            try {
                PersistenceStores.applyingRemoteChange(() -> {
                    // whatever is stored now: the record there is its new owner's, not this copy
                    BuildRecords.forgetLocalCopy(job, number);
                    if (BuildRecords.isStored(job, number)) {
                        // its new owner published it already, and this controller applied that to the copy just discarded
                        BuildRecords.writtenElsewhere(job, number);
                    }
                    return null;
                });
                discarded.add(run); // before its key is no longer lost: a save of this copy is never published
                if (key != null) {
                    lost.remove(key);
                }
            } catch (IOException | RuntimeException e) {
                // kept fenced (lost) until discarded: a later save of the local copy must not be published
                LOGGER.log(Level.WARNING, "Failed to discard the local copy of " + job.getFullName() + " #" + number + "; retrying", e);
                if (attempt < MAX_DISCARD_ATTEMPTS) {
                    discard(job, number, key, run, attempt + 1);
                }
            }
        }, DISCARD_DELAY_SECONDS * (1L << Math.min(attempt, 6)), TimeUnit.SECONDS);
    }

    private static final int MAX_DISCARD_ATTEMPTS = 10;

    /** Lets a stopped build finish writing locally before its copy is discarded. */
    private static final int DISCARD_DELAY_SECONDS = 5;

    /**
     * Checks that this controller still holds the leases of the builds it runs, and stops those it does not.
     */
    @Restricted(NoExternalUse.class)
    public static void checkOwnership() {
        for (String key : List.copyOf(owned.keySet())) {
            String jobKey = jobKeyOf(key);
            List<String> names = jobKey == null ? null : ItemChangeApplier.itemPath(jobKey + "/config.xml");
            Job<?, ?> job = names == null ? null : Jenkins.get().getItemByFullName(String.join("/", names), Job.class);
            Run<?, ?> run = job == null ? null : job.getBuildByNumber(Integer.parseInt(key.substring(key.lastIndexOf('/') + 1, key.length() - SUFFIX.length())));
            if (run == null || !run.isLogUpdated()) {
                continue;
            }
            if (!owns(run)) {
                lost(run);
            }
        }
    }

    /**
     * Removes placeholders whose controller is no longer alive.
     *
     * @return the number removed
     */
    @Restricted(NoExternalUse.class)
    public static int removeStale() throws IOException {
        PersistenceStore store = PersistenceStores.get();
        Set<String> live = store == null ? null : store.liveOrigins();
        if (live == null) {
            return 0;
        }
        int removed = 0;
        BuildLeases leases = store.leases();
        for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
            // per job: a sweep of many jobs takes a while, during which an origin found gone may come back
            Set<String> gone = new HashSet<>();
            int removedHere = 0;
            for (Placeholder p : all(job)) {
                if (!live.contains(p.origin) && stillGone(store, p.origin, gone)) {
                    long epoch = 0;
                    if (leases != null) {
                        epoch = leases.acquire(p.key);
                        if (epoch == 0) {
                            continue; // another controller is adopting it
                        }
                        boolean adopted;
                        try {
                            adopted = adopt(store, job, p.key, epoch);
                        } catch (IOException | RuntimeException e) {
                            // its placeholder is kept, for the next sweep to try again; the other builds are still looked at
                            LOGGER.log(Level.WARNING, "Could not adopt " + job.getFullName() + " #" + p.number + "; will retry", e);
                            leases.release(p.key, epoch);
                            continue;
                        }
                        if (adopted) {
                            removedHere++;
                            continue;
                        }
                    }
                    // as stored now, and only while this controller still holds its lease: one that lapsed while the
                    // adoption was prepared (a long pause) may have let another controller adopt the build, writing it
                    PersistenceStore.Entry now = store.read(p.key);
                    if (now != null && (leases == null || leases.holds(p.key, epoch))) {
                        LOGGER.info(() -> "Removing placeholder of " + job.getFullName() + " #" + p.number + " left by " + p.controller + " (" + p.origin + "), which is gone");
                        try {
                            // only as read: several controllers noticing at once is harmless, and a write since is kept
                            store.delete(p.key, now.version());
                            removedHere++;
                        } catch (ConflictException e) {
                            LOGGER.fine(() -> p.key + " changed meanwhile; left alone");
                        }
                    } else {
                        LOGGER.fine(() -> p.key + " completed, or was taken over, meanwhile; left alone");
                    }
                    if (leases != null) {
                        leases.release(p.key, epoch);
                    }
                }
            }
            removed += removedHere;
            if (removedHere > 0) {
                invalidate(PersistenceStores.keyFor(job.getRootDir()));
            }
        }
        return removed;
    }

    /**
     * Whether the origin of a placeholder missing from the sweep's first answer is still gone when asked after the
     * placeholder was listed: its controller may have joined since (a restart, during a rolling one) and written it.
     * An origin found gone then is taken as gone for the rest of the job's placeholders, so that the store is asked once
     * per origin and job.
     */
    private static boolean stillGone(PersistenceStore store, String origin, Set<String> gone) throws IOException {
        if (gone.contains(origin)) {
            return true;
        }
        Set<String> live = store.liveOrigins();
        if (live == null || live.contains(origin)) {
            return false;
        }
        gone.add(origin);
        return true;
    }

    /**
     * Parsed key of a stop request: {@code jobs/f/jobs/j/running/5.stop} → job key {@code jobs/f/jobs/j}, number 5.
     */
    record StopKey(String jobKey, int number) {
        @CheckForNull
        static StopKey parse(String key) {
            String[] segments = key.split("/");
            int n = segments.length;
            if (n < 4 || !segments[n - 2].equals(PREFIX) || !segments[n - 1].matches("[0-9]+\\Q" + STOP_SUFFIX + "\\E")) {
                return null;
            }
            String file = segments[n - 1];
            return new StopKey(String.join("/", Arrays.copyOf(segments, n - 2)), Integer.parseInt(file.substring(0, file.length() - STOP_SUFFIX.length())));
        }
    }

    /**
     * Asks whichever controller runs a build to stop it.
     * If it runs here, it is stopped right away; otherwise its owner (the holder of its lease, including one that adopts it later)
     * stops it when it sees the request.
     *
     * @param by who asked, for the build log
     */
    public static void requestStop(@NonNull Job<?, ?> job, int number, @NonNull String by) throws IOException {
        String runningKey = runningKey(job);
        if (runningKey == null) {
            return;
        }
        String key = runningKey + "/" + number + SUFFIX;
        if (owned.containsKey(key)) {
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run != null && run.isLogUpdated()) {
                interrupt(run, new StoppedElsewhere(CONTROLLER_NAME, by));
                return;
            }
        }
        PersistenceStore store = PersistenceStores.get();
        if (store.read(key) == null) {
            return; // no such build runs: a request left behind would stop a later build given that number
        }
        Properties request = new Properties();
        request.setProperty("controller", CONTROLLER_NAME);
        request.setProperty("by", by);
        StringWriter w = new StringWriter();
        request.store(w, null); // escaped, as it is read (a user name may hold a backslash)
        store.write(runningKey + "/" + number + STOP_SUFFIX, w.toString().getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
    }

    /** How many times a stop request is retried while the build it is for has no executor yet (being resumed). */
    private static final int STOP_ATTEMPTS = 8;

    /**
     * Stops a build owned here for which another controller requested a stop.
     */
    static void stopRequested(@NonNull String jobKey, int number) throws IOException {
        stopRequested(jobKey, number, 0);
    }

    private static void stopRequested(String jobKey, int number, int attempt) throws IOException {
        String placeholder = jobKey + "/" + PREFIX + "/" + number + SUFFIX;
        if (!owned.containsKey(placeholder)) {
            return; // not ours: its owner handles it
        }
        String stopKey = jobKey + "/" + PREFIX + "/" + number + STOP_SUFFIX;
        PersistenceStore store = PersistenceStores.get();
        PersistenceStore.Entry request = store.read(stopKey);
        if (request == null) {
            return;
        }
        Properties p = new Properties();
        p.load(new StringReader(new String(request.content(), StandardCharsets.UTF_8)));
        List<String> names = ItemChangeApplier.itemPath(jobKey + "/config.xml");
        Job<?, ?> job = names == null ? null : Jenkins.get().getItemByFullName(String.join("/", names), Job.class);
        Run<?, ?> run = job == null ? null : job.getBuildByNumber(number);
        if (run == null || run.isLogUpdated() && !interrupt(run, new StoppedElsewhere(p.getProperty("controller", "?"), p.getProperty("by", "?")))) {
            // adopted, but not loaded or given an executor yet: the request is kept (its build's completion removes it)
            if (attempt < STOP_ATTEMPTS) {
                Timer.get().schedule(() -> {
                    try {
                        stopRequested(jobKey, number, attempt + 1);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Failed to stop " + jobKey + " #" + number + " as requested", e);
                    }
                }, Math.min(30, 1L << attempt), TimeUnit.SECONDS);
            } else {
                LOGGER.warning(() -> "Could not stop " + jobKey + " #" + number + " as requested: it has no executor");
            }
            return;
        }
        store.delete(stopKey, PersistenceStore.ANY);
    }

    /** @return false if the build has no executor (yet) to interrupt */
    private static boolean interrupt(Run<?, ?> run, CauseOfInterruption cause) {
        Executor executor = run.getExecutor();
        if (executor == null) {
            return false;
        }
        LOGGER.info(() -> "Stopping " + run + ": " + cause.getShortDescription());
        executor.interrupt(Result.ABORTED, cause);
        return true;
    }

    /** Cause of interruption of a build stopped from another controller. */
    public static final class StoppedElsewhere extends CauseOfInterruption {
        private final String controller;
        private final String by;

        StoppedElsewhere(String controller, String by) {
            this.controller = controller;
            this.by = by;
        }

        @Override
        public String getShortDescription() {
            return "Stopped from " + controller + " by " + by;
        }
    }

    /** How many times adoption is retried when the placeholder changes under it. */
    private static final int ADOPTION_ATTEMPTS = 5;

    /**
     * Offers a build whose owner is gone to the {@link BuildAdopter}s, with its lease already held.
     *
     * @return true if it was adopted
     */
    private static boolean adopt(PersistenceStore store, Job<?, ?> job, String key, long epoch) throws IOException {
        try {
            return offer(store, job, key, epoch);
        } finally {
            if (!owned.containsKey(key)) {
                // not resumed here (gone, left for later, nobody could, or failing): the job's exclusive lease is not
                // kept for it, if it was taken
                SharedQueue.abandonExclusive(job, Integer.parseInt(key.substring(key.lastIndexOf('/') + 1, key.length() - SUFFIX.length())));
            }
        }
    }

    private static boolean offer(PersistenceStore store, Job<?, ?> job, String key, long epoch) throws IOException {
        int number = Integer.parseInt(key.substring(key.lastIndexOf('/') + 1, key.length() - SUFFIX.length()));
        for (int attempt = 0; attempt < ADOPTION_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            if (entry == null) {
                // completed meanwhile: nothing to adopt, nor to keep the lease for
                BuildLeases leases = store.leases();
                if (leases != null) {
                    leases.release(key, epoch);
                }
                return true;
            }
            if (BuildRecords.isStored(job, number)) {
                // completed: its record is published only once it has, and its owner was gone before removing this
                return false;
            }
            Run<?, ?> local = BuildRecords.loadedCopy(job, number);
            if (local != null && local.isLogUpdated() && !local.hasntStartedYet()) {
                // a copy still running here (lost to the owner now gone, and not stopped yet): not adopted over it, which
                // would leave two; the next sweep tries again
                throw new IOException(job.getFullName() + " #" + number + " still runs here; adopting it once it has stopped");
            } else if (local != null && !local.hasntStartedYet()) {
                BuildRecords.forgetLocalCopy(job, number); // a stale copy (lost here before): the adopted build replaces it
            }
            Placeholder p = Placeholder.fromBytes(key, number, entry.content(), entry.version());
            BuildAdopter.Adoption adoption = p == null ? null : prepare(job, p);
            if (adoption == null) {
                return false; // nobody can adopt it
            }
            boolean exclusive;
            try {
                exclusive = SharedQueue.adoptExclusive(job, number);
            } catch (IOException | RuntimeException e) {
                adoption.abandon(); // retried by the next sweep, which prepares it again
                throw e;
            }
            if (!exclusive) {
                // a newer build of this job (which allows one at a time) runs elsewhere, or here: resume this one later
                adoption.abandon();
                BuildLeases leases = store.leases();
                if (leases != null) {
                    leases.release(key, epoch);
                }
                LOGGER.info(() -> "Not adopting " + job.getFullName() + " #" + number + " yet: another build of it runs");
                return true; // nothing to remove: a later sweep adopts it
            }
            Placeholder mine = p.withOwner(store.getOrigin(), CONTROLLER_NAME, epoch, p.properties);
            long version;
            try {
                // fenced by the lease: it may have lapsed while the adoption was prepared (a save point fetched), and
                // another controller taken the build over meanwhile
                version = store.writeIfHeld(key, epoch, key, mine.toBytes(), p.version);
            } catch (LeaseLostException e) {
                adoption.abandon();
                throw e;
            } catch (ConflictException e) {
                // the previous owner recorded a newer save point while it was being fetched
                adoption.abandon();
                LOGGER.fine(() -> key + " changed while preparing its adoption; retrying");
                continue;
            } catch (IOException | RuntimeException e) {
                adoption.abandon(); // retried by the next sweep, which prepares it again
                throw e;
            }
            owned.put(key, new Placeholder(key, number, mine.origin, mine.controller, p.displayName, p.startTime, epoch, p.properties, version));
            invalidate(jobKeyOfRunning(runningKey(job)));
            LOGGER.info(() -> "Adopting " + job.getFullName() + " #" + number + " from " + p.controller + " (" + p.origin + "), which is gone");
            try {
                adoption.resume(epoch);
            } catch (IOException | RuntimeException e) {
                resumeFailed(store, job, p, version, e);
            }
            String jobKey = jobKeyOfRunning(runningKey(job));
            if (store.read(jobKey + "/" + PREFIX + "/" + number + STOP_SUFFIX) != null) {
                stopRequested(jobKey, number); // asked to stop while its owner was gone
            }
            return true;
        }
        return false;
    }

    /**
     * Undoes the adoption of a build whose resumption failed before anything of it was loaded here: its placeholder
     * names its previous owner again, which is gone, so that a later sweep tries again (or closes it), rather than
     * leaving it to this live controller, which does not run it. The caller releases the lease.
     */
    private static void resumeFailed(PersistenceStore store, Job<?, ?> job, Placeholder previous, long version, Exception e)
            throws IOException {
        if (job.getBuildByNumber(previous.number) != null) {
            // partly resumed: it is here now, so this controller keeps it (and fences or completes it as any other)
            LOGGER.log(Level.WARNING, "Resuming " + previous.key + " failed after it was loaded; keeping it here", e);
            return;
        }
        owned.remove(previous.key);
        try {
            store.write(previous.key, previous.toBytes(), version);
        } catch (ConflictException x) {
            e.addSuppressed(x); // changed since: by its next owner
        }
        invalidate(jobKeyOfRunning(runningKey(job)));
        throw new IOException("Could not resume " + previous.key, e);
    }

    /**
     * @return an adoption, or null if no adopter can take the build
     * @throws IOException if none could and one failed (perhaps for now only, as a save point not fetched): the build
     *     is then not taken for one nobody can adopt
     */
    @CheckForNull
    private static BuildAdopter.Adoption prepare(Job<?, ?> job, Placeholder p) throws IOException {
        Exception failure = null;
        for (BuildAdopter adopter : ExtensionList.lookup(BuildAdopter.class)) {
            try {
                BuildAdopter.Adoption adoption = adopter.prepare(job, p);
                if (adoption != null) {
                    return adoption;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, adopter + " failed to prepare adoption of " + p.key, e);
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
        return null;
    }

    /**
     * The placeholder of a build as this controller, its owner, last wrote it, or null if it is not owned here.
     */
    @CheckForNull
    public static Placeholder ownedPlaceholder(@NonNull Run<?, ?> run) {
        String key = placeholderKey(run);
        return key == null ? null : owned.get(key);
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Listener extends RunListener<Run<?, ?>> {

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            if (PersistenceStores.isApplyingRemoteChange()) {
                return;
            }
            try {
                started(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to record " + run + " as running", e);
            }
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class StaleCleanup extends AsyncPeriodicWork {

        public StaleCleanup() {
            super("Stale running build placeholders");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(CLEANUP_PERIOD);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            if (PersistenceStores.get() != null) {
                checkOwnership();
                removeStale();
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    /**
     * Lists the builds of a job running on other controllers, on the job page and in its REST API.
     */
    @ExportedBean
    public static final class RunningElsewhereAction implements Action {
        private final Job<?, ?> job;

        RunningElsewhereAction(Job<?, ?> job) {
            this.job = job;
        }

        public Job<?, ?> getJob() {
            return job;
        }

        @Exported(inline = true)
        public List<Placeholder> getBuilds() {
            return elsewhere(job);
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return "Running on other controllers";
        }

        @Override
        public String getUrlName() {
            return "running-elsewhere";
        }

        /**
         * Stops a build running on another controller.
         */
        @RequirePOST
        public HttpResponse doStop(@QueryParameter int number) throws IOException {
            job.checkPermission(Item.CANCEL);
            requestStop(job, number, Jenkins.getAuthentication2().getName());
            return HttpResponses.redirectTo("..");
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class ActionFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            return elsewhere(target).isEmpty() ? List.of() : List.of(new RunningElsewhereAction(target));
        }
    }
}
