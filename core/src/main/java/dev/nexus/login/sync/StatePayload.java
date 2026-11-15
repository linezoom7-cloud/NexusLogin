package dev.nexus.login.sync;

import java.nio.ByteBuffer;

/**
 * {@link SyncMessage.Op#STATE} içeriği: {@code state:1 | expiresAtMs:8 | premium:1}.
 *
 * @param expiresAtMs AUTHED için proxy saatine göre son geçerlilik; PENDING için 0
 * @param premium     yalnızca proxy'nin Mojang doğrulaması ({@code Player#isOnlineMode}) sonucu
 */
public record StatePayload(AuthState state, long expiresAtMs, boolean premium) {

    private static final int SIZE = 1 + 8 + 1;

    public enum AuthState {
        PENDING((byte) 1), AUTHED((byte) 2),
        /** TOTP (1.2.0) için ayrıldı: PIN doğru, ikinci faktör bekleniyor. */
        PENDING_2FA((byte) 3);

        final byte wire;

        AuthState(byte wire) {
            this.wire = wire;
        }
    }

    public byte[] encode() {
        return ByteBuffer.allocate(SIZE).put(state.wire).putLong(expiresAtMs).put((byte) (premium ? 1 : 0)).array();
    }

    public static StatePayload decode(byte[] data) throws SyncException {
        if (data.length != SIZE) {
            throw new SyncException(SyncException.Reason.MALFORMED, "STATE boyutu");
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte stateByte = buf.get();
        AuthState state = null;
        for (AuthState s : AuthState.values()) {
            if (s.wire == stateByte) {
                state = s;
            }
        }
        long expires = buf.getLong();
        byte premium = buf.get();
        if (state == null || (premium != 0 && premium != 1)) {
            throw new SyncException(SyncException.Reason.MALFORMED, "STATE alanı");
        }
        return new StatePayload(state, expires, premium == 1);
    }
}
