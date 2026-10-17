package jenkins.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.Secret;
import hudson.util.TextFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Default portable implementation of {@link ConfidentialStore} that uses
 * a directory inside $JENKINS_HOME.
 * <p>
 * The master key is stored by default in <code>$JENKINS_HOME/secrets/master.key</code> but another location can be provided using the system property <code>jenkins.security.DefaultConfidentialStore.file</code>.
 * <p>
 * It is also possible to prevent the generation of the master key file using the system property <code>jenkins.security.DefaultConfidentialStore.readOnly</code>.
 * In this case, the master key file must be provided or startup will fail.
 *
 * @author Kohsuke Kawaguchi
 */
// @MetaInfServices --- not annotated because this is the fallback implementation
public class DefaultConfidentialStore extends ConfidentialStore {

    private static final Logger LOGGER = Logger.getLogger(DefaultConfidentialStore.class.getName());
    static final String MASTER_KEY_FILE_SYSTEM_PROPERTY = DefaultConfidentialStore.class.getName() + ".file";
    static final String MASTER_KEY_READONLY_SYSTEM_PROPERTY_NAME = DefaultConfidentialStore.class.getName() + ".readOnly";

    private final SecureRandom sr = new SecureRandom();

    @NonNull
    private static File getMasterKeyFile(File rootDir) {
        var jenkinsMasterKey = SystemProperties.getString(MASTER_KEY_FILE_SYSTEM_PROPERTY);
        if (jenkinsMasterKey != null) {
            return new File(jenkinsMasterKey);
        } else {
            return new File(rootDir, "master.key");
        }
    }

    /**
     * Directory that stores individual keys.
     */
    private final File rootDir;

    /**
     * The master key.
     *
     * The sole purpose of the master key is to encrypt individual keys on the disk.
     * Because leaking this master key compromises all the individual keys, we must not let
     * this master key used for any other purpose, hence the protected access.
     */
    private final SecretKey masterKey;

    public DefaultConfidentialStore() throws IOException, InterruptedException {
        this(new File(Jenkins.get().getRootDir(), "secrets"));
    }

    public DefaultConfidentialStore(File rootDir) throws IOException, InterruptedException {
        this(rootDir, getMasterKeyFile(rootDir));
    }

    protected DefaultConfidentialStore(File rootDir, File keyFile) throws IOException, InterruptedException {
        this.rootDir = rootDir;
        if (rootDir.mkdirs()) {
            // protect this directory. but don't change the permission of the existing directory
            // in case the administrator changed this.
            new FilePath(rootDir).chmod(0700);
        }

        TextFile masterSecret = new TextFile(keyFile);
        if (!masterSecret.exists()) {
            if (SystemProperties.getBoolean(MASTER_KEY_READONLY_SYSTEM_PROPERTY_NAME)) {
                throw new IOException(masterSecret + " does not exist and system property " + MASTER_KEY_READONLY_SYSTEM_PROPERTY_NAME + " is set. You must provide a valid master key file.");
            } else {
                // we are only going to use small number of bits (since export control limits AES key length)
                // but let's generate a long enough key anyway
                masterSecret.write(Util.toHexString(randomBytes(128)));
            }
        }
        this.masterKey = Util.toAes128Key(masterSecret.readTrim());
    }

    /**
     * Persists the payload of {@link ConfidentialKey} to the disk.
     */
    @Override
    protected void store(ConfidentialKey key, byte[] payload) throws IOException {
        writeEncrypted(key, encrypt(key, payload));
    }

    private byte[] encrypt(ConfidentialKey key, byte[] payload) throws IOException {
        try {
            Cipher sym = Secret.getCipher("AES");
            sym.init(Cipher.ENCRYPT_MODE, masterKey);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (CipherOutputStream cos = new CipherOutputStream(bytes, sym)) {
                cos.write(payload);
                cos.write(MAGIC);
            }
            return bytes.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to persist the key: " + key.getId(), e);
        }
    }

    @CheckForNull
    private byte[] decrypt(ConfidentialKey key, byte[] encrypted) throws IOException {
        try {
            Cipher sym = Secret.getCipher("AES");
            sym.init(Cipher.DECRYPT_MODE, masterKey);
            try (CipherInputStream cis = new CipherInputStream(new ByteArrayInputStream(encrypted), sym)) {
                return verifyMagic(cis.readAllBytes());
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load the key: " + key.getId(), e);
        } catch (IOException x) {
            if (x.getCause() instanceof BadPaddingException || x.getCause() instanceof IllegalBlockSizeException) {
                return null; // broken somehow, e.g. encrypted with another master key, or truncated
            }
            throw x;
        }
    }

    private void writeEncrypted(ConfidentialKey key, byte[] encrypted) throws IOException {
        try {
            Files.write(getFileFor(key).toPath(), encrypted);
        } catch (InvalidPathException e) {
            throw new IOException(e);
        }
    }

    /**
     * Shares keys created at runtime with other controllers using the same {@link PersistenceStore}, when it
     * {@linkplain PersistenceStore#storesConfidentialKeys stores confidential keys} (the master key must be
     * the same on all of them, e.g. seeded): a key not found locally is looked up in the store as {@code secrets/<id>},
     * and a new one is created there with a create-only write, so that the first controller to use a key decides its value
     * for all of them. Keys used before the store is available stay local, as before.
     */
    @Override
    byte[] loadOrCreate(ConfidentialKey key, Generator generator) throws IOException {
        byte[] local = load(key);
        if (local != null) {
            return local;
        }
        byte[] temp = temporary.get(key.getId());
        if (temp != null) {
            return temp.clone(); // what this JVM uses already, whatever becomes of it in the store
        }
        PersistenceStore store = PersistenceStores.get();
        if (store == null || !store.storesConfidentialKeys()) {
            return super.loadOrCreate(key, generator);
        }
        String storeKey = SHARED_PREFIX + key.getId();
        PersistenceStore.Entry shared;
        try {
            shared = store.read(storeKey);
        } catch (IOException e) {
            // the store is unreachable and this key has never been used here: a temporary key, so that whatever needs
            // it (e.g. a page) works now; the same one for the rest of this JVM's life (its users keep it), and once the
            // store is back it is offered as the cluster's key, so that what was made with it stays readable (Target 14)
            synchronized (temporary) {
                byte[] value = temporary.get(key.getId());
                if (value == null) {
                    LOGGER.log(Level.WARNING, "The persistence store is unreachable: using a temporary " + key.getId(), e);
                    value = generator.generate();
                    temporary.put(key.getId(), value);
                    publishLater(key, 0);
                }
                return value.clone();
            }
        }
        if (shared == null) {
            byte[] value = generator.generate();
            byte[] encrypted = encrypt(key, value);
            try {
                store.write(storeKey, encrypted, PersistenceStore.ABSENT);
                writeEncrypted(key, encrypted);
                LOGGER.fine(() -> "Created " + key.getId() + " for all controllers");
                return value;
            } catch (ConflictException e) {
                shared = store.read(storeKey); // another controller created it first: use theirs
            }
        }
        byte[] value = shared == null ? null : decrypt(key, shared.content());
        if (value == null) {
            LOGGER.warning(() -> "Cannot decrypt " + key.getId() + " from the persistence store; is the master key the same on every controller?");
            return super.loadOrCreate(key, generator);
        }
        writeEncrypted(key, shared.content());
        return value;
    }

    static final String SHARED_PREFIX = "secrets/";

    /** Keys first used while the store was unreachable, by id, until they are written to it (or found made elsewhere). */
    private final Map<String, byte[]> temporary = new ConcurrentHashMap<>();

    /** Offers a temporary key to the store once it is reachable again, with a backoff, up to every five minutes. */
    private void publishLater(ConfidentialKey key, int attempt) {
        Timer.get().schedule(() -> {
            try {
                publishTemporary(key);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Still cannot offer the temporary " + key.getId() + " to the persistence store", e);
                publishLater(key, attempt + 1);
            }
        }, Math.min(300, 5L << Math.min(attempt, 6)), TimeUnit.SECONDS);
    }

    /**
     * Writes a temporary key to the store (create-only) and locally, so that it is the cluster's key from then on, also
     * after a restart. If another controller created the key meanwhile, this controller keeps using the temporary key
     * until it restarts (so that every use of the key here agrees), and the cluster's from then on: whatever this
     * controller made with the temporary key cannot be read with it then, nor elsewhere.
     */
    void publishTemporary(ConfidentialKey key) throws IOException {
        byte[] value = temporary.get(key.getId());
        PersistenceStore store = PersistenceStores.get();
        if (value == null || store == null) {
            return;
        }
        String storeKey = SHARED_PREFIX + key.getId();
        byte[] encrypted = encrypt(key, value);
        try {
            store.write(storeKey, encrypted, PersistenceStore.ABSENT);
            writeEncrypted(key, encrypted);
            LOGGER.info(() -> "Created " + key.getId() + " for all controllers from the temporary one used while the persistence store was unreachable");
        } catch (ConflictException e) {
            PersistenceStore.Entry shared = store.read(storeKey);
            if (shared == null) {
                throw new IOException(key.getId() + " was created and removed meanwhile; offering it again", e);
            }
            if (!Arrays.equals(decrypt(key, shared.content()), value)) {
                LOGGER.severe(() -> "Another controller created " + key.getId() + " while this one used a temporary one (the persistence store was unreachable): "
                        + "this controller keeps using it until it restarts, but what it made with it cannot be read elsewhere, nor here after a restart");
                // not written locally: key instances made here from now on would use the cluster's value, and those made
                // before the temporary one, so that they could not read each other's output; the restart copies it down
                return;
            }
            writeEncrypted(key, shared.content()); // the write went through, its answer was lost: kept locally all the same
        }
        temporary.remove(key.getId());
    }

    /**
     * Publishes keys created locally before the store was available (create-only), and warns about any which differ from the store's.
     */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    @Restricted(NoExternalUse.class)
    public static void reconcile() throws IOException {
        PersistenceStore store = PersistenceStores.get();
        if (store == null || !store.storesConfidentialKeys() || !(ConfidentialStore.get() instanceof DefaultConfidentialStore cs)) {
            return;
        }
        File[] files = cs.rootDir.listFiles((d, n) -> !n.equals("master.key") && !n.endsWith(".tmp"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                continue; // directories (e.g. filepath-filters.d) and links are not keys
            }
            byte[] encrypted = Files.readAllBytes(f.toPath());
            if (!cs.isKey(encrypted)) {
                // secrets/ holds other files too (e.g. the setup wizard's initial password, in plain text): they stay here
                LOGGER.fine(() -> f.getName() + " is not a confidential key; not sharing it");
                continue;
            }
            String storeKey = SHARED_PREFIX + f.getName();
            PersistenceStore.Entry shared = store.read(storeKey);
            if (shared == null) {
                try {
                    store.write(storeKey, encrypted, PersistenceStore.ABSENT);
                } catch (ConflictException e) {
                    shared = store.read(storeKey);
                }
            }
            if (shared != null && !Arrays.equals(shared.content(), encrypted)) {
                LOGGER.warning(() -> "Key " + f.getName() + " differs from the one other controllers use; seed the same secrets/ on every controller");
            }
        }
        // and keeps a local copy of every key the cluster has, so that a key first used here while the store is
        // unreachable (e.g. one signing pages) is still the cluster's, not a new one (Target 14)
        for (String name : store.children(SHARED_PREFIX.substring(0, SHARED_PREFIX.length() - 1))) {
            if (!isKeyName(name)) {
                LOGGER.warning(() -> "Not keeping a local copy of a key named " + name + " in the persistence store: not a key's name");
                continue;
            }
            Path f = cs.rootDir.toPath().resolve(name);
            if (Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
                continue; // a key of its own (reconciled above), or anything else, a link included: left alone
            }
            PersistenceStore.Entry shared = store.read(SHARED_PREFIX + name);
            if (shared == null || !cs.isKey(shared.content())) {
                continue;
            }
            // complete or not at all, and never through a link put there meanwhile: moved into place, not over anything
            Path tmp = Files.createTempFile(cs.rootDir.toPath(), "shared", ".tmp");
            try {
                Files.write(tmp, shared.content());
                Files.move(tmp, f);
            } catch (FileAlreadyExistsException e) {
                LOGGER.fine(() -> name + " appeared meanwhile; keeping it");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /**
     * Reverse operation of {@link #store(ConfidentialKey, byte[])}
     *
     * @return
     *      null the data has not been previously persisted.
     */
    @Override
    protected byte[] load(ConfidentialKey key) throws IOException {
        try {
            File f = getFileFor(key);
            if (!f.exists())    return null;

            Cipher sym = Secret.getCipher("AES");
            sym.init(Cipher.DECRYPT_MODE, masterKey);
            try (InputStream fis = Files.newInputStream(f.toPath());
                 CipherInputStream cis = new CipherInputStream(fis, sym)) {
                byte[] bytes = cis.readAllBytes();
                return verifyMagic(bytes);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load the key: " + key.getId(), e);
        } catch (InvalidPathException e) {
            throw new IOException(e);
        } catch (IOException x) {
            if (x.getCause() instanceof BadPaddingException) {
                return null; // broken somehow
            } else {
                throw x;
            }
        }
    }

    /**
     * Whether a name the store lists below {@code secrets/} is that of a key, a plain file name in {@code secrets/}: key
     * ids are made of class names and short names ({@code hudson.util.Secret}, {@code jenkins.model.Jenkins.crumbSalt}).
     * Nothing with a separator, nor {@code .} or {@code ..}, nor a file of the store's own.
     */
    static boolean isKeyName(String name) {
        return name.matches("[A-Za-z0-9_$][A-Za-z0-9_$.-]{0,254}") && !name.equals("master.key") && !name.endsWith(".tmp");
    }

    /** Whether a file's content is a key this store wrote: encrypted with the master key, with the trailer. */
    private boolean isKey(byte[] content) {
        try {
            Cipher sym = Secret.getCipher("AES");
            sym.init(Cipher.DECRYPT_MODE, masterKey);
            try (CipherInputStream cis = new CipherInputStream(new ByteArrayInputStream(content), sym)) {
                return verifyMagic(cis.readAllBytes()) != null;
            }
        } catch (GeneralSecurityException | IOException e) {
            return false;
        }
    }

    /**
     * Verifies that the given byte[] has the MAGIC trailer, to verify the integrity of the decryption process.
     */
    private byte[] verifyMagic(byte[] payload) {
        int payloadLen = payload.length - MAGIC.length;
        if (payloadLen < 0)   return null;    // obviously broken

        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[payloadLen + i] != MAGIC[i])
                return null;    // broken
        }
        byte[] truncated = new byte[payloadLen];
        System.arraycopy(payload, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private File getFileFor(ConfidentialKey key) {
        return new File(rootDir, key.getId());
    }

    @Override
    SecureRandom secureRandom() {
        return sr;
    }

    @Override
    public byte[] randomBytes(int size) {
        byte[] random = new byte[size];
        sr.nextBytes(random);
        return random;
    }

    private static final byte[] MAGIC = "::::MAGIC::::".getBytes(StandardCharsets.US_ASCII);
}
