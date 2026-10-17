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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryPersistenceStoreTest {

    @Test
    void changesAreDeliveredInTheOrderMade() throws Exception {
        InMemoryPersistenceStore store = new InMemoryPersistenceStore(key -> true);
        List<Long> delivered = new ArrayList<>();
        store.subscribe(e -> {
            synchronized (delivered) {
                delivered.add(e.version());
            }
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable writer = () -> {
            try {
                for (int i = 0; i < 500; i++) {
                    store.write("k", (Thread.currentThread().getName() + i).getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Thread a = new Thread(writer, "a");
        Thread b = new Thread(writer, "b");
        a.start();
        b.start();
        a.join();
        b.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1000, delivered.size());
        for (int i = 1; i < delivered.size(); i++) {
            assertTrue(delivered.get(i) > delivered.get(i - 1), "delivered out of order at " + i + ": " + delivered);
        }
    }

    @Test
    void aChangeMadeByAListenerIsDeliveredAfterTheOneItIsHandling() throws Exception {
        InMemoryPersistenceStore store = new InMemoryPersistenceStore(key -> true);
        store.subscribe(e -> {
            if (e.key().equals("k1")) {
                try {
                    store.write("k2", new byte[] {2}, PersistenceStore.ANY);
                } catch (ConflictException x) {
                    throw new AssertionError(x);
                }
            }
        });
        List<String> delivered = new ArrayList<>();
        store.subscribe(e -> delivered.add(e.key()));
        store.write("k1", new byte[] {1}, PersistenceStore.ANY);
        assertEquals(List.of("k1", "k2"), delivered);
    }

    @Test
    void aListenerFailingDoesNotStopTheOthersNorTheChange() throws Exception {
        InMemoryPersistenceStore store = new InMemoryPersistenceStore(key -> true);
        store.subscribe(e -> {
            throw new IllegalStateException("broken listener");
        });
        List<String> delivered = new ArrayList<>();
        store.subscribe(e -> delivered.add(e.key()));
        store.write("k1", new byte[] {1}, PersistenceStore.ANY);
        store.write("k2", new byte[] {2}, PersistenceStore.ANY);
        assertEquals(List.of("k1", "k2"), delivered);
    }

    @Test
    void deletingAnAbsentKeySucceedsWhateverTheVersion() throws Exception {
        InMemoryPersistenceStore store = new InMemoryPersistenceStore(key -> true);
        store.delete("never", 42);
        store.delete("never", PersistenceStore.ABSENT);
        store.delete("never", PersistenceStore.ANY);
    }
}
