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
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    /** Deliveries for a running build's steps: {@code running/<N>.deliver-<id>} (Target 15). */
    private static final String DELIVER_INFIX = ".deliver-";

    /**
     * How this controller is named in placeholders; defaults to the host name.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ String CONTROLLER_NAME = SystemProperties.getString(RunningBuilds.class.getName() + ".controllerName", hostName());

    /** How often stale placeholders are looked for, in seconds. */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ int CLEANUP_PERIOD = SystemProperties.getInteger(RunningBuilds.class.getName() + ".cleanupPeriod", 30);

    /** Placeholder property: when its owner {@linkplain #handOver handed it over}, in milliseconds since the epoch. */
    static final String HANDED_OVER = "handedOver";

    /**
     * How long a build handed over waits for its taker: past that, nobody took it (the taker went away, say), and any
     * controller's sweep adopts it, its owner's included, as one of a controller gone.
     */
    @Restricted(NoExternalUse.class)
    public static /* non-final for script console */ long HANDOVER_GRACE_MILLIS = SystemProperties.getLong(RunningBuilds.class.getName() + ".handoverGraceMillis", 120_000L);

    /** Whether a build was handed over long enough ago that its taker is not coming. */
    private static boolean handoverLapsed(Placeholder p) {
        String at = p.properties.get(HANDED_OVER);
        if (at == null) {
            return false;
        }
        try {
            return System.currentTimeMillis() - Long.parseLong(at) >= HANDOVER_GRACE_MILLIS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Properties of a placeholder as its new owner writes them: no longer handed over. */
    private static Map<String, String> owning(Map<String, String> properties) {
        if (!properties.containsKey(HANDED_OVER)) {
            return properties;
        }
        Map<String, String> mine = new java.util.TreeMap<>(properties);
        mine.remove(HANDED_OVER);
        return mine;
    }

    /** Placeholders per job key, kept up to date by change events; loaded from the store on first use. */
    private static final Map<String, List<Placeholder>> cache = new ConcurrentHashMap<>();

    /** The placeholder property recording a completed build's result until its record is published. */
    static final String RESULT = "result";

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
            // not cached: the next call asks the store again (Target 14)
            throw new java.io.UncheckedIOException(e);
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
        try {
            return cache.computeIfAbsent(jobKey, k -> load(store, runningKey));
        } catch (java.io.UncheckedIOException e) {
            LOGGER.log(Level.FINE, "Could not list running builds below " + runningKey, e.getCause());
            return List.of();
        }
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
    @Restricted(NoExternalUse.class)
    public static List<Placeholder> elsewhereCached(@NonNull Job<?, ?> job) {
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
        invalidate(jobKeyOfRunning(runningKey(run.getParent())));
        stopRequestedMeanwhile(run);
    }

    /** Stops a build asked to stop between its placeholder's write and its registration: that request was not taken as ours. */
    private static void stopRequestedMeanwhile(@NonNull Run<?, ?> run) throws IOException {
        String jobKey = jobKeyOfRunning(runningKey(run.getParent()));
        if (PersistenceStores.get().read(jobKey + "/" + PREFIX + "/" + run.getNumber() + STOP_SUFFIX) != null) {
            stopRequested(jobKey, run.getNumber());
        }
    }

    /** Whether a build's saved record says it completed (a record of a running build has no result yet). */
    private static boolean completedLocally(File buildXml) {
        try {
            String xml = java.nio.file.Files.readString(buildXml.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            return xml.contains("<result>") && !xml.contains("<completed>false</completed>");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Takes over the lease of a build this controller's previous incarnation (the same name, another origin) was running,
     * before it is loaded from this home.
     *
     * @return false if another controller holds it, so the local copy must not be loaded (nor resumed)
     */
    static boolean reclaim(@NonNull Job<?, ?> job, int number) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        String runningKey = store == null ? null : runningKey(job);
        if (runningKey == null) {
            return true;
        }
        String key = runningKey + "/" + number + SUFFIX;
        if (owned.containsKey(key)) {
            return true;
        }
        // first what costs nothing: a fresh home has no copy of most builds (asked of each stored number at startup,
        // 20,000 per job with a long history: no store calls for them, which a busy store made take minutes)
        File local = new File(new File(job.getBuildDir(), String.valueOf(number)), "build.xml");
        if (!local.isFile()) {
            return true; // nothing of a previous run here
        }
        Placeholder cached = null;
        for (Placeholder p : all(job)) {
            if (p.number == number) {
                cached = p;
            }
        }
        if (cached != null && store.getOrigin().equals(cached.origin)) {
            return true; // this very process runs it
        }
        if (cached == null && completedLocally(local)) {
            return true; // not running anywhere (the placeholders listed may be stale: a running build is checked below)
        }
        // a build this home has, running somewhere: this controller's previous run had it, and another controller may
        // have adopted it meanwhile (a restart takes longer than seeing a controller gone): ask the store, not the cache
        PersistenceStore.Entry entry = store.read(key);
        Placeholder p = entry == null ? null : Placeholder.fromBytes(key, number, entry.content(), entry.version());
        if (p == null || !CONTROLLER_NAME.equals(p.controller)) {
            if (p != null) {
                LOGGER.info(() -> job.getFullName() + " #" + number + " was adopted by " + p.controller + " while this controller restarted; not resuming it here");
                return false;
            }
            return true; // completed meanwhile
        }
        if (store.getOrigin().equals(p.origin)) {
            return true;
        }
        // as an adoption does: while this controller restarted, its exclusive lease on the job (one build at a time)
        // may have lapsed, and another controller started a newer build; this one then waits for it
        if (!SharedQueue.adoptExclusive(job, number)) {
            throw new NotYet("another build of " + job.getFullName() + " runs; #" + number + " is resumed here once it is done");
        }
        boolean reclaimed = false;
        try {
            BuildLeases leases = store.leases();
            long epoch = leases == null ? p.epoch : leases.acquire(key);
            if (epoch == 0) {
                LOGGER.info(() -> "Another controller holds the lease on " + job.getFullName() + " #" + number + "; not resuming it here");
                return false;
            }
            Placeholder mine = p.withOwner(store.getOrigin(), CONTROLLER_NAME, epoch, owning(p.properties));
            long version;
            try {
                version = store.writeIfHeld(key, epoch, key, mine.toBytes(), p.version); // fenced, as an adoption is
            } catch (LeaseLostException e) {
                LOGGER.info(() -> "Another controller took " + job.getFullName() + " #" + number + " over while it was reclaimed after a restart; not resuming it here");
                return false;
            } catch (ConflictException e) {
                LOGGER.info(() -> job.getFullName() + " #" + number + " changed while being reclaimed after a restart; not resuming it here");
                if (leases != null) {
                    leases.release(key, epoch);
                }
                return false;
            }
            owned.put(key, new Placeholder(key, number, mine.origin, mine.controller, p.displayName, p.startTime, epoch, p.properties, version));
            invalidate(jobKeyOfRunning(runningKey));
            LOGGER.info(() -> "Reclaimed " + job.getFullName() + " #" + number + " from this controller's previous run (" + p.origin + ") with epoch " + epoch);
            reclaimed = true;
            return true;
        } finally {
            if (!reclaimed) {
                SharedQueue.abandonExclusive(job, number); // not resumed here: the job is not blocked for it
            } else {
                SharedQueue.resumedExclusive(job, number); // loaded right after this: the queue here then sees it
            }
        }
    }

    /** A build that cannot be reclaimed yet (another build of its job runs), to be looked at again later. */
    static final class NotYet extends IOException {
        NotYet(String message) {
            super(message);
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
     * Records properties of a running build in its placeholder, for example where its latest save point is; those
     * recorded before are kept unless given again.
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
        Map<String, String> merged = new java.util.HashMap<>(p.properties);
        merged.putAll(properties); // e.g. the latest save point and, once completed, the result
        Placeholder next = p.withOwner(p.origin, p.controller, p.epoch, merged);
        long version;
        try {
            version = PersistenceStores.get().writeIfHeld(key, p.epoch, key, next.toBytes(), p.version);
        } catch (LeaseLostException e) {
            throw new ConflictException(key, p.version, PersistenceStore.ANY); // taken over since it was checked
        }
        owned.put(key, new Placeholder(key, p.number, p.origin, p.controller, p.displayName, p.startTime, p.epoch, merged, version));
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
        if (key != null) {
            finished(key);
        }
    }

    /** {@link #finished(Run)} by the placeholder's key, for a build which may be gone (deleted) since. */
    static void finished(@NonNull String key) throws IOException {
        // kept until all is done: should a delete fail, the retry still knows the epoch to release the lease with
        Placeholder p = owned.get(key);
        PersistenceStore store = PersistenceStores.get();
        store.delete(key, p == null ? PersistenceStore.ANY : p.version);
        store.delete(key.substring(0, key.length() - SUFFIX.length()) + STOP_SUFFIX, PersistenceStore.ANY);
        String base = key.substring(0, key.length() - SUFFIX.length());
        String dir = base.substring(0, base.lastIndexOf('/'));
        String prefix = base.substring(base.lastIndexOf('/') + 1) + DELIVER_INFIX;
        for (String name : store.children(dir)) {
            if (name.startsWith(prefix)) {
                store.delete(dir + "/" + name, PersistenceStore.ANY); // too late to deliver: the build is done
            }
        }
        BuildLeases leases = store.leases();
        if (leases != null && p != null) {
            leases.release(key, p.epoch);
        }
        owned.remove(key);
        String jobKey = jobKeyOf(key);
        if (jobKey != null) {
            invalidate(jobKey);
        }
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

    /** Builds started here whose placeholder and lease could not be recorded (the store was unreachable): retried. */
    private static final Map<String, Boolean> unregistered = new ConcurrentHashMap<>();

    /** Records builds as running whose registration failed when they started (Target 14). */
    static void registerPending() {
        for (String id : List.copyOf(unregistered.keySet())) {
            Run<?, ?> run = Run.fromExternalizableId(id);
            if (run == null || !run.isLogUpdated()) {
                unregistered.remove(id);
                continue;
            }
            try {
                String key = placeholderKey(run);
                if (key != null && owned.containsKey(key)) {
                    // recorded already, and only the look-up that followed failed: not written again, which would drop
                    // what it recorded since (a save point)
                    stopRequestedMeanwhile(run);
                } else {
                    started(run);
                }
                unregistered.remove(id);
                if (!run.isLogUpdated()) {
                    // it completed while being recorded, and may be being published under the lease just taken: completed
                    // again a little later (published, then its placeholder removed, in that order), not cleaned up now
                    BuildRecords.completeLater(run);
                    continue;
                }
                LOGGER.info(() -> "Recorded " + run + " as running, after the store was back");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Still cannot record " + run + " as running", e);
            }
        }
    }

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

    private static final java.util.concurrent.atomic.AtomicBoolean sweeping = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Adopts or closes the builds of controllers which are gone, now rather than at the next periodic sweep: for a store
     * noticing that a controller has left the cluster (Target 14, faster failover).
     */
    @Restricted(Beta.class)
    public static void sweepSoon() {
        jenkins.util.Timer.get().submit(() -> {
            try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                int n = removeStale();
                if (n > 0) {
                    LOGGER.info(() -> "Took care of " + n + " build(s) of controllers which left the cluster");
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not sweep the builds of controllers which left", e);
            }
        });
    }

    /**
     * Removes placeholders whose controller is no longer alive.
     *
     * @return the number removed
     */
    @Restricted(NoExternalUse.class)
    public static int removeStale() throws IOException {
        // one sweep at a time, whichever way it was started (the periodic one, or sweepSoon): leases are reentrant for
        // this controller, so two at once could both adopt, and resume, the same build
        if (!sweeping.compareAndSet(false, true)) {
            return 0; // another sweep is under way; the periodic one follows anyway
        }
        try {
            return sweep();
        } finally {
            sweeping.set(false);
        }
    }

    private static int sweep() throws IOException {
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
                // a build handed over and not taken (its taker went away, say) is adopted as one of a controller gone
                if (unsure.contains(p.key) || (!live.contains(p.origin) && stillGone(store, p.origin, gone)) || handoverLapsed(p)) {
                    long epoch = 0;
                    if (leases != null) {
                        epoch = leases.acquire(p.key);
                        if (epoch == 0) {
                            continue; // another controller is adopting it
                        }
                        Adoption adopted;
                        try {
                            adopted = adopt(store, job, p.key, epoch);
                        } catch (IOException | RuntimeException e) {
                            // its placeholder is kept, for the next sweep to try again; the other builds are still looked at
                            LOGGER.log(Level.WARNING, "Could not adopt " + job.getFullName() + " #" + p.number + "; will retry", e);
                            leases.release(p.key, epoch);
                            continue;
                        }
                        if (adopted != Adoption.NONE) {
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
                            String completed = p.properties.get(RESULT);
                            if (completed != null) {
                                // it completed, but its owner was gone before publishing its record: publish it with its result
                                BuildRecords.publishLost(job, p.number, p.startTime,
                                        "This build completed on " + p.controller + ", which was gone before publishing its record; it is closed with its result.",
                                        Result.fromString(completed));
                            } else {
                                // nothing can resume it: close it as aborted rather than let it vanish (Target 12)
                                BuildRecords.publishLost(job, p.number, p.startTime,
                                        "This build was running on " + p.controller + ", which is gone, and could not be resumed elsewhere; it is closed as aborted.");
                            }
                        } catch (IOException | RuntimeException e) {
                            // keep the placeholder (and release the lease), so that the next sweep tries again (Target 14)
                            LOGGER.log(Level.WARNING, "Could not close " + job.getFullName() + " #" + p.number + " as aborted; will retry", e);
                            if (leases != null) {
                                leases.release(p.key, epoch);
                            }
                            continue;
                        }
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
     * Something for a running build, from another controller (Target 15): typically the outcome a step of it waits for,
     * such as a {@code build} step's downstream build completing on another controller. Delivered by
     * {@link #deliver} to whichever controller owns the build, now or once one adopts it.
     */
    @Restricted(Beta.class)
    public interface Delivery extends hudson.ExtensionPoint {
        /**
         * @param run the build, owned and running here
         * @param id as given to {@link #deliver}
         * @return true once handled (the delivery is then forgotten); false if not for this handler, or not possible yet
         *     (it is offered again a few seconds later, for a while)
         */
        boolean deliver(@NonNull Run<?, ?> run, @NonNull String id, @NonNull String payload) throws Exception;
    }

    /**
     * Delivers something to a running build wherever it runs (Target 15): handled here at once if this controller
     * owns it; else stored for its owner, which handles it when told of it, or after adopting the build.
     *
     * @param id unique for this build and purpose; must not end with {@code .xml}
     */
    @Restricted(Beta.class)
    public static void deliver(@NonNull Job<?, ?> job, int number, @NonNull String id, @NonNull String payload) throws IOException {
        String runningKey = runningKey(job);
        if (runningKey == null) {
            return;
        }
        PersistenceStores.get().write(runningKey + "/" + number + DELIVER_INFIX + id, payload.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        if (owned.containsKey(runningKey + "/" + number + SUFFIX)) {
            String jobKey = jobKeyOfRunning(runningKey);
            Timer.get().submit(() -> delivered(jobKey, number, id, 0));
        }
    }

    /** Parsed key of a delivery: {@code jobs/j/running/5.deliver-x} → job key {@code jobs/j}, number 5, id {@code x}. */
    record DeliveryKey(String jobKey, int number, String id) {
        @CheckForNull
        static DeliveryKey parse(String key) {
            String[] segments = key.split("/");
            int n = segments.length;
            if (n < 4 || !segments[n - 2].equals(PREFIX)) {
                return null;
            }
            String file = segments[n - 1];
            int at = file.indexOf(DELIVER_INFIX);
            if (at <= 0 || !file.substring(0, at).matches("[0-9]+")) {
                return null;
            }
            return new DeliveryKey(String.join("/", Arrays.copyOf(segments, n - 2)), Integer.parseInt(file.substring(0, at)),
                    file.substring(at + DELIVER_INFIX.length()));
        }
    }

    private static final int DELIVERY_ATTEMPTS = 60;

    /** Hands a stored delivery to the {@link Delivery} handlers, if the build is owned and running here. */
    static void delivered(@NonNull String jobKey, int number, @NonNull String id, int attempt) {
        if (!owned.containsKey(jobKey + "/" + PREFIX + "/" + number + SUFFIX)) {
            return; // not ours: its owner handles it, now or after adopting the build
        }
        String key = jobKey + "/" + PREFIX + "/" + number + DELIVER_INFIX + id;
        try {
            PersistenceStore store = PersistenceStores.get();
            PersistenceStore.Entry e = store.read(key);
            if (e == null) {
                return; // handled already
            }
            List<String> names = ItemChangeApplier.itemPath(jobKey + "/config.xml");
            Job<?, ?> job = names == null ? null : Jenkins.get().getItemByFullName(String.join("/", names), Job.class);
            Run<?, ?> run = job == null ? null : job.getBuildByNumber(number);
            if (run != null && run.isLogUpdated()) {
                String payload = new String(e.content(), StandardCharsets.UTF_8);
                for (Delivery d : ExtensionList.lookup(Delivery.class)) {
                    if (d.deliver(run, id, payload)) {
                        store.delete(key, PersistenceStore.ANY);
                        LOGGER.fine(() -> "Delivered " + id + " to " + run);
                        return;
                    }
                }
            }
        } catch (Exception x) {
            LOGGER.log(Level.WARNING, "Could not deliver " + key, x);
        }
        if (attempt < DELIVERY_ATTEMPTS) {
            Timer.get().schedule(() -> delivered(jobKey, number, id, attempt + 1), 5, TimeUnit.SECONDS);
        } else {
            LOGGER.warning(() -> "Gave up delivering " + key);
        }
    }

    /** Deliveries stored for a build while nobody owned it (its owner died). */
    private static void pendingDeliveries(String jobKey, int number) throws IOException {
        for (String name : PersistenceStores.get().children(jobKey + "/" + PREFIX)) {
            if (name.startsWith(number + DELIVER_INFIX)) {
                String id = name.substring((number + DELIVER_INFIX).length());
                Timer.get().schedule(() -> delivered(jobKey, number, id, 0), 1, TimeUnit.SECONDS); // once resumed
            }
        }
    }

    /**
     * The log of a build running on another controller, readable here (Target 15): e.g. from where its owner ships it.
     */
    @Restricted(Beta.class)
    public interface RemoteLog extends hudson.ExtensionPoint {
        /**
         * Copies a build's log from {@code offset}.
         *
         * @return the offset after what was copied, or -1 if this extension cannot read that build's log
         */
        long copy(@NonNull Job<?, ?> job, int number, long offset, @NonNull java.io.OutputStream out) throws IOException;
    }

    /** Whether a build of a job is running on another controller. */
    @Restricted(Beta.class)
    public static boolean isRunningElsewhere(@NonNull Job<?, ?> job, int number) {
        for (Placeholder p : elsewhere(job)) {
            if (p.getNumber() == number) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the log of a build running on another controller (Target 15), for the CLI's {@code console} and
     * {@code build -f}; with {@code follow}, until it completes, then the rest from its record.
     *
     * @return false if the build does not run elsewhere, or no {@link RemoteLog} can read its log
     */
    @Restricted(Beta.class)
    public static boolean followElsewhere(@NonNull Job<?, ?> job, int number, boolean follow, @NonNull java.io.OutputStream out)
            throws IOException, InterruptedException {
        // a build another controller just started (as the shared queue told build -s) may not be known here yet: when
        // following, wait a little for its placeholder, or for its record if it completed meanwhile
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(follow ? 15 : 0);
        while (!isRunningElsewhere(job, number)) {
            if (System.nanoTime() >= until) {
                return false;
            }
            Run<?, ?> done = job.getBuildByNumber(number);
            if (done != null && !done.isLogUpdated()) {
                try (InputStream in = done.getLogInputStream()) {
                    in.transferTo(out);
                }
                out.flush();
                return true;
            }
            Thread.sleep(500);
            invalidate(jobKeyOfRunning(runningKey(job)));
        }
        long offset = 0;
        while (true) {
            long next = -1;
            for (RemoteLog l : ExtensionList.lookup(RemoteLog.class)) {
                next = l.copy(job, number, offset, out);
                if (next >= 0) {
                    break;
                }
            }
            if (next < 0 && !follow) {
                return offset > 0; // nobody can read it
            }
            if (next < 0 && offset == 0 && isRunningElsewhere(job, number)) {
                // following a build whose log has not been shipped yet (its owner ships every few seconds): wait for it
                Thread.sleep(1000);
                invalidate(jobKeyOfRunning(runningKey(job)));
                continue;
            }
            if (next >= 0) {
                offset = next;
                out.flush();
                if (!follow) {
                    return true;
                }
                invalidate(jobKeyOfRunning(runningKey(job)));
            }
            // a shipped log that is gone (next < 0) was deleted once the build completed: the rest is in its record
            if (next < 0 || !isRunningElsewhere(job, number)) {
                Run<?, ?> done = awaitRecord(job, number, 60);
                if (done != null) {
                    try (InputStream in = done.getLogInputStream()) {
                        in.skipNBytes(Math.min(offset, done.getLogText().length()));
                        in.transferTo(out);
                    } catch (java.io.EOFException e) {
                        // the record's log is shorter than what was shipped (e.g. a lost build's)
                    }
                }
                out.flush();
                return true;
            }
            Thread.sleep(1000);
        }
    }

    /** The record of a build which completed on another controller, once published here (waiting up to {@code seconds}). */
    @Restricted(Beta.class)
    @CheckForNull
    public static Run<?, ?> awaitRecord(@NonNull Job<?, ?> job, int number, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run != null && !run.isLogUpdated()) {
                return run;
            }
            Thread.sleep(500);
        }
        return null;
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
            if (run != null && run.isLogUpdated() && interrupt(run, new StoppedElsewhere(CONTROLLER_NAME, by))) {
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
        if (owned.containsKey(key)) {
            // owned here, but not loaded or given an executor yet (being resumed): stopped once it has one (the event of
            // this controller's own write is not applied here)
            stopRequested(jobKeyOfRunning(runningKey), number);
        }
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
                stopLater(jobKey, number, attempt);
            } else {
                LOGGER.warning(() -> "Could not stop " + jobKey + " #" + number + " as requested: it has no executor");
            }
            return;
        }
        store.delete(stopKey, PersistenceStore.ANY);
    }

    /** Tries {@link #stopRequested} again later; again after that if the store fails then (nothing else would). */
    private static void stopLater(String jobKey, int number, int attempt) {
        Timer.get().schedule(() -> {
            try {
                stopRequested(jobKey, number, attempt + 1);
            } catch (IOException | RuntimeException e) {
                if (attempt + 1 < STOP_ATTEMPTS) {
                    LOGGER.log(Level.INFO, "Failed to stop " + jobKey + " #" + number + " as requested; retrying", e);
                    stopLater(jobKey, number, attempt + 1);
                } else {
                    LOGGER.log(Level.WARNING, "Failed to stop " + jobKey + " #" + number + " as requested", e);
                }
            }
        }, Math.min(30, 1L << attempt), TimeUnit.SECONDS);
    }

    /** Stops a build adopted here if that was requested, looking again later if the store cannot tell now. */
    private static void stopIfRequested(String jobKey, int number, int attempt) {
        try {
            stopRequested(jobKey, number);
        } catch (IOException | RuntimeException e) {
            if (attempt < STOP_ATTEMPTS) {
                LOGGER.log(Level.WARNING, "Could not tell whether " + jobKey + " #" + number + " was asked to stop; looking again later", e);
                Timer.get().schedule(() -> stopIfRequested(jobKey, number, attempt + 1), Math.min(30, 1L << attempt), TimeUnit.SECONDS);
            } else {
                LOGGER.log(Level.WARNING, "Could not tell whether " + jobKey + " #" + number + " was asked to stop", e);
            }
        }
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

    /**
     * Placeholders whose adoption here failed as it was written, so that it may have been written all the same: they
     * name this controller, but nothing resumed them. Adopted again by the next sweep (or take-over).
     */
    private static final Set<String> unsure = ConcurrentHashMap.newKeySet();

    /** How many times adoption is retried when the placeholder changes under it. */
    private static final int ADOPTION_ATTEMPTS = 5;

    /** What became of a build offered for adoption. */
    enum Adoption {
        /** Adopted: resumed here, its lease kept. */
        ADOPTED,
        /** Completed (or removed) meanwhile: nothing to adopt; the lease is released. */
        GONE,
        /** Left for a later attempt (its placeholder is kept); the lease is released. */
        LATER,
        /** Nothing can adopt it; the lease is still held, for the caller to close it. */
        NONE
    }

    /**
     * Offers a build whose owner is gone to the {@link BuildAdopter}s, with its lease already held.
     *
     * @return what became of it
     */
    private static Adoption adopt(PersistenceStore store, Job<?, ?> job, String key, long epoch) throws IOException {
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

    private static Adoption offer(PersistenceStore store, Job<?, ?> job, String key, long epoch) throws IOException {
        int number = Integer.parseInt(key.substring(key.lastIndexOf('/') + 1, key.length() - SUFFIX.length()));
        for (int attempt = 0; attempt < ADOPTION_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            if (entry == null) {
                unsure.remove(key);
                // completed meanwhile: nothing to adopt, nor to keep the lease for
                BuildLeases leases = store.leases();
                if (leases != null) {
                    leases.release(key, epoch);
                }
                return Adoption.GONE;
            }
            if (BuildRecords.isStored(job, number)) {
                // completed: its record is published only once it has, and its owner was gone before removing this
                return Adoption.NONE;
            }
            Placeholder p = Placeholder.fromBytes(key, number, entry.content(), entry.version());
            if (p != null && store.getOrigin().equals(p.origin) && !unsure.contains(key) && !p.properties.containsKey(HANDED_OVER)) {
                return Adoption.ADOPTED; // this controller has it already (a sweep listed it as it was): its lease stays
            } // else written by an adoption here whose write seemed to fail, or handed over: not resumed here, so it carries on
            Run<?, ?> local = BuildRecords.loadedCopy(job, number);
            if (local != null && local.isLogUpdated() && !local.hasntStartedYet()) {
                // a copy still running here (lost to the owner now gone, and not stopped yet): not adopted over it, which
                // would leave two; the next sweep tries again
                throw new IOException(job.getFullName() + " #" + number + " still runs here; adopting it once it has stopped");
            } else if (local != null && !local.hasntStartedYet()) {
                BuildRecords.forgetLocalCopy(job, number); // a stale copy (lost here before): the adopted build replaces it
            }
            if (p != null && p.properties.containsKey(RESULT)) {
                return Adoption.NONE; // it completed; its owner was gone before publishing its record: nothing to resume
            }
            BuildAdopter.Adoption adoption = p == null ? null : prepare(job, p);
            if (adoption == null) {
                return Adoption.NONE;
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
                return Adoption.LATER;
            }
            Placeholder mine = p.withOwner(store.getOrigin(), CONTROLLER_NAME, epoch, owning(p.properties));
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
                // perhaps written all the same (the answer lost): it then names this live controller, which a sweep
                // would leave alone, while nothing runs it here; so the next one adopts it again
                unsure.add(key);
                adoption.abandon();
                throw e;
            }
            unsure.remove(key);
            owned.put(key, new Placeholder(key, number, mine.origin, mine.controller, p.displayName, p.startTime, epoch, p.properties, version));
            invalidate(jobKeyOfRunning(runningKey(job)));
            LOGGER.info(() -> "Adopting " + job.getFullName() + " #" + number + " from " + p.controller + " (" + p.origin + ")");
            try {
                adoption.resume(epoch);
            } catch (IOException | RuntimeException e) {
                resumeFailed(store, job, p, version, e);
            }
            SharedQueue.resumedExclusive(job, number); // loaded now (or not resumed, and let go of): the queue sees it
            // asked to stop while its owner was gone; it runs here now whatever this finds, so nothing below throws: a
            // caller takes an exception for "not resumed", and releases the lease
            String jobKey = jobKeyOfRunning(runningKey(job));
            stopIfRequested(jobKey, number, 0);
            pendingDeliveries(jobKey, number); // e.g. a downstream build completed while its owner was gone
            return Adoption.ADOPTED;
        }
        // its owner kept changing it: not as gone as it seemed; a later sweep looks again
        BuildLeases leases = store.leases();
        if (leases != null) {
            leases.release(key, epoch);
        }
        return Adoption.LATER;
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
     * Gives up a build this controller runs so that another controller can take it over ({@link #takeOver}), for example
     * a Pipeline waiting for an agent only another controller has (Target 12). The build is then stopped here without
     * being published, as when ownership is lost. The caller should have a taker lined up: one that does not take it
     * within {@link #HANDOVER_GRACE_MILLIS} leaves it to any controller's sweep, which adopts it as if its owner were gone.
     */
    @Restricted(Beta.class)
    public static void handOver(@NonNull Run<?, ?> run) throws IOException {
        String key = placeholderKey(run);
        PersistenceStore store = PersistenceStores.get();
        Placeholder p = key == null ? null : owned.get(key);
        if (p == null || store == null || store.leases() == null) {
            throw new IOException(run + " is not owned here");
        }
        // marked as handed over first (fenced: still this controller's), so that if nobody takes it, a sweep adopts it
        // after a while rather than it showing as running here for good
        Map<String, String> properties = new java.util.TreeMap<>(p.properties);
        properties.put(HANDED_OVER, String.valueOf(System.currentTimeMillis()));
        Placeholder released = p.withOwner(p.origin, p.controller, p.epoch, properties);
        try {
            store.writeIfHeld(key, p.epoch, key, released.toBytes(), p.version);
        } catch (LeaseLostException e) {
            lost(run);
            throw e;
        }
        store.leases().release(key, p.epoch);
        LOGGER.info(() -> "Handed over " + run);
        lost(run);
    }

    /**
     * Takes over a build another controller {@linkplain #handOver handed over}: acquires its lease and adopts it.
     *
     * @return whether it was adopted
     */
    @Restricted(Beta.class)
    public static boolean takeOver(@NonNull Job<?, ?> job, int number) throws IOException {
        PersistenceStore store = PersistenceStores.get();
        String runningKey = runningKey(job);
        if (store == null || store.leases() == null || runningKey == null) {
            return false;
        }
        String key = runningKey + "/" + number + SUFFIX;
        long epoch = store.leases().acquire(key);
        if (epoch == 0) {
            return false; // not released (yet), or taken by another controller
        }
        Adoption adopted;
        try {
            adopted = adopt(store, job, key, epoch);
        } catch (IOException | RuntimeException e) {
            store.leases().release(key, epoch); // left as it was, for another attempt
            throw e;
        }
        // only an adoption is a takeover: a build that completed meanwhile was not taken over
        return switch (adopted) {
            case ADOPTED -> true;
            case GONE, LATER -> false; // its lease released already
            case NONE -> {
                store.leases().release(key, epoch);
                yield false;
            }
        };
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

        /**
         * A build this controller was running before it restarted with its home (e.g. a safe restart to apply plugin
         * changes) is loaded, and a Pipeline then resumed, from that home: first take its lease over from the previous
         * incarnation, as an adopter would, so that no other controller adopts it too. If another controller holds it
         * already (it adopted the build meanwhile), the local copy is not loaded: that controller's is the build.
         */
        @Override
        public boolean allowLoad(@NonNull Job<?, ?> job, int buildNumber) {
            if (PersistenceStores.isApplyingRemoteChange()) {
                return true;
            }
            try {
                return reclaim(job, buildNumber);
            } catch (NotYet e) {
                LOGGER.info(() -> "Not resuming a build after a restart yet: " + e.getMessage());
                reclaimLater(job, buildNumber);
                return false;
            } catch (IOException | RuntimeException e) {
                // not loaded (nor resumed) without its lease: another controller may run it; looked at again later
                LOGGER.log(Level.WARNING, "Could not reclaim " + job.getFullName() + " #" + buildNumber + " after a restart; trying again later", e);
                reclaimLater(job, buildNumber);
                return false;
            }
        }

        /**
         * Tries to load a build not loaded because it could not be reclaimed yet, so that a Pipeline is resumed without
         * waiting for someone to open it. Only that build: the others of the job, perhaps running, keep their instances.
         */
        private static void reclaimLater(Job<?, ?> job, int number) {
            Timer.get().schedule(() -> {
                if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy) {
                    try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                        lazy.getLazyBuildMixIn()._getRuns().recognizeNumber(number); // declined when listed: not indexed
                        job.getBuildByNumber(number); // a load failing again schedules another
                    }
                }
            }, RECLAIM_RETRY_SECONDS, TimeUnit.SECONDS);
        }

        /** How long a build not reclaimed after a restart waits before it is tried again. */
        static /* non-final for tests */ long RECLAIM_RETRY_SECONDS = 30;

        /**
         * Records the result in the placeholder as soon as a build completes, before its record is published: should
         * this controller die in between, the build is then closed with that result instead of resumed elsewhere
         * (Target 15, ET17: a resumed Pipeline that had completed never ran on).
         */
        @Override
        public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
            Result result = run.getResult();
            if (result == null || PersistenceStores.isApplyingRemoteChange() || ownedPlaceholder(run) == null) {
                return;
            }
            try {
                update(run, Map.of(RESULT, result.toString()));
            } catch (ConflictException e) {
                LOGGER.fine(() -> run + " completed after another controller took it over");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not record the result of " + run + " in its placeholder", e);
            }
        }

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            if (PersistenceStores.isApplyingRemoteChange()) {
                return;
            }
            try {
                started(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to record " + run + " as running; will retry", e);
                unregistered.put(run.getExternalizableId(), Boolean.TRUE);
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
                registerPending();
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
     * A build running on another controller, as the REST API shows it in a job's {@code buildsElsewhere} (Target 14):
     * the main properties, with the same visibility, of a {@link Run} that is building. Its actions (parameters, causes, …) are
     * known to the controller running it only: they are listed empty here, and its own URL (and API), which that
     * controller answers, has them.
     */
    @ExportedBean
    @Restricted(Beta.class)
    public static final class RemoteBuild {
        private final Job<?, ?> job;
        private final Placeholder p;

        RemoteBuild(Job<?, ?> job, Placeholder p) {
            this.job = job;
            this.p = p;
        }

        @Exported(visibility = 2)
        public int getNumber() {
            return p.number;
        }

        @Exported(visibility = 2)
        public String getUrl() {
            return Jenkins.get().getRootUrlFromRequest() + job.getUrl() + p.number + "/";
        }

        /** None known here: see the build's own API, which the controller running it answers. */
        @Exported
        public List<hudson.model.Action> getActions() {
            return List.of();
        }

        @Exported
        public String getId() {
            return String.valueOf(p.number);
        }

        @Exported
        public String getDisplayName() {
            return p.displayName;
        }

        @Exported
        public String getFullDisplayName() {
            return job.getFullDisplayName() + " " + p.displayName;
        }

        @Exported
        public boolean isBuilding() {
            return true;
        }

        @Exported
        public boolean isInProgress() {
            return true;
        }

        @Exported
        public Object getResult() {
            return null;
        }

        @Exported
        public long getTimestamp() {
            return p.startTime;
        }

        @Exported
        public long getDuration() {
            return 0;
        }

        @Exported
        public long getEstimatedDuration() {
            return job.getEstimatedDuration();
        }

        @Exported
        public String getDescription() {
            return null;
        }

        @Exported
        public boolean isKeepLog() {
            return false;
        }

        @Exported
        public long getQueueId() {
            return -1;
        }

        @Exported
        public Object getExecutor() {
            return null;
        }

        @Exported
        public List<Object> getArtifacts() {
            return List.of();
        }

        /** Which controller runs it (not on a {@link Run}). */
        @Exported
        public String getController() {
            return p.controller;
        }
    }

    /** A job's builds running on other controllers, newest first, as the REST API shows them. */
    @Restricted(NoExternalUse.class)
    @NonNull
    public static List<RemoteBuild> remoteBuilds(@NonNull Job<?, ?> job) {
        List<RemoteBuild> builds = new ArrayList<>();
        for (Placeholder p : elsewhere(job)) {
            builds.add(new RemoteBuild(job, p));
        }
        return builds;
    }

    /** The number of the newest build of a job running on another controller, if newer than this controller's newest. */
    @Restricted(Beta.class)
    public static int newerElsewhere(@NonNull Job<?, ?> job) {
        List<Placeholder> remote = elsewhere(job);
        Run<?, ?> local = job.getLastBuild();
        return !remote.isEmpty() && (local == null || remote.get(0).number > local.getNumber()) ? remote.get(0).number : 0;
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

    /**
     * The page of a build running on another controller, at the build's own URL here ({@code job/x/N/}), so that its
     * links never find nothing. A cluster routing such requests to the controller running the build answers them first;
     * without one, this shows the build as running there, until its record arrives and the build itself is shown.
     *
     * @return null if no build of this number runs elsewhere
     */
    @Restricted(NoExternalUse.class)
    @CheckForNull
    public static BuildElsewhere buildElsewhere(@NonNull Job<?, ?> job, int number) {
        for (Placeholder p : elsewhere(job)) {
            if (p.number == number) {
                return new BuildElsewhere(job, p);
            }
        }
        return null;
    }

    /** A build running on another controller, as a page: {@code index} and {@code console}. */
    @Restricted(NoExternalUse.class)
    public static final class BuildElsewhere {
        private final Job<?, ?> job;
        private final Placeholder placeholder;

        BuildElsewhere(Job<?, ?> job, Placeholder placeholder) {
            this.job = job;
            this.placeholder = placeholder;
        }

        public Job<?, ?> getJob() {
            return job;
        }

        public Placeholder getPlaceholder() {
            return placeholder;
        }

        public String getDisplayName() {
            return job.getFullDisplayName() + " " + placeholder.getDisplayName();
        }

        public boolean isStoppable() {
            return job.hasPermission(Item.CANCEL);
        }

        /** Stops it: asks the controller running it to. */
        @RequirePOST
        public HttpResponse doStop() throws IOException {
            job.checkPermission(Item.CANCEL);
            requestStop(job, placeholder.number, Jenkins.getAuthentication2().getName());
            return HttpResponses.redirectTo(".");
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
