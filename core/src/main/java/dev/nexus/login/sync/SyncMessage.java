package dev.nexus.login.sync;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Doğrulanmış (veya gönderilecek) tek bir sync mesajı.
 *
 * @param keyId        imzalayan anahtar (decode sonrası dolar; encode'da etkin anahtar kullanılır)
 * @param timestampMs  gönderenin saati (ReplayGuard ±pencere ile kontrol eder)
 * @param nonce        16 rastgele bayt, tekrar oynatmayı engeller
 * @param playerUuid   mesajın ilgili olduğu oyuncu
 * @param connectionId proxy'nin bu bağlantı için ürettiği kimlik; yeniden bağlanınca değişir
 * @param epoch        durum sürümü; eski epoch'lu push reddedilir
 * @param seq          bağlantı başına monoton artan sıra
 * @param op           işlem türü
 * @param payload      op'a özel içerik (en fazla {@link SyncCodec#MAX_PAYLOAD} bayt)
 */
public record SyncMessage(int keyId, long timestampMs, byte[] nonce, UUID playerUuid, UUID connectionId,
                          long epoch, long seq, Op op, byte[] payload) {

    public static final int NONCE_BYTES = 16;

    public SyncMessage {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(payload, "payload");
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce " + NONCE_BYTES + " bayt olmalı");
        }
        if (payload.length > SyncCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload çok büyük: " + payload.length);
        }
        nonce = nonce.clone();
        payload = payload.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SyncMessage m && keyId == m.keyId && timestampMs == m.timestampMs
                && epoch == m.epoch && seq == m.seq && op == m.op && Arrays.equals(nonce, m.nonce)
                && playerUuid.equals(m.playerUuid) && connectionId.equals(m.connectionId)
                && Arrays.equals(payload, m.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, timestampMs, Arrays.hashCode(nonce), playerUuid, connectionId, epoch, seq, op,
                Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        // payload ve nonce loglara düşmesin.
        return "SyncMessage[op=" + op + ", player=" + playerUuid + ", conn=" + connectionId + ", epoch=" + epoch
                + ", seq=" + seq + ", keyId=" + keyId + "]";
    }

    /** İşlem türleri. Wire değerleri sabittir; yenisi eklenirse sonuna eklenir. */
    public enum Op {
        /** Proxy → backend: oyuncunun durumu (PENDING / AUTHED / PENDING_2FA, payload'da). */
        STATE((byte) 1),
        /** Auth backend → proxy: PIN doğrulandı. */
        AUTH_OK((byte) 2),
        /** Proxy → backend: oturum iptal. */
        REVOKE((byte) 3),
        /** Proxy → backend: AUTHED durumunun süresini yenile. */
        HEARTBEAT((byte) 4);

        final byte wire;

        Op(byte wire) {
            this.wire = wire;
        }

        static Op fromWire(byte value) {
            for (Op op : values()) {
                if (op.wire == value) {
                    return op;
                }
            }
            return null;
        }
    }
}
