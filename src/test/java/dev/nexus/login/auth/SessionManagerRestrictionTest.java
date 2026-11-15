package dev.nexus.login.auth;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-closed: oturumu olmayan gerçek oyuncu kısıtlıdır; NPC ve çıkış yapan oyuncu değildir. */
class SessionManagerRestrictionTest {

    private static Player player(UUID id, boolean npc) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hasMetadata" -> npc;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void playerWithoutSessionIsRestricted() {
        assertTrue(new SessionManager().isRestricted(player(UUID.randomUUID(), false)));
    }

    @Test
    void npcWithoutSessionIsNotRestricted() {
        assertFalse(new SessionManager().isRestricted(player(UUID.randomUUID(), true)));
    }

    @Test
    void sessionStateDecidesWhenASessionExists() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();
        AuthSession session = sessions.create(id, "1.2.3.4", Duration.ofMinutes(2));
        assertTrue(sessions.isRestricted(player(id, false)));

        session.state(AuthState.AUTHENTICATED);
        assertFalse(sessions.isRestricted(player(id, false)));
    }

    @Test
    void leavingPlayerIsNotRestrictedUntilHeJoinsAgain() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();
        sessions.create(id, "1.2.3.4", Duration.ofMinutes(2));
        sessions.remove(id);
        sessions.leaving(id);
        assertFalse(sessions.isRestricted(player(id, false)), "çıkış sırasındaki eklentiler engellenmemeli");

        sessions.create(id, "1.2.3.4", Duration.ofMinutes(2));
        sessions.remove(id);
        assertTrue(sessions.isRestricted(player(id, false)), "yeniden girişte işaret silinmeli");
    }
}
