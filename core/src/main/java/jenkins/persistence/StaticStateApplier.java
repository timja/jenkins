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
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Descriptor;
import hudson.model.User;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Applies changes other controllers made to static state in the shared store: the global configuration, descriptors'
 * configuration, users and nodes. A {@link RemoteChangeHandler} which handles such a key (a plugin's own root file,
 * say) applies it instead. Called by {@link ItemChangeApplier}.
 */
@Restricted(NoExternalUse.class)
public final class StaticStateApplier {

    private static final Logger LOGGER = Logger.getLogger(StaticStateApplier.class.getName());

    private static final Pattern USER = Pattern.compile("users/([^/]+)/config\\.xml");
    private static final Pattern NODE = Pattern.compile("nodes/([^/]+)/config\\.xml");

    private StaticStateApplier() {}

    /**
     * @return true if applied
     */
    static boolean apply(@NonNull String key, boolean deleted) throws Exception {
        for (RemoteChangeHandler h : ExtensionList.lookup(RemoteChangeHandler.class)) {
            if (h.handles(key)) {
                LOGGER.fine(() -> h + " applies " + key + " changed elsewhere");
                h.changed(key, deleted);
                return true;
            }
        }
        Jenkins j = Jenkins.get();
        if (key.equals("config.xml")) {
            if (deleted) {
                return false;
            }
            XmlFile config = new XmlFile(Jenkins.XSTREAM, new File(j.getRootDir(), "config.xml"));
            Route route = StoreRouting.route(config.getPath());
            PersistenceStore.Entry entry = route == null ? null : route.peek();
            if (entry != null) {
                LOGGER.fine("Reloading global configuration changed elsewhere");
                StoredReloads.reload(config, j, entry);
            }
            return true;
        }
        Matcher m = USER.matcher(key);
        if (m.matches()) {
            LOGGER.fine(() -> "Applying user " + m.group(1) + " changed elsewhere");
            User.changedElsewhere(m.group(1), deleted);
            return true;
        }
        Matcher n = NODE.matcher(key);
        if (n.matches()) {
            LOGGER.fine(() -> "Applying node " + n.group(1) + " changed elsewhere");
            j.getNodesObject().changedElsewhere(n.group(1), deleted);
            return true;
        }
        Descriptor<?> d = descriptorOf(key);
        if (d != null) {
            Route route = StoreRouting.route(Descriptor.configFileOf(d).getPath());
            PersistenceStore.Entry entry = deleted || route == null ? null : route.peek();
            if (entry != null) {
                LOGGER.fine(() -> "Reloading " + d.getId() + " changed elsewhere");
                StoredReloads.reload(Descriptor.configFileOf(d), d, entry);
            }
            return true;
        }
        LOGGER.info(() -> key + " was changed elsewhere; nothing here applies it until it is next loaded");
        return false;
    }

    /** The descriptor whose configuration file has a key, wherever it keeps it, or null. */
    @CheckForNull
    static Descriptor<?> descriptorOf(@NonNull String key) {
        for (Descriptor<?> d : ExtensionList.lookup(Descriptor.class)) {
            if (key.equals(PersistenceStores.keyFor(Descriptor.configFileOf(d).getPath()))) {
                return d;
            }
        }
        return null;
    }

    /**
     * Loads again, from the store, descriptors that loaded their configuration before the store was available: one
     * created before extensions were (by an initializer during plugin start, say) read its file from the local home,
     * which on a fresh controller has nothing, and would otherwise keep its defaults until restarted.
     */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void reloadEarlyDescriptors() {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return;
        }
        int reloaded = 0;
        for (Descriptor<?> d : ExtensionList.lookup(Descriptor.class)) {
            Route route = StoreRouting.route(Descriptor.configFileOf(d).getPath());
            try {
                // read once: a descriptor already loaded from the store holds its version, and is not loaded again
                if (route != null && StoredVersions.lastSeen(route.key()) == PersistenceStore.ANY && route.exists()) {
                    d.load();
                    reloaded++;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to load " + d.getId() + " from the persistence store", e);
            }
        }
        int count = reloaded;
        LOGGER.fine(() -> "Loaded " + count + " descriptors from the persistence store");
    }
}
