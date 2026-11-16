package dev.nexus.login.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinPolicyTest {

    private final PinPolicy policy = new PinPolicy(6, 8);

    @ParameterizedTest
    @CsvSource({
            "12345,    TOO_SHORT",
            "123456789, TOO_LONG",
            "111111,   REPEATED",
            "121212,   REPEATED",
            "123123,   REPEATED",
            "123456,   SEQUENTIAL",
            "987654,   SEQUENTIAL",
            "890123,   SEQUENTIAL",
            "112233,   COMMON",
            "11223344, COMMON",
            "14725836, COMMON",
            "19871987, COMMON",
    })
    void rejectsWeakPins(String pin, PinPolicy.Violation expected) {
        assertEquals(Optional.of(expected), policy.check(pin.toCharArray()));
    }

    @Test
    void acceptsStrongPin() {
        assertEquals(Optional.empty(), policy.check("402719".toCharArray()));
        assertEquals(Optional.empty(), policy.check("58201937".toCharArray()));
    }

    @Test
    void keypadPatternsAndLongCommonListAreRejected() {
        PinPolicy nine = new PinPolicy(6, 9);
        assertEquals(Optional.of(PinPolicy.Violation.COMMON), nine.check("147258369".toCharArray()));
        assertEquals(Optional.of(PinPolicy.Violation.COMMON), nine.check("159357".toCharArray()));
        assertTrue(PinPolicy.commonCount() >= 50, "en az 50 yaygın PIN");
    }

    @Test
    void mixedDigitRuleIsOptIn() {
        PinPolicy mixed = new PinPolicy(8, 9, true);
        assertEquals(Optional.of(PinPolicy.Violation.FEW_DIGITS), mixed.check("11122233".toCharArray()));
        assertEquals(Optional.of(PinPolicy.Violation.FEW_DIGITS), mixed.check("90090099".toCharArray()));
        assertEquals(Optional.empty(), mixed.check("58201937".toCharArray()));
        assertEquals(Optional.empty(), mixed.check("51151520".toCharArray()), "tam 4 farklı rakam yeterli");
        assertEquals(Optional.empty(), new PinPolicy(8, 9).check("11122233".toCharArray()), "varsayılan: kapalı");
    }
}
