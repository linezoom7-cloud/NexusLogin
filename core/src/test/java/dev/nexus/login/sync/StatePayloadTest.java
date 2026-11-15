package dev.nexus.login.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatePayloadTest {

    @Test
    void roundTripAllStates() throws Exception {
        for (StatePayload.AuthState s : StatePayload.AuthState.values()) {
            StatePayload p = new StatePayload(s, 123_456_789L, s == StatePayload.AuthState.AUTHED);
            assertEquals(p, StatePayload.decode(p.encode()));
        }
    }

    @Test
    void rejectsWrongSizeUnknownStateAndBadBoolean() {
        byte[] ok = new StatePayload(StatePayload.AuthState.PENDING, 0, false).encode();
        assertThrows(SyncException.class, () -> StatePayload.decode(new byte[ok.length - 1]));
        assertThrows(SyncException.class, () -> StatePayload.decode(new byte[ok.length + 1]));
        byte[] badState = ok.clone();
        badState[0] = 9;
        assertThrows(SyncException.class, () -> StatePayload.decode(badState));
        byte[] badBool = ok.clone();
        badBool[ok.length - 1] = 2;
        assertThrows(SyncException.class, () -> StatePayload.decode(badBool));
    }
}
