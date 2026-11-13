package dev.nexus.login.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Yeni PIN'ler için kalite kuralları. PIN boyunca String'e çevrilmez; kontroller doğrudan
 * {@code char[]} üzerinde yapılır ki düz metin heap'te immutable bir kopya bırakmasın.
 */
public final class PinPolicy {

    public enum Violation {
        TOO_SHORT("pin.too-short"),
        TOO_LONG("pin.too-long"),
        NOT_NUMERIC("pin.not-numeric"),
        REPEATED("pin.repeated"),
        SEQUENTIAL("pin.sequential"),
        COMMON("pin.common");

        private final String messageKey;

        Violation(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /** Sızdırılmış PIN listelerinde en üst sıralarda yer alan, desen kurallarına takılmayan değerler. */
    private static final List<char[]> COMMON = List.of(
            "112233", "123123", "123321", "147258", "159753", "102030", "111222", "789456",
            "147852", "963852", "142536", "198700", "200000", "100200", "112358", "11223344",
            "12341234", "12344321", "20202020", "19901990"
    ).stream().map(String::toCharArray).toList();

    private final int minLength;
    private final int maxLength;

    public PinPolicy(int minLength, int maxLength) {
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public Optional<Violation> check(char[] pin) {
        if (pin.length < minLength) {
            return Optional.of(Violation.TOO_SHORT);
        }
        if (pin.length > maxLength) {
            return Optional.of(Violation.TOO_LONG);
        }
        for (char c : pin) {
            if (c < '0' || c > '9') {
                return Optional.of(Violation.NOT_NUMERIC);
            }
        }
        if (hasShortPeriod(pin)) {
            return Optional.of(Violation.REPEATED);
        }
        if (isSequential(pin)) {
            return Optional.of(Violation.SEQUENTIAL);
        }
        for (char[] common : COMMON) {
            if (Arrays.equals(common, pin)) {
                return Optional.of(Violation.COMMON);
            }
        }
        return Optional.empty();
    }

    /** 111111 (periyot 1), 121212 (periyot 2), 123123 (periyot 3). */
    private static boolean hasShortPeriod(char[] pin) {
        for (int period = 1; period <= 3 && period < pin.length; period++) {
            boolean repeats = true;
            for (int i = period; i < pin.length && repeats; i++) {
                repeats = pin[i] == pin[i - period];
            }
            if (repeats) {
                return true;
            }
        }
        return false;
    }

    /** 123456 / 987654 ve 0'dan 9'a dönen 890123 / 321098 gibi diziler. */
    private static boolean isSequential(char[] pin) {
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length; i++) {
            int diff = Math.floorMod(pin[i] - pin[i - 1], 10);
            ascending &= diff == 1;
            descending &= diff == 9;
        }
        return ascending || descending;
    }
}
