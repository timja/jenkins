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
import hudson.model.Action;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

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

        Placeholder(String key, int number, String origin, String controller, String displayName, long startTime) {
            this.key = key;
            this.number = number;
            this.origin = origin;
            this.controller = controller;
            this.displayName = displayName;
            this.startTime = startTime;
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
            StringWriter w = new StringWriter();
            p.store(w, null);
            return w.toString().getBytes(StandardCharsets.UTF_8);
        }

        @CheckForNull
        static Placeholder fromBytes(String key, int number, byte[] content) {
            Properties p = new Properties();
            try {
                p.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
                return new Placeholder(key, number, p.getProperty("origin", ""), p.getProperty("controller", "?"),
                        p.getProperty("displayName", "#" + number), Long.parseLong(p.getProperty("startTime", "0")));
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
                Placeholder p = entry == null ? null : Placeholder.fromBytes(key, number, entry.content());
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
        PersistenceStore store = PersistenceStores.get();
        List<Placeholder> result = new ArrayList<>();
        for (Placeholder p : all(job)) {
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

    static void started(@NonNull Run<?, ?> run) throws IOException {
        String runningKey = runningKey(run.getParent());
        if (runningKey == null) {
            return;
        }
        PersistenceStore store = PersistenceStores.get();
        String key = runningKey + "/" + run.getNumber() + SUFFIX;
        Placeholder p = new Placeholder(key, run.getNumber(), store.getOrigin(), CONTROLLER_NAME, run.getDisplayName(), run.getStartTimeInMillis());
        store.write(key, p.toBytes(), PersistenceStore.ANY);
        invalidate(runningKey.substring(0, runningKey.length() - PREFIX.length() - 1));
    }

    /**
     * Removes the placeholder of a build which completed here; called once its record is published.
     */
    static void finished(@NonNull Run<?, ?> run) throws IOException {
        String runningKey = runningKey(run.getParent());
        if (runningKey == null) {
            return;
        }
        PersistenceStores.get().delete(runningKey + "/" + run.getNumber() + SUFFIX, PersistenceStore.ANY);
        invalidate(runningKey.substring(0, runningKey.length() - PREFIX.length() - 1));
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
        for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
            int removedHere = 0;
            for (Placeholder p : all(job)) {
                if (!live.contains(p.origin)) {
                    LOGGER.info(() -> "Removing placeholder of " + job.getFullName() + " #" + p.number + " left by " + p.controller + " (" + p.origin + "), which is gone");
                    // idempotent, so several controllers noticing at once is harmless
                    store.delete(p.key, PersistenceStore.ANY);
                    removedHere++;
                }
            }
            removed += removedHere;
            if (removedHere > 0) {
                invalidate(PersistenceStores.keyFor(job.getRootDir()));
            }
        }
        return removed;
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
            return null;
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
