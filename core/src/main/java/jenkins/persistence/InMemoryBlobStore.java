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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * A {@link BlobStore} in memory, for tests.
 */
@Restricted(Beta.class)
public class InMemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> blobs = new TreeMap<>();

    private static boolean under(String key, String prefix) {
        return key.equals(prefix) || key.startsWith(prefix + "/");
    }

    @Override
    public void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException {
        byte[] bytes = content.readAllBytes();
        if (bytes.length != length) {
            throw new IOException("expected " + length + " bytes for " + key + " but got " + bytes.length);
        }
        synchronized (blobs) {
            blobs.put(key, bytes);
        }
    }

    @Override
    @CheckForNull
    public InputStream get(@NonNull String key) {
        synchronized (blobs) {
            byte[] bytes = blobs.get(key);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    @Override
    public void delete(@NonNull String prefix) {
        synchronized (blobs) {
            blobs.keySet().removeIf(k -> under(k, prefix));
        }
    }

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) {
        synchronized (blobs) {
            for (String key : Set.copyOf(blobs.keySet())) {
                if (under(key, fromPrefix)) {
                    blobs.put(toPrefix + key.substring(fromPrefix.length()), blobs.remove(key));
                }
            }
        }
    }

    /**
     * All keys, for assertions.
     */
    @NonNull
    public Set<String> keys() {
        synchronized (blobs) {
            return new TreeSet<>(blobs.keySet());
        }
    }

    /**
     * Removes everything, for tests.
     */
    public void clear() {
        synchronized (blobs) {
            blobs.clear();
        }
    }
}
