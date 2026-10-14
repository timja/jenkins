/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

package hudson;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.DataHolder;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamDriver;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.diagnosis.OldDataMonitor;
import hudson.model.Descriptor;
import hudson.util.AtomicFileWriter;
import hudson.util.XStream2;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import jenkins.persistence.MaterializedFiles;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import org.apache.commons.io.IOUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Represents an XML data file that Jenkins uses as a data file.
 *
 *
 * <h2>Evolving data format</h2>
 * <p>
 * Changing data format requires a particular care so that users with
 * the old data format can migrate to the newer data format smoothly.
 *
 * <p>
 * Adding a field is the easiest. When you read an old XML that does
 * not have any data, the newly added field is left to the VM-default
 * value (if you let XStream create the object, such as
 * {@link #read()} &mdash; which is the majority), or to the value initialized by the
 * constructor (if the object is created via {@code new} and then its
 * value filled by XStream, such as {@link #unmarshal(Object)}.)
 *
 * <p>
 * Removing a field requires that you actually leave the field with
 * {@code transient} keyword. When you read the old XML, XStream
 * will set the value to this field. But when the data is saved,
 * the field will no longer will be written back to XML.
 * (It might be possible to tweak XStream so that we can simply
 * remove fields from the class. Any help appreciated.)
 *
 * <p>
 * Changing the data structure is usually a combination of the two
 * above. You'd leave the old data store with {@code transient},
 * and then add the new data. When you are reading the old XML,
 * only the old field will be set. When you are reading the new XML,
 * only the new field will be set. You'll then need to alter the code
 * so that it will be able to correctly handle both situations,
 * and that as soon as you see data in the old field, you'll have to convert
 * that into the new data structure, so that the next {@code save} operation
 * will write the new data (otherwise you'll end up losing the data, because
 * old fields will be never written back.)
 *
 * <p>
 * You may also want to call {@link OldDataMonitor#report(UnmarshallingContext, String)}.
 * This can be done within a nested class {@code ConverterImpl} extending {@link hudson.util.XStream2.PassthruConverter}
 * in an override of {@link hudson.util.XStream2.PassthruConverter#callback}.
 *
 * <p>
 * In some limited cases (specifically when the class is the root object
 * to be read from XML, such as {@link Descriptor}), it is possible
 * to completely and drastically change the data format. See
 * {@link Descriptor#load()} for more about this technique.
 *
 * <p>
 * There's a few other possibilities, such as implementing a custom
 * {@link Converter} for XStream, or {@link XStream#alias(String, Class) registering an alias}.
 *
 * @see <a href="https://www.jenkins.io/doc/developer/persistence/">Architecture » Persistence</a>
 * @author Kohsuke Kawaguchi
 */
public final class XmlFile {
    private final XStream xs;
    private final File file;
    private final boolean force;
    private static final Map<Object, Void> beingWritten = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<File> writing = new ThreadLocal<>();

    public XmlFile(File file) {
        this(DEFAULT_XSTREAM, file);
    }

    public XmlFile(XStream xs, File file) {
        this(xs, file, true);
    }

    /**
     * @param force Whether or not to flush the page cache to the storage device with {@link
     *     FileChannel#force} (i.e., {@code fsync}} or {@code FlushFileBuffers}) before this method
     *     returns. If you set this to {@code false}, you will lose data integrity.
     * @since 2.304
     */
    public XmlFile(XStream xs, File file, boolean force) {
        this.xs = xs;
        this.file = file;
        this.force = force;
    }

    /**
     * The file on the local filesystem.
     * If the content is served by a {@link PersistenceStore}, the current content is first copied to this file,
     * which then acts as a copy: changes made to it directly are written back to the store (merged with changes made
     * meanwhile), at the next {@code getFile()} and periodically.
     */
    public File getFile() {
        PersistenceStores.Route route = route();
        if (route != null) {
            // changed on disk since the last copy (Target 15): keep that change; and should writing it fail, keep the
            // copy as it is rather than replace it with the stored content
            if (MaterializedFiles.writeBack(route) != MaterializedFiles.Outcome.FAILED) {
                materialize(route);
            }
        }
        return file;
    }

    /**
     * The local path of this file, without copying the stored content to it (as {@link #getFile} does).
     * For code that only needs to know which file, or which store key, this is.
     */
    @Restricted(NoExternalUse.class)
    public File getPath() {
        return file;
    }

    public XStream getXStream() {
        return xs;
    }

    /**
     * Loads the contents of this file into a new object.
     */
    public Object read() throws IOException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Reading " + file);
        }
        try (InputStream in = new BufferedInputStream(open())) {
            return xs.fromXML(in);
        } catch (RuntimeException | Error e) {
            throw new IOException("Unable to read " + file, e);
        }
    }

    /**
     * Loads the contents of this file into an existing object.
     *
     * @return
     *      The unmarshalled object. Usually the same as {@code o}, but would be different
     *      if the XML representation is completely new.
     */
    public Object unmarshal(Object o) throws IOException {
        return unmarshal(o, false);
    }

    /**
     * Variant of {@link #unmarshal(Object)} applying {@link XStream2#unmarshal(HierarchicalStreamReader, Object, DataHolder, boolean)}.
     * @since 2.99
     */
    public Object unmarshalNullingOut(Object o) throws IOException {
        return unmarshal(o, true);
    }

    private Object unmarshal(Object o, boolean nullOut) throws IOException {
        PersistenceStores.declare(file, o);
        try (InputStream in = new BufferedInputStream(open())) {
            // TODO: expose XStream the driver from XStream
            if (nullOut) {
                return ((XStream2) xs).unmarshal(DEFAULT_DRIVER.createReader(in), o, null, true);
            } else {
                return xs.unmarshal(DEFAULT_DRIVER.createReader(in), o);
            }
        } catch (RuntimeException | Error e) {
            throw new IOException("Unable to read " + file, e);
        }
    }

    public void write(Object o) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, new Throwable(), () -> "Writing " + file);
        }
        PersistenceStores.declare(file, o);
        PersistenceStores.Route route = route();
        if (route != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (Writer w = new OutputStreamWriter(buf, StandardCharsets.UTF_8)) {
                w.write("<?xml version='1.1' encoding='UTF-8'?>\n");
                beingWritten.put(o, null);
                writing.set(file);
                try {
                    xs.toXML(o, w);
                } finally {
                    beingWritten.remove(o);
                    writing.set(null);
                }
            } catch (RuntimeException e) {
                throw new IOException(e);
            }
            mkdirs(); // the directory itself stays local: other files such as builds live alongside
            route.write(buf.toByteArray());
            return;
        }
        mkdirs();
        AtomicFileWriter w = force
                ? new AtomicFileWriter(file)
                : new AtomicFileWriter(file.toPath(), StandardCharsets.UTF_8, false, false);
        try {
            w.write("<?xml version='1.1' encoding='UTF-8'?>\n");
            beingWritten.put(o, null);
            writing.set(file);
            try {
                xs.toXML(o, w);
            } finally {
                beingWritten.remove(o);
                writing.set(null);
            }
            w.commit();
        } catch (RuntimeException e) {
            throw new IOException(e);
        } finally {
            w.abort();
        }
    }

    /**
     * Writes an already serialized XML document, for example one submitted by a user.
     *
     * @param content the complete document
     * @since TODO
     */
    @Restricted(Beta.class)
    public void writeRaw(byte[] content) throws IOException {
        PersistenceStores.Route route = route();
        if (route != null) {
            mkdirs();
            route.write(content);
            return;
        }
        mkdirs();
        // ISO-8859-1 maps every byte to one char and back, so the bytes are written unchanged
        AtomicFileWriter w = new AtomicFileWriter(file.toPath(), StandardCharsets.ISO_8859_1, force, force);
        try {
            w.write(new String(content, StandardCharsets.ISO_8859_1));
            w.commit();
        } finally {
            w.abort();
        }
    }

    /**
     * Reads the document as bytes, without interpreting its encoding.
     *
     * @since TODO
     */
    @Restricted(Beta.class)
    public byte[] readRawBytes() throws IOException {
        try (InputStream in = open(false)) {
            return in.readAllBytes();
        }
    }

    /**
     * Provides an XStream replacement for an object unless a call to {@link #write} is currently in progress.
     * As per JENKINS-45892 this may be used by any class which expects to be written at top level to an XML file
     * but which cannot safely be serialized as a nested object (for example, because it expects some {@code onLoad} hook):
     * implement a {@code writeReplace} method delegating to this method.
     * The replacement need not be {@link Serializable} since it is only necessary for use from XStream.
     * @param o an object ({@code this} from {@code writeReplace})
     * @param replacement a supplier of a safely serializable replacement object with a {@code readResolve} method
     * @return {@code o}, if {@link #write} is being called on it, else the replacement
     * @since 2.74
     */
    public static Object replaceIfNotAtTopLevel(Object o, Supplier<Object> replacement) {
        File currentlyWriting = writing.get();
        if (beingWritten.containsKey(o) || currentlyWriting == null) {
            return o;
        } else {
            LOGGER.log(Level.WARNING, "JENKINS-45892: reference to " + o + " being saved from unexpected " + currentlyWriting, new IllegalStateException());
            return replacement.get();
        }
    }

    public boolean exists() {
        try {
            return checkExists();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to check whether " + file + " exists", e);
            return false;
        }
    }

    /**
     * As {@link #exists()}, except that a {@link PersistenceStore} which cannot tell (being unreachable, say) throws,
     * rather than the file being taken for absent: for callers that would take that for a deletion.
     */
    @Restricted(NoExternalUse.class)
    public boolean checkExists() throws IOException {
        PersistenceStores.Route route = route();
        return route != null ? route.exists() : file.exists();
    }

    public void delete() throws IOException {
        PersistenceStores.Route route = route();
        if (route != null) {
            route.delete();
        }
        Files.deleteIfExists(Util.fileToPath(file));
    }

    public void mkdirs() throws IOException {
        Util.createDirectories(Util.fileToPath(file.getParentFile()));
    }

    @Override
    public String toString() {
        return file.toString();
    }

    /**
     * Opens a {@link Reader} that loads XML.
     * This method uses {@link #sniffEncoding() the right encoding},
     * not just the system default encoding.
     * @return Reader for the file. should be close externally once read.
     * @throws IOException Encoding issues
     */
    public Reader readRaw() throws IOException {
        try {
            InputStream fileInputStream = open(false);
            try {
                return new InputStreamReader(fileInputStream, sniffEncoding());
            } catch (IOException ex) {
                // Exception may happen if we fail to find encoding or if this encoding is unsupported.
                // In such case we close the underlying stream and rethrow.
                Util.closeAndLogFailures(fileInputStream, LOGGER, "FileInputStream", file.toString());
                throw ex;
            }
        } catch (InvalidPathException e) {
            throw new IOException(e);
        }
    }

    /**
     * Returns the XML file read as a string.
     */
    public String asString() throws IOException {
        StringWriter w = new StringWriter();
        writeRawTo(w);
        return w.toString();
    }

    /**
     * Writes the raw XML to the given {@link Writer}.
     * Writer will not be closed by the implementation.
     */
    public void writeRawTo(Writer w) throws IOException {
        try (Reader r = readRaw()) {
            IOUtils.copy(r, w);
        }
    }

    /**
     * Parses the beginning of the file and determines the encoding.
     *
     * @return
     *      always non-null.
     * @throws IOException
     *      if failed to detect encoding.
     */
    public String sniffEncoding() throws IOException {
        class Eureka extends SAXException {
            final String encoding;

            Eureka(String encoding) {
                this.encoding = encoding;
            }
        }

        try (InputStream in = open(false)) {
            InputSource input = new InputSource(file.toURI().toASCIIString());
            input.setByteStream(in);
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            spf.setNamespaceAware(true);
            spf.newSAXParser().parse(input, new DefaultHandler() {
                private Locator loc;
                @Override
                public void setDocumentLocator(Locator locator) {
                    this.loc = locator;
                }

                @Override
                public void startDocument() throws SAXException {
                    attempt();
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    attempt();
                    // if we still haven't found it at the first start element, then we are not going to find it.
                    throw new Eureka(null);
                }

                private void attempt() throws Eureka {
                    if (loc == null)   return;
                    if (loc instanceof Locator2 loc2) {
                        String e = loc2.getEncoding();
                        if (e != null)
                            throw new Eureka(e);
                    }
                }
            });
            // can't reach here
            throw new AssertionError();
        } catch (Eureka e) {
            if (e.encoding != null)
                return e.encoding;
            // the environment can contain old version of Xerces and others that do not support Locator2
            // in such a case, assume UTF-8 rather than fail, since Jenkins internally always write XML in UTF-8
            return "UTF-8";
        } catch (SAXException e) {
            throw new IOException("Failed to detect encoding of " + file, e);
        } catch (InvalidPathException e) {
            throw new IOException(e);
        } catch (ParserConfigurationException e) {
            throw new AssertionError(e);    // impossible
        }
    }

    /**
     * {@link XStream} instance is supposed to be thread-safe.
     */

    @CheckForNull
    private PersistenceStores.Route route() {
        return PersistenceStores.route(file);
    }

    /**
     * Opens the content, from the {@link PersistenceStore} if it serves this file, else from the local file.
     */
    private InputStream open() throws IOException {
        return open(true);
    }

    /**
     * @param loading whether the content is read into an object (this JVM then knows that version: a later write of
     *     the object expects it, and merges against it); other reads (raw text, a snapshot file) only look
     */
    private InputStream open(boolean loading) throws IOException {
        PersistenceStores.Route route = route();
        if (route == null) {
            return Files.newInputStream(file.toPath());
        }
        PersistenceStore.Entry entry = loading ? route.read() : route.peek();
        if (entry == null) {
            throw new NoSuchFileException(file.toString(), null, "not found in persistence store as " + route.key());
        }
        return new ByteArrayInputStream(entry.content());
    }

    /**
     * Copies the stored content to the local file, for callers of {@link #getFile} which read it directly.
     */
    private void materialize(PersistenceStores.Route route) {
        try {
            PersistenceStore.Entry entry = route.peek();
            if (entry == null) {
                MaterializedFiles.deletedElsewhere(route.key()); // a copy made earlier, of what is no longer stored
                return;
            }
            if (file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), entry.content())) {
                MaterializedFiles.materialized(route.key(), file, entry);
                return;
            }
            LOGGER.log(Level.FINE, new Throwable("caller"), () -> "Materializing " + route.key() + " to " + file);
            mkdirs();
            // replaced at once, never truncated in place: a reader of the file sees the old copy or the new one whole
            AtomicFileWriter w = new AtomicFileWriter(file.toPath(), StandardCharsets.ISO_8859_1);
            try {
                w.write(new String(entry.content(), StandardCharsets.ISO_8859_1));
                w.commit();
            } finally {
                w.abort();
            }
            MaterializedFiles.materialized(route.key(), file, entry);
        } catch (IOException | InvalidPathException e) {
            LOGGER.log(Level.WARNING, "Failed to materialize " + route.key() + " to " + file, e);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(XmlFile.class.getName());

    private static final HierarchicalStreamDriver DEFAULT_DRIVER = XStream2.getDefaultDriver();

    private static final XStream DEFAULT_XSTREAM = new XStream2(DEFAULT_DRIVER);
}
