package dev.nexus.login.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    })
    void rejectsWeakPins(String pin, PinPolicy.Violation expected) {
        assertEquals(Optional.of(expected), policy.check(pin.toCharArray()));
    }

    @Test
    void acceptsStrongPin() {
        assertEquals(Optional.empty(), policy.check("402719".toCharArray()));
        assertEquals(Optional.empty(), policy.check("58201937".toCharArray()));
    }
}
