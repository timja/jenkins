/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc., Stephen Connolly
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
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.Failure;
import hudson.model.Node;
import hudson.model.PersistenceRoot;
import hudson.model.Queue;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.OfflineCause;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Manages all the nodes for Jenkins.
 *
 * @since 1.607
 */
@Restricted(NoExternalUse.class) // for now, we may make it public later
public class Nodes implements PersistenceRoot {

    private static final Logger LOGGER = Logger.getLogger(Nodes.class.getName());

    /**
     * Determine if we need to enforce the name restrictions during node creation or replacement.
     * Should be enabled (default) to prevent SECURITY-2021.
     */
    @Restricted(NoExternalUse.class)
    private static final boolean ENFORCE_NAME_RESTRICTIONS = SystemProperties.getBoolean(Nodes.class.getName() + ".enforceNameRestrictions", true);

    /**
     * The {@link Jenkins} instance that we are tracking nodes for.
     */
    @NonNull
    private final Jenkins jenkins;

    /**
     * The map of nodes.
     */
    private final ConcurrentMap<String, Node> nodes = new ConcurrentSkipListMap<>();

    /**
     * Constructor, intended to be called only from {@link Jenkins}.
     *
     * @param jenkins A reference to the {@link Jenkins} that this instance is tracking nodes for, beware not to
     *                let this reference escape from a partially constructed {@link Nodes} as when we are passed the
     *                reference the {@link Jenkins} instance has not completed instantiation.
     */
    /*package*/ Nodes(@NonNull Jenkins jenkins) {
        this.jenkins = jenkins;
    }

    /**
     * Returns the list of nodes.
     *
     * @return the list of nodes.
     */
    @NonNull
    public List<Node> getNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Sets the list of nodes.
     *
     * @param nodes the new list of nodes.
     * @throws IOException if the new list of nodes could not be persisted.
     */
    public void setNodes(final @NonNull Collection<? extends Node> nodes) throws IOException {
        Map<String, Node> toRemove = new HashMap<>();
        Queue.withLock(() -> {
            toRemove.putAll(Nodes.this.nodes);
            for (var node : nodes) {
                final var name = node.getNodeName();
                Nodes.this.nodes.put(name, node);
                node.onLoad(Nodes.this, name);
                var oldNode = toRemove.get(name);
                if (oldNode != null) {
                    node.setTemporaryOfflineCause(oldNode.getTemporaryOfflineCause());
                    NodeListener.fireOnUpdated(oldNode, node);
                    toRemove.remove(name);
                } else {
                    NodeListener.fireOnCreated(node);
                }
            }
            Nodes.this.nodes.keySet().removeAll(toRemove.keySet());
            jenkins.updateComputerList();
            jenkins.trimLabels();
        });
        save();
        for (var deletedNode : toRemove.values()) {
            NodeListener.fireOnDeleted(deletedNode);
            var nodeName = deletedNode.getNodeName();
            LOGGER.fine(() -> "deleting " + new File(getRootDir(), nodeName));
            deleteNodeDir(nodeName);
        }
    }

    /**
     * Adds a node if a node with the given name doesn't already exist. This is equivalent to
     *
     * <pre>
     * if (nodes.getNode(node.getNodeName()) == null) {
     *     nodes.addNode(node);
     * }
     * </pre>
     *
     * except that it happens atomically.
     *
     * @param node the new node.
     * @return True if the node was added. False otherwise (indicating a node with the given name already exists)
     * @throws IOException if the list of nodes could not be persisted.
     * @since 2.529
     */
    public boolean addNodeIfAbsent(final @NonNull Node node) throws IOException {
        if (ENFORCE_NAME_RESTRICTIONS) {
            Jenkins.checkGoodName(node.getNodeName());
        }

        Node old = nodes.putIfAbsent(node.getNodeName(), node);
        if (old == null) {
            handleAddedNode(node, null);
            return true;
        }
        return false;
    }

    /**
     * Adds a node. If a node of the same name already exists then that node will be replaced.
     *
     * @param node the new node.
     * @throws IOException if the list of nodes could not be persisted.
     */
    public void addNode(final @NonNull Node node) throws IOException {
        if (ENFORCE_NAME_RESTRICTIONS) {
            Jenkins.checkGoodName(node.getNodeName());
        }

        Node old = nodes.put(node.getNodeName(), node);
        if (node != old) {
            handleAddedNode(node, old);
        }
    }

    private void handleAddedNode(final @NonNull Node node, final Node old) throws IOException {
        node.onLoad(this, node.getNodeName());
        jenkins.updateNewComputer(node);
        jenkins.trimLabels(node, old);
        // TODO there is a theoretical race whereby the node instance is updated/removed after lock release
        try {
            node.save();
        } catch (IOException | RuntimeException e) {
            // JENKINS-50599: If persisting the node throws an exception, we need to remove the node from
            // memory before propagating the exception.
            Queue.runWithLock(() -> {
                nodes.compute(node.getNodeName(), (ignoredNodeName, ignoredNode) -> old);
                jenkins.updateComputers(node);
                if (old != null) {
                    jenkins.trimLabels(node, old);
                } else {
                    jenkins.trimLabels(node);
                }
            });
            throw e;
        }
        if (old != null) {
            NodeListener.fireOnUpdated(old, node);
        } else {
            NodeListener.fireOnCreated(node);
        }
    }

    public XmlFile getConfigFile(Node node) {
        if (node instanceof AbstractCloudSlave) {
            // launched by this controller's cloud: other controllers sharing a persistence store must not launch it too
            String key = PersistenceStores.keyFor(new File(node.getRootDir(), "config.xml"));
            if (key != null) {
                PersistenceStores.keepLocal(key, true);
            }
        }
        return getConfigFile(node.getRootDir());
    }

    /** Whether a node is a cloud agent which another controller launched (its configuration came from the store). */
    private static boolean launchedElsewhere(Node n, File dir) {
        String key = PersistenceStores.keyFor(new File(dir, "config.xml"));
        return n instanceof AbstractCloudSlave && key != null && !PersistenceStores.isKeptLocal(key);
    }

    public XmlFile getConfigFile(File dir) {
        return new XmlFile(Jenkins.XSTREAM, new File(dir, "config.xml"));
    }

    public XmlFile getConfigFile(String nodeName) {
        return new XmlFile(Jenkins.XSTREAM, new File(getRootDir(), nodeName + File.separator + "config.xml"));
    }

    /**
     * Updates an existing node on disk. If the node instance is not in the list of nodes, then this
     * will be a no-op, even if there is another instance with the same {@link Node#getNodeName()}.
     *
     * @param node the node to be updated.
     * @return {@code true}, if the node was updated. {@code false}, if the node was not in the list of nodes.
     * @throws IOException if the node could not be persisted.
     * @since 1.634
     */
    public boolean updateNode(final @NonNull Node node) throws IOException {
        return updateNode(node, true);
    }

    private boolean updateNode(final @NonNull Node node, boolean fireListener) throws IOException {
        boolean exists;
        try {
            exists = Queue.withLock(() -> {
                if (node == nodes.get(node.getNodeName())) {
                    jenkins.trimLabels(node);
                    return true;
                }
                return false;
            });
        } catch (RuntimeException e) {
            // should never happen, but if it does let's do the right thing
            throw e;
        } catch (Exception e) {
            // can never happen
            exists = false;
        }
        if (exists) {
            // TODO there is a theoretical race whereby the node instance is updated/removed after lock release
            node.save();
            if (fireListener) {
                NodeListener.fireOnUpdated(node, node);
            }
            return true;
        }
        return false;
    }

    /**
     * Replace node of given name.
     *
     * @return {@code true} if node was replaced.
     * @since 2.8
     */
    public boolean replaceNode(final Node oldOne, final @NonNull Node newOne) throws IOException {
        if (ENFORCE_NAME_RESTRICTIONS) {
            Jenkins.checkGoodName(newOne.getNodeName());
        }

        if (oldOne == nodes.get(oldOne.getNodeName())) {
            // use the queue lock until Nodes has a way of directly modifying a single node.
            Queue.runWithLock(() -> {
                if (!newOne.getNodeName().equals(oldOne.getNodeName())
                        && nodes.containsKey(newOne.getNodeName())) {
                    throw new Failure("Node already exists: " + newOne.getNodeName());
                }
                Nodes.this.nodes.remove(oldOne.getNodeName());
                Nodes.this.nodes.put(newOne.getNodeName(), newOne);
                newOne.onLoad(Nodes.this, newOne.getNodeName());
            });
            updateNode(newOne, false);
            if (!newOne.getNodeName().equals(oldOne.getNodeName())) {
                LOGGER.fine(() -> "deleting " + new File(getRootDir(), oldOne.getNodeName()));
                deleteNodeDir(oldOne.getNodeName());
            }
            Queue.withLock(() -> {
                jenkins.updateComputers(newOne);
                jenkins.trimLabels(oldOne, newOne);
            });
            NodeListener.fireOnUpdated(oldOne, newOne);

            return true;
        } else {
            return false;
        }
    }

    /**
     * Removes a node. If the node instance is not in the list of nodes, then this will be a no-op, even if
     * there is another instance with the same {@link Node#getNodeName()}.
     *
     * @param node the node instance to remove.
     * @throws IOException if the list of nodes could not be persisted.
     */
    public void removeNode(final @NonNull Node node) throws IOException {
        if (node == nodes.get(node.getNodeName())) {
            AtomicBoolean match = new AtomicBoolean();
            Queue.runWithLock(() -> {
                Computer c = node.toComputer();
                if (c != null) {
                    c.recordTermination();
                    c.disconnect(OfflineCause.create(hudson.model.Messages._Hudson_NodeBeingRemoved()));
                }
                match.set(node == nodes.remove(node.getNodeName()));
            });
            // no need for a full save() so we just do the minimum
            LOGGER.fine(() -> "deleting " + new File(getRootDir(), node.getNodeName()));
            // before deleting: getConfigFile(Node) keeps a cloud agent's key local, which the deletion then undoes
            XmlFile config = getConfigFile(node);
            deleteNodeDir(node.getNodeName());

            if (match.get()) {
                jenkins.updateComputers(node);
                jenkins.trimLabels(node);
            }
            NodeListener.fireOnDeleted(node);
            SaveableListener.fireOnDeleted(node, config);
        }
    }

    /**
     * Applies a change another controller made to a node's configuration in a shared persistence store:
     * loads it again (or for the first time), or forgets it if it was deleted. Nothing is saved.
     */
    @Restricted(NoExternalUse.class)
    public void changedElsewhere(@NonNull String name, boolean deleted) {
        if (!PersistenceStores.isSafeName(name)) {
            // from a key no controller writes: ".." would be JENKINS_HOME itself, deleted below
            LOGGER.warning(() -> "Ignoring a node named " + name + ", changed elsewhere: not a node's name");
            return;
        }
        Node old = getNode(name);
        if (deleted) {
            if (old != null) {
                unload(old);
                jenkins.updateComputerList();
                jenkins.trimLabels();
                NodeListener.fireOnDeleted(old);
            }
            try {
                // a copy left here (read from the store, say) must not bring the node back at the next startup
                Util.deleteRecursive(new File(getRootDir(), name));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "could not delete the directory of " + name + ", deleted elsewhere", e);
            }
            return;
        }
        Node loaded = load(name, true);
        if (loaded == null) {
            return; // gone meanwhile, or vetoed here (then dropped here too, as at startup)
        }
        if (old != null) {
            Queue.withLock(() -> {
                jenkins.updateComputerList(); // rebind the computer to the new node object
                jenkins.trimLabels(old, loaded); // as a local replacement does: a label removed elsewhere leaves it now
            });
            NodeListener.fireOnUpdated(old, loaded);
        } else {
            NodeListener.fireOnCreated(loaded);
        }
    }

    /**
     * Deletes a node's directory, and its configuration from a persistence store sharing it, so that it sees the deletion.
     * The local configuration goes with the directory, which is only unlinked if it is a symbolic link.
     */
    private void deleteNodeDir(String nodeName) throws IOException {
        PersistenceStores.Route route = PersistenceStores.route(getConfigFile(nodeName).getPath());
        if (route != null) {
            route.delete();
        }
        Util.deleteRecursive(new File(getRootDir(), nodeName));
        String key = PersistenceStores.keyFor(new File(getRootDir(), nodeName + File.separator + "config.xml"));
        if (key != null) {
            PersistenceStores.keepLocal(key, false);
        }
    }

    @Override
    public void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        for (Node n : nodes.values()) {
            if (n instanceof EphemeralNode) {
                continue;
            }
            XmlFile xmlFile = getConfigFile(n);
            LOGGER.fine(() -> "saving " + xmlFile);
            xmlFile.write(n);
            SaveableListener.fireOnChange(this, xmlFile);
        }
    }

    /**
     * Returns the named node.
     *
     * @param name the {@link Node#getNodeName()} of the node to retrieve.
     * @return the {@link Node} or {@code null} if the node could not be found.
     */
    @CheckForNull
    public Node getNode(String name) {
        return name == null ? null : nodes.get(name);
    }

    /**
     * Loads the nodes from disk.
     *
     * @throws IOException if the nodes could not be deserialized.
     */
    public void load() throws IOException {
        final File nodesDir = getRootDir();
        final File[] localDirs = nodesDir.listFiles(File::isDirectory);
        // nodes may also exist only in a persistence store shared with other controllers
        final Set<File> subdirs = new TreeSet<>(localDirs == null ? List.of() : Arrays.asList(localDirs));
        final Set<String> stored = PersistenceStores.storedItemNames(nodesDir);
        for (String name : stored) {
            subdirs.add(new File(nodesDir, name));
        }
        final Map<String, Node> newNodes = new TreeMap<>();
        {
            for (File subdir : subdirs) {
                try {
                    boolean local = localDirs != null && Arrays.asList(localDirs).contains(subdir);
                    // only here (this controller's cloud agent, never written to the store): read it from here, which
                    // routing to the store would not find, and keep it here if it is a cloud agent
                    String key = PersistenceStores.keyFor(new File(subdir, "config.xml"));
                    boolean onlyHere = local && key != null && !stored.contains(subdir.getName());
                    if (onlyHere) {
                        PersistenceStores.keepLocal(key, true);
                    }
                    Node n = load(subdir, newNodes);
                    if (onlyHere && !(n instanceof AbstractCloudSlave)) {
                        PersistenceStores.keepLocal(key, false); // a permanent node: shared from its next save
                    }
                    if (n != null && !local && launchedElsewhere(n, subdir)) {
                        newNodes.remove(n.getNodeName()); // only in the store: another controller's cloud agent
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "could not load " + subdir, e);
                }
            }
        }
        Queue.runWithLock(() -> {
            newNodes.entrySet().removeIf(stringNodeEntry -> ExtensionList.lookup(NodeListener.class).stream().anyMatch(nodeListener -> {
                if (!nodeListener.allowLoad(stringNodeEntry.getValue())) {
                    LOGGER.log(Level.FINE, () -> "Loading of node " + stringNodeEntry.getKey() + " vetoed by " + nodeListener);
                    return true;
                }
                return false;
            }));
            nodes.entrySet().removeIf(stringNodeEntry -> !(stringNodeEntry.getValue() instanceof EphemeralNode));
            nodes.putAll(newNodes);
            jenkins.updateComputerList();
            jenkins.trimLabels();
        });
    }

    /** Whether every {@link NodeListener} allows a node to be loaded, as {@link #load()} asks. */
    private static boolean allowLoad(Node node) {
        return ExtensionList.lookup(NodeListener.class).stream().allMatch(nodeListener -> {
            if (!nodeListener.allowLoad(node)) {
                LOGGER.log(Level.FINE, () -> "Loading of node " + node.getNodeName() + " vetoed by " + nodeListener);
                return false;
            }
            return true;
        });
    }

    @CheckForNull
    public Node getOrLoad(String name) {
        Node node = getNode(name);
        LOGGER.fine(() -> "already loaded? " + node);
        return node == null ? load(name) : node;
    }

    @CheckForNull
    public Node load(String name) {
        return load(name, false);
    }

    /**
     * @param vetoable whether {@link NodeListener#allowLoad} may veto the node, as at startup ({@link #load()}): a node
     *     vetoed is not loaded, and one of that name loaded before is dropped
     */
    @CheckForNull
    private Node load(String name, boolean vetoable) {
        try {
            XmlFile xmlFile = getConfigFile(name);
            if (xmlFile.checkExists()) {
                Node n = (Node) xmlFile.read();
                if (launchedElsewhere(n, new File(getRootDir(), name))) {
                    LOGGER.fine(() -> "not loading " + name + ", a cloud agent of another controller");
                    return null;
                }
                if (vetoable && !allowLoad(n)) {
                    Node old = nodes.get(n.getNodeName());
                    if (old != null) { // as if deleted: it would not be here after a restart either
                        unload(old);
                        jenkins.updateComputerList();
                        jenkins.trimLabels();
                        NodeListener.fireOnDeleted(old);
                    }
                    return null;
                }
                nodes.put(n.getNodeName(), n);
                n.onLoad(this, n.getNodeName());
                jenkins.updateNewComputer(n);
                jenkins.trimLabels(n);
                LOGGER.finer(() -> "loading " + xmlFile);
                return n;
            } else {
                LOGGER.fine(() -> "no such file " + xmlFile);
            }
        } catch (IOException e) {
            if (vetoable) { // a change made elsewhere: applied again later, not taken for the node being gone
                throw new UncheckedIOException("could not load " + name + ", changed elsewhere", e);
            }
            LOGGER.log(Level.WARNING, "could not load " + name, e);
        }
        return null;
    }

    private Node load(File dir, Map<String, Node> nodesCollector) throws IOException {
        Node n = (Node) getConfigFile(dir).read();
        if (n != null) {
            nodesCollector.put(n.getNodeName(), n);
            n.onLoad(this, n.getNodeName());
        }
        return n;
    }

    public void load(File dir) throws IOException {
        Node n = load(dir, nodes);
        jenkins.updateComputers(n);
        jenkins.trimLabels(n);
    }

    public void unload(Node node) {
        if (node == nodes.get(node.getNodeName())) {
            AtomicBoolean match = new AtomicBoolean();
            Queue.withLock(() -> match.set(node == nodes.remove(node.getNodeName())));
            if (match.get()) {
                jenkins.updateComputers(node);
                jenkins.trimLabels(node);
            }
        }
    }

    /**
     * Returns {@code true} if and only if the list of nodes is stored in the legacy location.
     *
     * @return {@code true} if and only if the list of nodes is stored in the legacy location.
     */
    public boolean isLegacy() {
        return !getRootDir().isDirectory();
    }

    @Override
    public File getRootDir() {
        return new File(jenkins.getRootDir(), "nodes");
    }

    public File getRootDirFor(Node node) {
        return getRootDirFor(node.getNodeName());
    }

    private File getRootDirFor(String name) {
        return new File(getRootDir(), name);
    }

    @Extension
    public static class ScheduleMaintenanceAfterSavingNode extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Node) {
                Jenkins.get().getQueue().scheduleMaintenance();
            }
        }
    }
}
