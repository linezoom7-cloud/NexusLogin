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
            new char[]{'1', '1', '2', '2', '3', '3'},
            new char[]{'1', '2', '3', '1', '2', '3'},
            new char[]{'1', '2', '3', '3', '2', '1'},
            new char[]{'1', '4', '7', '2', '5', '8'},
            new char[]{'1', '5', '9', '7', '5', '3'},
            new char[]{'1', '0', '2', '0', '3', '0'},
            new char[]{'1', '1', '1', '2', '2', '2'},
            new char[]{'7', '8', '9', '4', '5', '6'},
            new char[]{'1', '4', '7', '8', '5', '2'},
            new char[]{'9', '6', '3', '8', '5', '2'},
            new char[]{'1', '4', '2', '5', '3', '6'},
            new char[]{'1', '9', '8', '7', '0', '0'},
            new char[]{'2', '0', '0', '0', '0', '0'},
            new char[]{'1', '0', '0', '2', '0', '0'},
            new char[]{'1', '1', '2', '3', '5', '8'},
            new char[]{'1', '1', '2', '2', '3', '3', '4', '4'},
            new char[]{'1', '2', '3', '4', '1', '2', '3', '4'},
            new char[]{'1', '2', '3', '4', '4', '3', '2', '1'},
            new char[]{'2', '0', '2', '0', '2', '0', '2', '0'},
            new char[]{'1', '9', '9', '0', '1', '9', '9', '0'}
    );

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
