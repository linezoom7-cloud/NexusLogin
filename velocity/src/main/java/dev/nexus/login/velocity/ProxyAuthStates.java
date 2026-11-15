package dev.nexus.login.velocity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy'deki tek doğruluk kaynağı: oyuncu başına auth durumu. Platform tipi içermez (testlenebilir).
 * Durum yalnızca {@link #start} ile oluşur; {@link #end} sonrası aynı bağlantı için geri gelmez
 * (kapı testi: DisconnectEvent, async PreConnect bitmeden gelebilir).
 */
final class ProxyAuthStates {

    enum State { PENDING, AUTHED }

    static final class Entry {
        final UUID player;
        final UUID connectionId;
        private final AtomicLong seq = new AtomicLong();
        private State state = State.PENDING;
        private long epoch = 1;
        private final boolean premium;

        private Entry(UUID player, UUID connectionId, boolean premium) {
            this.player = player;
            this.connectionId = connectionId;
            this.premium = premium;
        }

        synchronized State state() {
            return state;
        }

        synchronized long epoch() {
            return epoch;
        }

        boolean premium() {
            return premium;
        }

        long nextSeq() {
            return seq.incrementAndGet();
        }
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** Yeni bağlantı: her zaman PENDING ve yeni connectionId ile başlar (eski durum devralınmaz). */
    Entry start(UUID player, boolean premium) {
        Entry entry = new Entry(player, UUID.randomUUID(), premium);
        entries.put(player, entry);
        return entry;
    }

    Optional<Entry> get(UUID player) {
        return Optional.ofNullable(entries.get(player));
    }

    boolean isAuthed(UUID player) {
        Entry e = entries.get(player);
        return e != null && e.state() == State.AUTHED;
    }

    /**
     * Auth sunucusundan gelen AUTH_OK'yi uygular. Yalnızca güncel bağlantı ve epoch için, PENDING iken
     * kabul edilir; aksi halde false (eski/başka bağlantıya ait mesaj).
     */
    boolean acceptAuthOk(UUID player, UUID connectionId, long epoch) {
        Entry e = entries.get(player);
        if (e == null || !e.connectionId.equals(connectionId)) {
            return false;
        }
        synchronized (e) {
            if (e.state != State.PENDING || e.epoch != epoch) {
                return false;
            }
            e.state = State.AUTHED;
            e.epoch++;
            return true;
        }
    }

    /** Bağlantı kapandı. Yalnızca aynı bağlantının kaydı silinir (eski DisconnectEvent yeni oturumu silmesin). */
    Optional<Entry> end(UUID player, UUID connectionId) {
        Entry e = entries.get(player);
        if (e != null && e.connectionId.equals(connectionId) && entries.remove(player, e)) {
            return Optional.of(e);
        }
        return Optional.empty();
    }

    int size() {
        return entries.size();
    }
}
