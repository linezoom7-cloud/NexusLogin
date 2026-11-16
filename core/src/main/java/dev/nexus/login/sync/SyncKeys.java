package dev.nexus.login.sync;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Proxy ile backend'ler arasındaki sync anahtarları. Velocity forwarding secret'ından BAĞIMSIZDIR:
 * secret sızsa bile saldırgan AUTHED mesajı üretemez.
 *
 * <p>Dosya biçimi ({@code nexuslogin-sync.key}): her satır {@code <keyId>=<base64 ana anahtar>}.
 * İlk satır gönderimde kullanılan etkin anahtardır; tüm satırlar alımda kabul edilir (kesintisiz
 * rotasyon). {@code #} ile başlayan satırlar yorumdur. Ana anahtar en az 32 bayttır.
 */
public final class SyncKeys {

    public static final int MIN_KEY_BYTES = 32;

    private final int activeKeyId;
    /** keyId -> yön -> türetilmiş HMAC anahtarı. */
    private final Map<Integer, Map<Direction, byte[]>> derived;

    private SyncKeys(int activeKeyId, Map<Integer, Map<Direction, byte[]>> derived) {
        this.activeKeyId = activeKeyId;
        this.derived = derived;
    }

    public static SyncKeys parse(String content) {
        Map<Integer, Map<Direction, byte[]>> keys = new LinkedHashMap<>();
        Integer active = null;
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("sync anahtar dosyası satır " + (i + 1) + ": '<keyId>=<base64>' bekleniyordu");
            }
            int keyId;
            try {
                keyId = Integer.parseInt(line.substring(0, eq).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("sync anahtar dosyası satır " + (i + 1) + ": keyId sayı olmalı");
            }
            if (keyId < 1 || keyId > 255) {
                throw new IllegalArgumentException("sync anahtar dosyası satır " + (i + 1) + ": keyId 1-255 arası olmalı");
            }
            byte[] master;
            try {
                master = Base64.getDecoder().decode(line.substring(eq + 1).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("sync anahtar dosyası satır " + (i + 1) + ": geçersiz base64");
            }
            if (master.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException("sync anahtar dosyası satır " + (i + 1) + ": anahtar en az "
                        + MIN_KEY_BYTES + " bayt olmalı (" + master.length + ")");
            }
            if (keys.containsKey(keyId)) {
                throw new IllegalArgumentException("sync anahtar dosyası: keyId " + keyId + " iki kez tanımlı");
            }
            Map<Direction, byte[]> perDirection = new LinkedHashMap<>();
            for (Direction direction : Direction.values()) {
                perDirection.put(direction, hkdfExpand(master, ("nexuslogin-sync-v1 " + direction.label).getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII), 32));
            }
            java.util.Arrays.fill(master, (byte) 0);
            keys.put(keyId, Collections.unmodifiableMap(perDirection));
            if (active == null) {
                active = keyId;
            }
        }
        if (active == null) {
            throw new IllegalArgumentException("sync anahtar dosyasında anahtar yok");
        }
        return new SyncKeys(active, Collections.unmodifiableMap(keys));
    }

    /** Yeni bir anahtar dosyası satırı üretir: {@code <keyId>=<32 rastgele bayt base64>}. */
    public static String generateLine(int keyId, SecureRandom random) {
        byte[] master = new byte[MIN_KEY_BYTES];
        random.nextBytes(master);
        return keyId + "=" + Base64.getEncoder().encodeToString(master);
    }

    public int activeKeyId() {
        return activeKeyId;
    }

    byte[] activeKey(Direction direction) {
        return derived.get(activeKeyId).get(direction);
    }

    Optional<byte[]> key(int keyId, Direction direction) {
        Map<Direction, byte[]> perDirection = derived.get(keyId);
        return perDirection == null ? Optional.empty() : Optional.of(perDirection.get(direction));
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        return dev.nexus.login.crypto.Hkdf.expand(prk, info, length);
    }
}
