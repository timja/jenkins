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

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern ENCODING = Pattern.compile("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*['\"]([^'\"]+)['\"]");

    /**
     * An item's configuration with its {@code itemUuid} set: put in after its root's start tag, any there before taken
     * out, and the rest left as it is, so that it is written once, as given (not saved again with the identity).
     *
     * @return null if that cannot be done to it (not configuration as Jenkins writes it); then it is saved again
     */
    @CheckForNull
    static byte[] withItemUuid(@NonNull byte[] xml, @NonNull String uuid) {
        try {
            Matcher m = ENCODING.matcher(new String(xml, 0, Math.min(xml.length, 200), StandardCharsets.ISO_8859_1));
            Charset cs = m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
            String s = new String(xml, cs);
            int i = 0;
            while (true) { // the prolog
                while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '\uFEFF')) {
                    i++;
                }
                int next;
                if (s.startsWith("<?", i)) {
                    next = after(s, "?>", i);
                } else if (s.startsWith("<!--", i)) {
                    next = after(s, "-->", i);
                } else if (s.startsWith("<!DOCTYPE", i)) {
                    int subset = s.indexOf('[', i);
                    int end = s.indexOf('>', i);
                    next = subset >= 0 && subset < end ? after(s, ">", after(s, "]", subset)) : after(s, ">", i);
                } else {
                    break;
                }
                if (next <= i) {
                    return null;
                }
                i = next;
            }
            if (!s.startsWith("<", i)) {
                return null;
            }
            int rootEnd = tagEnd(s, i);
            if (rootEnd < 0) {
                return null;
            }
            String element = "<itemUuid>" + uuid + "</itemUuid>";
            if (s.charAt(rootEnd - 1) == '/') { // <project/>
                int name = i + 1;
                while (name < rootEnd - 1 && !Character.isWhitespace(s.charAt(name))) {
                    name++;
                }
                String root = s.substring(i + 1, name);
                return (s.substring(0, rootEnd - 1) + ">" + element + "</" + root + ">" + s.substring(rootEnd + 1)).getBytes(cs);
            }
            // the root's own itemUuid elements: children at depth 1, not elements of that name further down
            List<int[]> old = new ArrayList<>();
            int depth = 1;
            int start = -1;
            int j = rootEnd + 1;
            while (depth > 0) {
                int lt = s.indexOf('<', j);
                if (lt < 0) {
                    return null;
                }
                if (s.startsWith("<!--", lt)) {
                    j = after(s, "-->", lt);
                } else if (s.startsWith("<![CDATA[", lt)) {
                    j = after(s, "]]>", lt);
                } else if (s.startsWith("<?", lt)) {
                    j = after(s, "?>", lt);
                } else if (s.startsWith("</", lt)) {
                    j = after(s, ">", lt);
                    depth--;
                    if (depth == 1 && start >= 0) {
                        old.add(new int[] {start, j});
                        start = -1;
                    }
                } else {
                    int end = tagEnd(s, lt);
                    if (end < 0) {
                        return null;
                    }
                    boolean empty = s.charAt(end - 1) == '/';
                    if (depth == 1 && (s.startsWith("<itemUuid>", lt) || s.startsWith("<itemUuid/>", lt) || s.startsWith("<itemUuid ", lt))) {
                        if (empty) {
                            old.add(new int[] {lt, end + 1});
                        } else {
                            start = lt;
                        }
                    }
                    if (!empty) {
                        depth++;
                    }
                    j = end + 1;
                }
                if (j <= lt) {
                    return null; // not well-formed
                }
            }
            StringBuilder b = new StringBuilder(s);
            for (int k = old.size() - 1; k >= 0; k--) {
                int from = old.get(k)[0];
                int line = from;
                while (line > 0 && (s.charAt(line - 1) == ' ' || s.charAt(line - 1) == '\t')) {
                    line--;
                }
                if (line > 0 && s.charAt(line - 1) == '\n') {
                    from = line - 1; // its line, with it
                }
                b.delete(from, old.get(k)[1]);
            }
            b.insert(rootEnd + 1, "\n  " + element);
            return b.toString().getBytes(cs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Just after the first {@code end} from {@code from}, or -1 if there is none. */
    private static int after(String s, String end, int from) {
        int at = from < 0 ? -1 : s.indexOf(end, from);
        return at < 0 ? -1 : at + end.length();
    }

    /** Where a start tag beginning at {@code lt} ends (its {@code >}), quoted attribute values skipped; -1 if it does not. */
    private static int tagEnd(String s, int lt) {
        char quote = 0;
        for (int k = lt + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return k;
            }
        }
        return -1;
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

        // copy config; a copy is another item: an identity of its own, not the source's from the copied configuration
        byte[] copied = srcConfigFile.readRawBytes();
        byte[] withUuid = result instanceof AbstractItem ? withItemUuid(copied, UUID.randomUUID().toString()) : copied;
        Items.getConfigFile(result).writeRaw(withUuid != null ? withUuid : copied);

        // reload from the new config
        final File rootDir = result.getRootDir();
        result = Items.callWhileUpdatingByXml(() -> (T) Items.load(parent, rootDir));
        result.onCopiedFrom(src);
        if (result instanceof AbstractItem copy && withUuid == null) {
            copy.newItemUuid(); // configuration this cannot put one into: saved again with it
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
            // a new item, whatever identity the posted configuration had: as for a copy (another controller of a cluster
            // may have been given the same configuration, and the item it made the same identity)
            byte[] withUuid = withItemUuid(buf.toByteArray(), UUID.randomUUID().toString());
            try {
                configXml.writeRaw(withUuid != null ? withUuid : buf.toByteArray());
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
            if (result instanceof AbstractItem created && withUuid == null) {
                created.newItemUuid(); // configuration this cannot put one into: saved again with it
                created.save();
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
