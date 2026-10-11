/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

package hudson.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.listeners.ItemListener;
import hudson.security.AccessControlled;
import hudson.util.CopyOnWriteMap;
import hudson.util.Function1;
import io.jenkins.servlet.ServletExceptionWrapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStores;
import jenkins.security.ExtendedReadRedaction;
import jenkins.util.xml.XMLUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.StaplerResponse2;
import org.springframework.security.access.AccessDeniedException;
import org.xml.sax.SAXException;

/**
 * Defines a bunch of static methods to be used as a "mix-in" for {@link ItemGroup}
 * implementations. Not meant for a consumption from outside {@link ItemGroup}s.
 *
 * @author Kohsuke Kawaguchi
 * @see ViewGroupMixIn
 */
public abstract class ItemGroupMixIn {
    /**
     * {@link ItemGroup} for which we are working.
     */
    private final ItemGroup parent;
    private final AccessControlled acl;

    protected ItemGroupMixIn(ItemGroup parent, AccessControlled acl) {
        this.parent = parent;
        this.acl = acl;
    }

    /*
    * Callback methods to be implemented by the ItemGroup implementation.
    */

    /**
     * Adds a newly created item to the parent.
     */
    protected abstract void add(TopLevelItem item);

    /**
     * Assigns the root directory for a prospective item.
     */
    protected abstract File getRootDirFor(String name);


/*
 * The rest is the methods that provide meat.
 */

    /**
     * Loads all the child {@link Item}s.
     *
     * @param modulesDir
     *      Directory that contains sub-directories for each child item.
     */
    public static <K, V extends Item> Map<K, V> loadChildren(ItemGroup parent, File modulesDir, Function1<? extends K, ? super V> key) {
        try {
            Util.createDirectories(modulesDir.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<File> subdirs = new ArrayList<>(Arrays.asList(modulesDir.listFiles(File::isDirectory)));
        Set<String> listed = new HashSet<>();
        subdirs.forEach(d -> listed.add(d.getName()));
        // items which so far exist only in a persistence store
        Set<String> stored;
        try {
            stored = PersistenceStores.storedItemNames(modulesDir, listed); // those listed here are loaded anyway
        } catch (IOException e) {
            Logger.getLogger(ItemGroupMixIn.class.getName()).log(Level.WARNING, "could not list stored items of " + modulesDir, e);
            stored = Set.of();
        }
        for (String name : stored) {
            File subdir = new File(modulesDir, name);
            if (!listed.contains(name)) {
                try {
                    Util.createDirectories(subdir.toPath());
                    subdirs.add(subdir);
                } catch (IOException e) {
                    // one item which cannot be made here does not keep the others from loading
                    Logger.getLogger(ItemGroupMixIn.class.getName()).log(Level.WARNING, "could not make a directory for stored item " + subdir, e);
                }
            }
        }
        CopyOnWriteMap.Tree<K, V> configurations = new CopyOnWriteMap.Tree<>();
        for (File subdir : subdirs) {
            try {
                // Try to retain the identity of an existing child object if we can.
                V item = (V) parent.getItem(subdir.getName());
                if (item == null) {
                    XmlFile xmlFile = Items.getConfigFile(subdir);
                    boolean exists;
                    try {
                        exists = xmlFile.checkExists();
                    } catch (IOException e) {
                        if (PersistenceStores.isApplyingRemoteChange()) {
                            // a folder reloaded for a change made elsewhere: that change fails, and is applied again later,
                            // rather than the child being dropped from the folder here for the store being unreachable
                            throw new UncheckedIOException(e);
                        }
                        Logger.getLogger(ItemGroupMixIn.class.getName()).log(Level.WARNING, "could not check for " + xmlFile, e);
                        continue;
                    }
                    if (exists) {
                        item = (V) Items.load(parent, subdir);
                    } else {
                        Logger.getLogger(ItemGroupMixIn.class.getName()).log(Level.WARNING, "could not find file " + xmlFile.getFile());
                        continue;
                    }
                } else {
                    item.onLoad(parent, subdir.getName());
                }
                configurations.put(key.call(item), item);
            } catch (Exception e) {
                if (e instanceof UncheckedIOException && PersistenceStores.isApplyingRemoteChange()) {
                    throw (UncheckedIOException) e; // the store unreachable: retried, as above
                }
                Logger.getLogger(ItemGroupMixIn.class.getName()).log(Level.WARNING, "could not load " + subdir, e);
            }
        }

        return configurations;
    }

    /**
     * {@link Item} → name function.
     */
    public static final Function1<String, Item> KEYED_BY_NAME = Item::getName;

    /**
     * Creates a {@link TopLevelItem} for example from the submission of the {@code /lib/hudson/newFromList/form} tag
     * or throws an exception if it fails.
     *
     * @since 2.475
     */
    public synchronized TopLevelItem createTopLevelItem(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        acl.checkPermission(Item.CREATE);

        TopLevelItem result;

        String requestContentType = req.getContentType();
        String mode = req.getParameter("mode");
        if (requestContentType == null
                && !(mode != null && mode.equals("copy")))
            throw new Failure("No Content-Type header set");

        boolean isXmlSubmission = requestContentType != null
            && (requestContentType.startsWith("application/xml")
                    || requestContentType.startsWith("text/xml"));

        String name = req.getParameter("name");
        if (name == null)
            throw new Failure("Query parameter 'name' is required");

        { // check if the name looks good
            Jenkins.checkGoodName(name);
            name = name.trim();
            if (parent.getItem(name) != null)
                throw new Failure(Messages.Hudson_JobAlreadyExists(name));
        }

        if (mode != null && mode.equals("copy")) {
            String from = req.getParameter("from");

            // resolve a name to Item
            Item src = Jenkins.get().getItem(from, parent);
            if (src == null) {
                if (Util.fixEmpty(from) == null)
                    throw new Failure("Specify which job to copy");
                else
                    throw new Failure("No such job: " + from);
            }
            if (!(src instanceof TopLevelItem))
                throw new Failure(from + " cannot be copied");

            result = copy((TopLevelItem) src, name);
        } else {
            if (isXmlSubmission) {
                result = createProjectFromXML(name, req.getInputStream());
                rsp.setStatus(HttpServletResponse.SC_OK);
                return result;
            } else {
                if (mode == null)
                    throw new Failure("No mode given");
                TopLevelItemDescriptor descriptor = Items.all().findByName(mode);
                if (descriptor == null) {
                    throw new Failure("No item type ‘" + mode + "’ is known");
                }
                descriptor.checkApplicableIn(parent);
                acl.getACL().checkCreatePermission(parent, descriptor);

                // create empty job and redirect to the project config screen
                result = createProject(descriptor, name, true);
            }
        }

        rsp.sendRedirect2(redirectAfterCreateItem(req, result));
        return result;
    }

    /**
     * @deprecated use {@link #createTopLevelItem(StaplerRequest2, StaplerResponse2)}
     */
    @Deprecated
    public synchronized TopLevelItem createTopLevelItem(StaplerRequest req, StaplerResponse rsp) throws IOException, javax.servlet.ServletException {
        try {
            return createTopLevelItem(StaplerRequest.toStaplerRequest2(req), StaplerResponse.toStaplerResponse2(rsp));
        } catch (ServletException e) {
            throw ServletExceptionWrapper.fromJakartaServletException(e);
        }
    }

    /**
     * Computes the redirection target URL for the newly created {@link TopLevelItem}.
     */
    protected String redirectAfterCreateItem(StaplerRequest2 req, TopLevelItem result) throws IOException {
        return req.getContextPath() + '/' + result.getUrl() + "configure";
    }

    /**
     * Copies an existing {@link TopLevelItem} to a new name.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends TopLevelItem> T copy(T src, String name) throws IOException {
        acl.checkPermission(Item.CREATE);
        src.checkPermission(Item.EXTENDED_READ);
        XmlFile srcConfigFile = Items.getConfigFile(src);
        if (!src.hasPermission(Item.CONFIGURE)) {
            final String originalConfigDotXml = srcConfigFile.asString();
            final String redactedConfigDotXml = ExtendedReadRedaction.applyAll(originalConfigDotXml);
            if (!originalConfigDotXml.equals(redactedConfigDotXml)) {
                // AccessDeniedException2 does not permit a custom message, and anyway redirecting the user to the login screen is obviously pointless.
                throw new AccessDeniedException(
                        Messages.ItemGroupMixIn_may_not_copy_as_it_contains_secrets_and_(
                                src.getFullName(),
                                Jenkins.getAuthentication2().getName(),
                                Item.PERMISSIONS.title,
                                Item.EXTENDED_READ.name,
                                Item.CONFIGURE.name));
            }
        }
        src.getDescriptor().checkApplicableIn(parent);
        acl.getACL().checkCreatePermission(parent, src.getDescriptor());
        Jenkins.checkGoodName(name);
        ItemListener.checkBeforeCopy(src, parent);

        T result = (T) createProject(src.getDescriptor(), name, false);

        // copy config
        Items.getConfigFile(result).writeRaw(srcConfigFile.readRawBytes());

        // reload from the new config
        final File rootDir = result.getRootDir();
        result = Items.callWhileUpdatingByXml(() -> (T) Items.load(parent, rootDir));
        result.onCopiedFrom(src);
        if (result instanceof AbstractItem copy) {
            copy.newItemUuid(); // a copy is another item (it had the source's from the copied configuration)
            copy.save();
        }

        add(result);
        ItemListener.fireOnCopied(src, result);
        Jenkins.get().rebuildDependencyGraphAsync();

        return result;
    }

    public synchronized TopLevelItem createProjectFromXML(String name, InputStream xml) throws IOException {
        acl.checkPermission(Item.CREATE);

        Jenkins.get().getProjectNamingStrategy().checkName(parent.getFullName(), name);
        Items.verifyItemDoesNotAlreadyExist(parent, name, null);
        Jenkins.checkGoodName(name);

        // place it as config.xml
        final File dir = getRootDirFor(name);
        final XmlFile configXml = Items.getConfigFile(dir);
        // in a persistence store, an item of that name this controller has not loaded is not replaced
        final PersistenceStores.Creation creation = PersistenceStores.creating(new File(dir, "config.xml"));
        // the directory of a stored item not loaded here (its builds) is not this creation's to delete
        final boolean existed = dir.exists();
        boolean success = false;
        try {
            Util.createDirectories(dir.toPath());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            XMLUtils.safeTransform(new StreamSource(xml), new StreamResult(buf));
            try {
                configXml.writeRaw(buf.toByteArray());
            } catch (ConflictException e) {
                throw alreadyExists(name);
            }

            // load it
            TopLevelItem result = Items.callWhileUpdatingByXml(() -> (TopLevelItem) Items.load(parent, dir));

            boolean hasCreatePermission = acl.getACL().hasCreatePermission2(Jenkins.getAuthentication2(), parent, result.getDescriptor());
            boolean applicableIn = result.getDescriptor().isApplicableIn(parent);

            success = hasCreatePermission && applicableIn;

            if (!hasCreatePermission) {
                throw new AccessDeniedException(Jenkins.getAuthentication2().getName() + " does not have required permissions to create " + result.getDescriptor().clazz.getName());
            }
            if (!applicableIn) {
                throw new AccessDeniedException(result.getDescriptor().clazz.getName() + " is not applicable in " + parent.getFullName());
            }

            add(result);

            result.onCreatedFromScratch();
            ItemListener.fireOnCreated(result);
            Jenkins.get().rebuildDependencyGraphAsync();

            return result;
        } catch (TransformerException | SAXException e) {
            success = false;
            throw new IOException("Failed to persist config.xml", e);
        } catch (IOException | RuntimeException e) {
            success = false;
            throw e;
        } finally {
            if (creation != null) {
                creation.close();
            }
            if (!success) {
                // if anything fails, delete the config file to avoid further confusion
                // (in a persistence store, only what was written here)
                if (creation != null) {
                    creation.undo();
                }
                if (!existed || creation == null || !creation.wroteNothing()) {
                    Util.deleteRecursive(dir);
                }
            }
        }
    }

    /** Something of that name is stored, by another controller sharing the persistence store or not loaded here. */
    private static Failure alreadyExists(String name) {
        return new Failure(Messages.Hudson_JobAlreadyExists(name));
    }

    @NonNull
    public synchronized TopLevelItem createProject(@NonNull TopLevelItemDescriptor type, @NonNull String name, boolean notify)
            throws IOException {
        acl.checkPermission(Item.CREATE);
        type.checkApplicableIn(parent);
        acl.getACL().checkCreatePermission(parent, type);

        Jenkins.get().getProjectNamingStrategy().checkName(parent.getFullName(), name);
        Items.verifyItemDoesNotAlreadyExist(parent, name, null);
        Jenkins.checkGoodName(name);

        TopLevelItem item = type.newInstance(parent, name);
        item.onCreatedFromScratch();
        try (PersistenceStores.Creation creation = PersistenceStores.creating(new File(item.getRootDir(), "config.xml"))) {
            item.save();
        } catch (ConflictException e) {
            throw alreadyExists(name);
        }
        add(item);
        Jenkins.get().rebuildDependencyGraphAsync();

        if (notify)
            ItemListener.fireOnCreated(item);

        return item;
    }

}
