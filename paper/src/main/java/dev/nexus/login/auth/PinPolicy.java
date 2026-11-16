package dev.nexus.login.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
        COMMON("pin.common"),
        FEW_DIGITS("pin.few-digits");

        private final String messageKey;

        Violation(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Sızdırılmış PIN listelerinde üst sıralarda yer alan veya tuş takımı deseni olan değerler
     * (147258369, 159357...). Desen kurallarına zaten takılanlar burada tekrar edilmez.
     */
    private static final List<char[]> COMMON = Stream.of(
            // 6 hane (min-length 6 kullanan sunucular için)
            "112233", "123321", "147258", "159753", "102030", "111222", "789456", "147852",
            "963852", "142536", "198700", "200000", "100200", "112358", "123654", "159357",
            "147369", "258456", "852456", "741852", "963741", "321654", "456123", "789123",
            "102938", "192837", "123987", "101112", "131415", "112211", "696900", "252525",
            // 8 hane
            "11223344", "12341234", "12344321", "20202020", "19901990", "10203040", "11112222", "12345679",
            "12348765", "13572468", "24681357", "14725836", "15975346", "19871987", "19861986", "19851985",
            "20002000", "20212021", "20232023", "20242024", "11221122", "12211221", "14789632", "78963214",
            "98765431", "11335577",
            // 9 hane
            "147258369", "159753852", "147852369", "123698745", "963852741", "741852963", "789456123", "321654987",
            "123454321",
            "")
            .filter(s -> !s.isEmpty()).map(String::toCharArray).toList();

    /** {@code require-mixed-digits} açıkken gereken en az farklı rakam sayısı. */
    public static final int MIN_DISTINCT_DIGITS = 4;

    private final int minLength;
    private final int maxLength;
    private final boolean requireMixedDigits;

    public PinPolicy(int minLength, int maxLength) {
        this(minLength, maxLength, false);
    }

    public PinPolicy(int minLength, int maxLength, boolean requireMixedDigits) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.requireMixedDigits = requireMixedDigits;
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
        if (requireMixedDigits && distinctDigits(pin) < MIN_DISTINCT_DIGITS) {
            return Optional.of(Violation.FEW_DIGITS);
        }
        return Optional.empty();
    }

    private static int distinctDigits(char[] pin) {
        boolean[] seen = new boolean[10];
        int count = 0;
        for (char c : pin) {
            if (!seen[c - '0']) {
                seen[c - '0'] = true;
                count++;
            }
        }
        return count;
    }

    /** Testler için: listedeki değer sayısı. */
    static int commonCount() {
        return COMMON.size();
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
