package dev.nexus.login.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ReplayGuardTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ReplayGuard guard = new ReplayGuard(now::get);
    private final UUID conn = UUID.randomUUID();

    private SyncMessage msg(long ts, long seq) {
        return msg(ts, seq, SyncCodec.newNonce(), conn);
    }

    private static SyncMessage msg(long ts, long seq, byte[] nonce, UUID conn) {
        return new SyncMessage(1, ts, nonce, UUID.randomUUID(), conn, 1, seq, SyncMessage.Op.HEARTBEAT, new byte[0]);
    }

    private SyncException.Reason reject(SyncMessage m) {
        return assertThrows(SyncException.class, () -> guard.check(m)).reason();
    }

    @Test
    void acceptsFreshIncreasingMessages() throws Exception {
        guard.check(msg(now.get(), 1));
        guard.check(msg(now.get() + 4_000, 2));
        guard.check(msg(now.get() - 4_000, 5));
    }

    @Test
    void rejectsStaleAndFutureTimestamps() {
        assertEquals(SyncException.Reason.STALE_TIMESTAMP, reject(msg(now.get() - ReplayGuard.WINDOW_MS - 1, 1)));
        assertEquals(SyncException.Reason.STALE_TIMESTAMP, reject(msg(now.get() + ReplayGuard.WINDOW_MS + 1, 1)));
    }

    @Test
    void rejectsReplayedNonceEvenOnAnotherConnection() throws Exception {
        byte[] nonce = SyncCodec.newNonce();
        guard.check(msg(now.get(), 1, nonce, conn));
        assertEquals(SyncException.Reason.REPLAYED_NONCE, reject(msg(now.get(), 2, nonce, conn)));
        assertEquals(SyncException.Reason.REPLAYED_NONCE, reject(msg(now.get(), 1, nonce, UUID.randomUUID())));
    }

    @Test
    void rejectsNonIncreasingSeqPerConnection() throws Exception {
        guard.check(msg(now.get(), 10));
        assertEquals(SyncException.Reason.OUT_OF_ORDER, reject(msg(now.get(), 10)));
        assertEquals(SyncException.Reason.OUT_OF_ORDER, reject(msg(now.get(), 9)));
        guard.check(msg(now.get(), 1, SyncCodec.newNonce(), UUID.randomUUID())); // başka bağlantı bağımsız
    }

    @Test
    void forgetResetsSequenceForThatConnection() throws Exception {
        guard.check(msg(now.get(), 10));
        guard.forget(conn);
        guard.check(msg(now.get(), 1));
    }

    @Test
    void rejectedMessageDoesNotConsumeNonceOrSeq() throws Exception {
        byte[] nonce = SyncCodec.newNonce();
        guard.check(msg(now.get(), 5));
        assertEquals(SyncException.Reason.OUT_OF_ORDER, reject(msg(now.get(), 4, nonce, conn)));
        guard.check(msg(now.get(), 6, nonce, conn)); // ret edilen mesajın nonce'u kaydedilmemiş olmalı
    }

    @Test
    void expiredNoncesArePurged() throws Exception {
        guard.check(msg(now.get(), 1));
        assertEquals(1, guard.trackedNonces());
        now.addAndGet(2 * ReplayGuard.WINDOW_MS + 1);
        assertEquals(0, guard.trackedNonces());
    }

    @Test
    void failsClosedWhenConnectionCapacityIsFull() throws Exception {
        for (int i = 0; i < ReplayGuard.MAX_CONNECTIONS; i++) {
            guard.check(msg(now.get(), 1, SyncCodec.newNonce(), UUID.randomUUID()));
        }
        assertEquals(SyncException.Reason.CAPACITY, reject(msg(now.get(), 1, SyncCodec.newNonce(), UUID.randomUUID())));
    }
}
