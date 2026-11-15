package dev.nexus.login.sync;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import static dev.nexus.login.sync.SyncException.Reason.*;

/**
 * İmzası doğrulanmış bir mesajın taze ve tek seferlik olduğunu denetler:
 * zaman damgası ±{@link #WINDOW_MS}, nonce daha önce görülmemiş, {@code seq} bağlantı başına
 * kesin artan. Kapasite dolarsa fail-closed: yeni mesaj reddedilir.
 */
public final class ReplayGuard {

    public static final long WINDOW_MS = 5_000;
    static final int MAX_NONCES = 100_000;
    static final int MAX_CONNECTIONS = 20_000;

    private final LongSupplier clock;
    /** nonce -> son geçerlilik (ms); ekleme sırasıyla tutulur, en eski baştadır. */
    private final LinkedHashMap<ByteBuffer, Long> nonces = new LinkedHashMap<>();
    private final Map<UUID, Long> lastSeq = new HashMap<>();

    public ReplayGuard() {
        this(System::currentTimeMillis);
    }

    public ReplayGuard(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void check(SyncMessage message) throws SyncException {
        long now = clock.getAsLong();
        if (Math.abs(now - message.timestampMs()) > WINDOW_MS) {
            throw new SyncException(STALE_TIMESTAMP, "zaman damgası pencere dışında");
        }
        purgeExpired(now);
        ByteBuffer nonce = ByteBuffer.wrap(message.nonce());
        if (nonces.containsKey(nonce)) {
            throw new SyncException(REPLAYED_NONCE, "nonce tekrar");
        }
        Long previous = lastSeq.get(message.connectionId());
        if (previous != null && message.seq() <= previous) {
            throw new SyncException(OUT_OF_ORDER, "seq artmıyor");
        }
        if (nonces.size() >= MAX_NONCES || (previous == null && lastSeq.size() >= MAX_CONNECTIONS)) {
            throw new SyncException(CAPACITY, "replay kapasitesi dolu");
        }
        // Nonce, zaman penceresinin iki katı boyunca hatırlanır: pencere içindeki her tekrar yakalanır.
        nonces.put(nonce, now + 2 * WINDOW_MS);
        lastSeq.put(message.connectionId(), message.seq());
    }

    /** Bağlantı kapandı: sıra kaydı silinir (yeni bağlantı yeni connectionId alır). */
    public synchronized void forget(UUID connectionId) {
        lastSeq.remove(connectionId);
    }

    synchronized int trackedNonces() {
        purgeExpired(clock.getAsLong());
        return nonces.size();
    }

    private void purgeExpired(long now) {
        Iterator<Long> it = nonces.values().iterator();
        while (it.hasNext() && it.next() <= now) {
            it.remove();
        }
    }
}
