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

import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Descriptor;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Loads again, from the persistence store, descriptors that loaded their configuration before the store was available.
 *
 * <p>The store is only resolved once extensions are available ({@link InitMilestone#EXTENSIONS_AUGMENTED});
 * a descriptor created earlier (e.g. by an initializer during plugin start) read its {@code <id>.xml} from the local
 * home, which on a fresh controller has nothing, and would otherwise keep its defaults until restarted.
 */
@Restricted(NoExternalUse.class)
public final class EarlyDescriptors {

    private static final Logger LOGGER = Logger.getLogger(EarlyDescriptors.class.getName());

    private EarlyDescriptors() {}

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void reload() {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return;
        }
        File root = Jenkins.get().getRootDir();
        int reloaded = 0;
        for (Descriptor<?> d : ExtensionList.lookup(Descriptor.class)) {
            // the default location (Descriptor.getConfigFile); getFile() would materialize a snapshot
            String key = PersistenceStores.keyFor(new File(root, d.getId() + ".xml"));
            try {
                if (key != null && store.owns(key) && store.read(key) != null) {
                    d.load();
                    reloaded++;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to load " + d.getId() + " from the persistence store", e);
            }
        }
        int n = reloaded;
        LOGGER.fine(() -> "Loaded " + n + " descriptors from the persistence store");
    }
}
