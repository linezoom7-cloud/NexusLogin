package dev.nexus.login.sync;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncCodecTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SERVER = "auth";

    private static SyncKeys keys(String... lines) {
        return SyncKeys.parse(String.join("\n", lines));
    }

    private static SyncMessage message(byte[] payload) {
        return new SyncMessage(0, 1_000_000L, SyncCodec.newNonce(), UUID.randomUUID(), UUID.randomUUID(),
                7, 3, SyncMessage.Op.STATE, payload);
    }

    private static SyncException.Reason rejectReason(byte[] data, SyncKeys keys, Direction d, String server) {
        return assertThrows(SyncException.class, () -> SyncCodec.decode(data, keys, d, server)).reason();
    }

    @Test
    void hkdfExpandMatchesRfc5869TestCase1() {
        byte[] prk = HEX.parseHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(SyncKeys.hkdfExpand(prk, info, 42)));
    }

    @Test
    void roundTripKeepsEveryField() throws Exception {
        SyncKeys k = keys(SyncKeys.generateLine(1, RANDOM));
        SyncMessage sent = message(new byte[]{1, 2, 3});
        SyncMessage got = SyncCodec.decode(SyncCodec.encode(sent, k, Direction.PROXY_TO_BACKEND, SERVER),
                k, Direction.PROXY_TO_BACKEND, SERVER);
        assertEquals(new SyncMessage(1, sent.timestampMs(), sent.nonce(), sent.playerUuid(), sent.connectionId(),
                sent.epoch(), sent.seq(), sent.op(), sent.payload()), got);
    }

    @Test
    void everySingleBitFlipIsRejected() {
        SyncKeys k = keys(SyncKeys.generateLine(1, RANDOM));
        byte[] data = SyncCodec.encode(message(new byte[]{9, 9}), k, Direction.PROXY_TO_BACKEND, SERVER);
        for (int i = 0; i < data.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] copy = data.clone();
                copy[i] ^= (byte) (1 << bit);
                assertThrows(SyncException.class, () -> SyncCodec.decode(copy, k, Direction.PROXY_TO_BACKEND, SERVER),
                        "bayt " + i + " bit " + bit + " değişince kabul edildi");
            }
        }
    }

    @Test
    void wrongKeyDirectionServerOrUnknownKeyIsRejected() {
        String line = SyncKeys.generateLine(1, RANDOM);
        SyncKeys k = keys(line);
        byte[] data = SyncCodec.encode(message(new byte[0]), k, Direction.PROXY_TO_BACKEND, SERVER);

        assertEquals(SyncException.Reason.BAD_SIGNATURE,
                rejectReason(data, keys(SyncKeys.generateLine(1, RANDOM)), Direction.PROXY_TO_BACKEND, SERVER));
        assertEquals(SyncException.Reason.WRONG_DIRECTION, rejectReason(data, k, Direction.BACKEND_TO_PROXY, SERVER));
        assertEquals(SyncException.Reason.BAD_SIGNATURE, rejectReason(data, k, Direction.PROXY_TO_BACKEND, "lobby"),
                "başka sunucuya yönelik mesaj tekrar oynatılamamalı");
        assertEquals(SyncException.Reason.UNKNOWN_KEY,
                rejectReason(data, keys(SyncKeys.generateLine(2, RANDOM)), Direction.PROXY_TO_BACKEND, SERVER));
    }

    @Test
    void reflectedMessageWithDirectionByteSwappedIsRejected() {
        SyncKeys k = keys(SyncKeys.generateLine(1, RANDOM));
        byte[] data = SyncCodec.encode(message(new byte[0]), k, Direction.BACKEND_TO_PROXY, SERVER);
        data[2] = Direction.PROXY_TO_BACKEND.wire; // yön baytını değiştir: farklı anahtar + imza kapsamı
        assertEquals(SyncException.Reason.BAD_SIGNATURE, rejectReason(data, k, Direction.PROXY_TO_BACKEND, SERVER));
    }

    @Test
    void malformedInputsAreRejectedWithoutExceptionsLeaking() {
        SyncKeys k = keys(SyncKeys.generateLine(1, RANDOM));
        byte[] data = SyncCodec.encode(message(new byte[]{1}), k, Direction.PROXY_TO_BACKEND, SERVER);

        assertEquals(SyncException.Reason.MALFORMED, rejectReason(null, k, Direction.PROXY_TO_BACKEND, SERVER));
        assertEquals(SyncException.Reason.MALFORMED, rejectReason(new byte[10], k, Direction.PROXY_TO_BACKEND, SERVER));
        assertEquals(SyncException.Reason.MALFORMED,
                rejectReason(java.util.Arrays.copyOf(data, data.length - 1), k, Direction.PROXY_TO_BACKEND, SERVER));
        assertEquals(SyncException.Reason.MALFORMED,
                rejectReason(java.util.Arrays.copyOf(data, data.length + 1), k, Direction.PROXY_TO_BACKEND, SERVER));
        byte[] wrongVersion = data.clone();
        wrongVersion[0] = 2;
        assertEquals(SyncException.Reason.UNSUPPORTED_VERSION, rejectReason(wrongVersion, k, Direction.PROXY_TO_BACKEND, SERVER));
        assertEquals(SyncException.Reason.MALFORMED,
                rejectReason(new byte[SyncCodec.MAX_PAYLOAD + 200], k, Direction.PROXY_TO_BACKEND, SERVER));
    }

    @Test
    void rotationSendsWithFirstKeyAndAcceptsOldKey() throws Exception {
        String oldLine = SyncKeys.generateLine(1, RANDOM);
        String newLine = SyncKeys.generateLine(2, RANDOM);
        SyncKeys oldOnly = keys(oldLine);
        SyncKeys rotated = keys(newLine, oldLine);

        assertEquals(2, rotated.activeKeyId());
        byte[] fromOld = SyncCodec.encode(message(new byte[0]), oldOnly, Direction.PROXY_TO_BACKEND, SERVER);
        assertEquals(1, SyncCodec.decode(fromOld, rotated, Direction.PROXY_TO_BACKEND, SERVER).keyId());
        byte[] fromNew = SyncCodec.encode(message(new byte[0]), rotated, Direction.PROXY_TO_BACKEND, SERVER);
        assertEquals(SyncException.Reason.UNKNOWN_KEY, rejectReason(fromNew, oldOnly, Direction.PROXY_TO_BACKEND, SERVER));
    }

    @Test
    void keyFileValidation() {
        assertThrows(IllegalArgumentException.class, () -> keys("# yalnız yorum"));
        assertThrows(IllegalArgumentException.class, () -> keys("1=" + java.util.Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class, () -> keys("0=" + java.util.Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> keys("abc=xyz"));
        assertThrows(IllegalArgumentException.class, () -> keys("1=!!!not-base64!!!"));
        String line = SyncKeys.generateLine(5, RANDOM);
        assertThrows(IllegalArgumentException.class, () -> keys(line, line), "aynı keyId iki kez");
        assertEquals(5, keys("# yorum", "", line).activeKeyId());
    }

    @Test
    void payloadLimitAndToStringHidesSecrets() {
        assertThrows(IllegalArgumentException.class, () -> message(new byte[SyncCodec.MAX_PAYLOAD + 1]));
        // Rastgele UUID'ler toString'de yer alır; o yüzden tek bir rakam değil, içeriğin kendisi aranır.
        SyncMessage m = message("SECRET-PAYLOAD".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String text = m.toString();
        assertFalse(text.contains("SECRET-PAYLOAD"), "payload toString'e girmemeli");
        assertFalse(text.toLowerCase().contains("payload") || text.contains("nonce"), "payload/nonce alanı yazılmamalı");
        assertFalse(text.contains(java.util.HexFormat.of().formatHex(m.nonce())), "nonce yazılmamalı");
    }
}
