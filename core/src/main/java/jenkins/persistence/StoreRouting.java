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
import hudson.ExtensionListListener;
import hudson.init.InitMilestone;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The single routing policy: which files under {@code JENKINS_HOME} are served by the active {@link PersistenceStore}.
 *
 * <p>Inputs: core's default shared set (see {@link SharedFiles}), the {@link SharedFiles} and {@link LocalFiles}
 * extensions ({@link LocalFiles} wins), and the keys {@linkplain #keepLocal kept local} for cloud agents.
 * Keys whose first segment starts with {@code _} are never routed. {@code nextBuildNumber} is not routed, nor is
 * anything in a build directory ({@code builds/<number>/…}) or in {@code secrets/} (confidential keys are shared, if at
 * all, by the confidential store itself).
 * Routing changes only when those extension lists change or a cloud agent is added or removed.
 *
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public final class StoreRouting {

    private static final Logger LOGGER = Logger.getLogger(StoreRouting.class.getName());

    private StoreRouting() {}

    /**
     * Whether root-level static state (root {@code *.xml}, users, nodes, {@code log/*.xml}) is in the default shared
     * set: {@code -Djenkins.persistence.StoreRouting.staticState=false} keeps it local.
     */
    public static final boolean STATIC_STATE = SystemProperties.getBoolean(StoreRouting.class.getName() + ".staticState", true);

    /** Keys kept local whatever the patterns say. */
    private static final Set<String> keptLocal = ConcurrentHashMap.newKeySet();

    /** Patterns of {@link LocalFiles}; until extensions are loaded, core's own. */
    private static volatile List<PathMatcher> localPatterns = matchers(new LocalFiles.Core().patterns());
    /** Patterns of {@link SharedFiles}; none until extensions are loaded. */
    private static volatile List<PathMatcher> sharedPatterns = List.of();

    private static List<PathMatcher> matchers(Collection<String> patterns) {
        List<PathMatcher> result = new ArrayList<>();
        for (String p : patterns) {
            try {
                result.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Ignoring the file pattern " + p, e);
            }
        }
        return List.copyOf(result);
    }

    /** The Jenkins whose extensions the patterns were loaded from. */
    private static volatile Jenkins loadedFor;
    /** Set while this thread loads the patterns, so that extensions reading files meanwhile do not recurse. */
    private static final ThreadLocal<Boolean> LOADING = new ThreadLocal<>();

    /**
     * Loads the {@link LocalFiles} and {@link SharedFiles} patterns once extensions are available (the store is never
     * active before), and again whenever plugins add some.
     */
    private static void loadPatterns() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null || loadedFor == j || LOADING.get() != null || j.getInitLevel().compareTo(InitMilestone.EXTENSIONS_AUGMENTED) < 0) {
            return;
        }
        LOADING.set(Boolean.TRUE);
        try {
            ExtensionList<LocalFiles> local = ExtensionList.lookup(LocalFiles.class);
            ExtensionList<SharedFiles> shared = ExtensionList.lookup(SharedFiles.class);
            local.size(); // loaded before taking the lock, as extensions loading may read files
            shared.size();
            synchronized (StoreRouting.class) {
                if (loadedFor != j) {
                    refresh(local, shared);
                    listen(local, shared);
                    loadedFor = j;
                }
            }
        } finally {
            LOADING.remove();
        }
    }

    private static void listen(ExtensionList<LocalFiles> local, ExtensionList<SharedFiles> shared) {
        ExtensionListListener l = new ExtensionListListener() {
            @Override
            public void onChange() {
                refresh(local, shared);
            }
        };
        local.addListener(l);
        shared.addListener(l);
    }

    private static void refresh(ExtensionList<LocalFiles> local, ExtensionList<SharedFiles> shared) {
        List<String> patterns = new ArrayList<>(new LocalFiles.Core().patterns());
        for (LocalFiles l : local) {
            patterns.addAll(l.patterns());
        }
        localPatterns = matchers(patterns);
        patterns.clear();
        for (SharedFiles s : shared) {
            patterns.addAll(s.patterns());
        }
        sharedPatterns = matchers(patterns);
    }

    /**
     * Finds out whether a file is served by the active store.
     *
     * @return a route to the key in the store, or null if the file lives on the local filesystem (always null with no store)
     */
    @CheckForNull
    public static Route route(@NonNull File file) {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return null;
        }
        String key = PersistenceStores.keyFor(file);
        return key != null && isRouted(key) ? new Route(store, key) : null;
    }

    /**
     * Whether a key is served by the store when one is active: in the default shared set or a {@link SharedFiles}
     * pattern, and neither {@linkplain #keepLocal kept local}, nor in a {@link LocalFiles} pattern, nor excluded.
     */
    public static boolean isRouted(@NonNull String key) {
        loadPatterns();
        if (key.isEmpty() || key.startsWith("_") || !PersistenceStores.isSafeKey(key)) {
            return false; // reserved for direct users of the store; or no file name
        }
        List<String> segments = List.of(key.split("/"));
        if (segments.get(0).equals("secrets") || segments.get(segments.size() - 1).equals("nextBuildNumber") || inBuildDirectory(segments)) {
            return false;
        }
        if (keptLocal.contains(key)) {
            return false;
        }
        Path path;
        try {
            path = Path.of(key);
        } catch (InvalidPathException e) {
            return false;
        }
        for (PathMatcher m : localPatterns) {
            if (m.matches(path)) {
                return false;
            }
        }
        if (isDefaultShared(segments)) {
            return true;
        }
        for (PathMatcher m : sharedPatterns) {
            if (m.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a key is below a build directory, {@code builds/<number>}: build data is never routed. */
    private static boolean inBuildDirectory(List<String> segments) {
        for (int i = 1; i < segments.size() - 1; i++) {
            if (segments.get(i).equals("builds") && isNumber(segments.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultShared(List<String> segments) {
        int n = segments.size();
        String first = segments.get(0);
        String last = segments.get(n - 1);
        if (first.equals("jobs") && n >= 3 && last.equals("config.xml")) {
            return true; // items, and what is configured below them: folders' items, branches, matrix configurations…
        }
        if (!STATIC_STATE) {
            return false;
        }
        if (n == 1) {
            return last.endsWith(".xml"); // the global configuration, descriptors
        }
        if (n == 3 && (first.equals("users") || first.equals("nodes")) && last.equals("config.xml")) {
            return true;
        }
        return n == 2 && first.equals("log") && last.endsWith(".xml");
    }

    /**
     * Keeps a key local (or shares it again), whatever the patterns say: used by {@code Nodes} for cloud agents,
     * which each controller provisions for itself.
     */
    public static void keepLocal(@NonNull String key, boolean local) {
        if (local) {
            keptLocal.add(key);
        } else {
            keptLocal.remove(key);
        }
    }

    /** Whether a key is {@linkplain #keepLocal kept local}. */
    public static boolean isKeptLocal(@NonNull String key) {
        return keptLocal.contains(key);
    }
}
