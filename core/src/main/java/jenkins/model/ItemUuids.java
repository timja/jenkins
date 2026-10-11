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


package jenkins.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Finds items by {@link Item#getItemUuid}, the identity renames do not change (Target 15).
 *
 * @since TODO
 */
@Restricted(Beta.class)
public final class ItemUuids {

    private static final Logger LOGGER = Logger.getLogger(ItemUuids.class.getName());

    private static final Map<String, String> fullNames = new ConcurrentHashMap<>();

    private ItemUuids() {}

    /** The item with this identity, if this user may see it. */
    @CheckForNull
    public static Item get(@NonNull String uuid) {
        String fullName = fullNames.get(uuid);
        Item item = fullName == null ? null : Jenkins.get().getItemByFullName(fullName);
        return item != null && uuid.equals(item.getItemUuid()) ? item : null;
    }

    private static void index(Item item) {
        String id = item.getItemUuid();
        if (id != null) {
            fullNames.put(id, item.getFullName());
        }
    }

    /** Keeps the index, and gives an item created with another's identity (e.g. from copied XML) one of its own. */
    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Listener extends ItemListener {
        @Override
        public void onLoaded() {
            for (Item item : Jenkins.get().allItems()) {
                index(item);
            }
        }

        @Override
        public void onCreated(Item item) {
            String id = item.getItemUuid();
            String other = id == null ? null : fullNames.get(id);
            // only where it was created: the other controllers of a cluster take what it saves
            boolean clash = other != null && !other.equals(item.getFullName()) && Jenkins.get().getItemByFullName(other) != null;
            // created from configuration without one: derived from its name, it would be a deleted item's of that name
            boolean derived = id != null && id.equals(AbstractItem.legacyItemUuid(item.getFullName()));
            if ((clash || derived) && item instanceof AbstractItem ai && !PersistenceStores.isApplyingRemoteChange()) {
                LOGGER.fine(() -> item.getFullName() + " was created without an identity of its own; giving it one");
                ai.newItemUuid();
                try {
                    ai.save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not save the new identity of " + item.getFullName(), e);
                }
            }
            index(item);
        }

        @Override
        public void onUpdated(Item item) {
            index(item);
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            index(item);
        }

        @Override
        public void onDeleted(Item item) {
            String id = item.getItemUuid();
            if (id != null) {
                fullNames.remove(id, item.getFullName());
            }
        }
    }
}
