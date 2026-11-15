package dev.nexus.login.sync;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import static dev.nexus.login.sync.SyncException.Reason.*;

/**
 * {@code nexuslogin:sync} kanalının ikili biçimi:
 *
 * <pre>
 * version:1 | keyId:1 | direction:1 | ts_ms:8 | nonce:16 | player:16 | conn:16 | epoch:8 | seq:8
 * | op:1 | payloadLen:2 | payload:n | hmac:32
 * </pre>
 *
 * HMAC-SHA256 kapsamı: {@code channel \0 server \0} + hmac öncesindeki tüm baytlar. Kanal ve hedef
 * sunucu adı imzaya girdiği için bir sunucuya giden mesaj başka sunucuda tekrar oynatılamaz.
 * İmza, alanlar yorumlanmadan önce sabit zamanlı karşılaştırılır.
 */
public final class SyncCodec {

    public static final String CHANNEL = "nexuslogin:sync";
    public static final byte VERSION = 1;
    public static final int MAX_PAYLOAD = 1024;

    private static final int HEADER = 1 + 1 + 1 + 8 + SyncMessage.NONCE_BYTES + 16 + 16 + 8 + 8 + 1 + 2;
    private static final int MAC_BYTES = 32;
    private static final int MIN_SIZE = HEADER + MAC_BYTES;
    private static final int MAX_SIZE = MIN_SIZE + MAX_PAYLOAD;
    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SyncCodec() {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[SyncMessage.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /** Mesajı etkin anahtarla imzalar. {@code message.keyId()} yok sayılır. */
    public static byte[] encode(SyncMessage message, SyncKeys keys, Direction direction, String server) {
        byte[] payload = message.payload();
        ByteBuffer buf = ByteBuffer.allocate(HEADER + payload.length + MAC_BYTES);
        buf.put(VERSION);
        buf.put((byte) keys.activeKeyId());
        buf.put(direction.wire);
        buf.putLong(message.timestampMs());
        buf.put(message.nonce());
        putUuid(buf, message.playerUuid());
        putUuid(buf, message.connectionId());
        buf.putLong(message.epoch());
        buf.putLong(message.seq());
        buf.put(message.op().wire);
        buf.putShort((short) payload.length);
        buf.put(payload);
        byte[] mac = mac(keys.activeKey(direction), server, buf.array(), buf.position());
        buf.put(mac);
        return buf.array();
    }

    /**
     * İmzayı ve biçimi doğrular. Zaman/nonce/sıra kontrolü {@link ReplayGuard}'dadır.
     *
     * @param expected bu tarafın kabul ettiği yön (backend PROXY_TO_BACKEND bekler)
     * @param server   bu mesajın hedeflendiği sunucu adı (backend kendi adını verir)
     */
    public static SyncMessage decode(byte[] data, SyncKeys keys, Direction expected, String server) throws SyncException {
        if (data == null || data.length < MIN_SIZE || data.length > MAX_SIZE) {
            throw new SyncException(MALFORMED, "boyut geçersiz");
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte version = buf.get();
        if (version != VERSION) {
            throw new SyncException(UNSUPPORTED_VERSION, "sürüm " + version);
        }
        int keyId = Byte.toUnsignedInt(buf.get());
        byte direction = buf.get();
        if (direction != expected.wire) {
            throw new SyncException(WRONG_DIRECTION, "yön uyuşmuyor");
        }
        int payloadLength = Short.toUnsignedInt(buf.getShort(HEADER - 2));
        if (payloadLength > MAX_PAYLOAD || data.length != HEADER + payloadLength + MAC_BYTES) {
            throw new SyncException(MALFORMED, "uzunluk alanı tutarsız");
        }
        byte[] key = keys.key(keyId, expected).orElseThrow(() -> new SyncException(UNKNOWN_KEY, "keyId " + keyId));
        int signedLength = data.length - MAC_BYTES;
        byte[] actual = mac(key, server, data, signedLength);
        byte[] given = java.util.Arrays.copyOfRange(data, signedLength, data.length);
        if (!MessageDigest.isEqual(actual, given)) {
            throw new SyncException(BAD_SIGNATURE, "imza geçersiz");
        }

        long ts = buf.getLong();
        byte[] nonce = new byte[SyncMessage.NONCE_BYTES];
        buf.get(nonce);
        UUID player = getUuid(buf);
        UUID connection = getUuid(buf);
        long epoch = buf.getLong();
        long seq = buf.getLong();
        SyncMessage.Op op = SyncMessage.Op.fromWire(buf.get());
        if (op == null) {
            throw new SyncException(UNKNOWN_OP, "bilinmeyen op");
        }
        buf.getShort();
        byte[] payload = new byte[payloadLength];
        buf.get(payload);
        return new SyncMessage(keyId, ts, nonce, player, connection, epoch, seq, op, payload);
    }

    private static byte[] mac(byte[] key, String server, byte[] data, int length) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            mac.update(CHANNEL.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(server.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(data, 0, length);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 kullanılamıyor", e);
        }
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buf) {
        return new UUID(buf.getLong(), buf.getLong());
    }
}
