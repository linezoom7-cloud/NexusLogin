package dev.nexus.login.auth;

import dev.nexus.login.account.Account;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Beni hatırla" ve ön-yükleme yaşam döngüsünün güvenlik kuralları.
 *
 * <p>Özellikle hesap silme (unregister) sonrası kick ile quit arasındaki yarışı kapsar:
 * {@code forget} çağrıldıktan sonra aynı oturumda yazılan "beni hatırla" kaydı yok sayılmalı,
 * aksi halde silinen hesap PIN'siz yeniden açılabilirdi.
 */
class SessionManagerTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void rememberIsBoundToIpAndSingleUse() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();

        sessions.remember(id, "1.2.3.4", TEN_MINUTES);

        assertFalse(sessions.consumeRemembered(id, "5.6.7.8"), "farklı IP hatırlanmamalı");
        assertFalse(sessions.consumeRemembered(id, "1.2.3.4"), "kayıt tek kullanımlık olmalı");
    }

    @Test
    void rememberWorksOnceForSameIp() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();

        sessions.remember(id, "1.2.3.4", TEN_MINUTES);

        assertTrue(sessions.consumeRemembered(id, "1.2.3.4"));
        assertFalse(sessions.consumeRemembered(id, "1.2.3.4"), "ikinci kez tüketilememeli");
    }

    @Test
    void zeroDurationDisablesRemember() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();

        sessions.remember(id, "1.2.3.4", Duration.ZERO);

        assertFalse(sessions.consumeRemembered(id, "1.2.3.4"),
                "session-remember-minutes: 0 iken kayıt oluşmamalı");
    }

    @Test
    void forgetBlocksRememberUntilANewSuccessfulLogin() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();
        Account account = new Account(1L, id, "Steve", "hash", false, "1.2.3.4", "1.2.3.4");

        sessions.remember(id, "1.2.3.4", TEN_MINUTES);
        sessions.preload(id, account);

        sessions.forget(id);

        assertTrue(sessions.takePreloaded(id).isEmpty(), "silinen hesabın ön-yüklemesi temizlenmeli");
        assertFalse(sessions.consumeRemembered(id, "1.2.3.4"), "silinen hesabın eski kaydı geçersiz olmalı");

        // Kick ile quit arasında remember tekrar çağrılabilir; engellenmeli.
        sessions.remember(id, "1.2.3.4", TEN_MINUTES);
        assertFalse(sessions.consumeRemembered(id, "1.2.3.4"),
                "forget sonrası yazılan kayıt yok sayılmalı");

        // Yeni bir başarılı giriş kaydı yeniden açar.
        sessions.allowRemember(id);
        sessions.remember(id, "1.2.3.4", TEN_MINUTES);
        assertTrue(sessions.consumeRemembered(id, "1.2.3.4"));
    }

    @Test
    void invalidateRememberedDropsOnlyThatPlayersRecord() {
        SessionManager sessions = new SessionManager();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        sessions.remember(first, "1.2.3.4", TEN_MINUTES);
        sessions.remember(second, "1.2.3.4", TEN_MINUTES);

        sessions.invalidateRemembered(first);

        assertFalse(sessions.consumeRemembered(first, "1.2.3.4"), "PIN değişince eski kayıt silinmeli");
        assertTrue(sessions.consumeRemembered(second, "1.2.3.4"), "diğer oyuncu etkilenmemeli");
    }

    @Test
    void sessionLifecycleStartsRestricted() {
        SessionManager sessions = new SessionManager();
        UUID id = UUID.randomUUID();

        AuthSession session = sessions.create(id, "1.2.3.4", Duration.ofMinutes(5));

        assertSame(session, sessions.get(id));
        assertTrue(session.isRestricted(), "yeni oturum doğrulanana kadar kısıtlı olmalı");
        assertEquals("1.2.3.4", session.ip());
        assertSame(session, sessions.remove(id));
        assertNull(sessions.get(id), "çıkışta oturum kaldırılmalı");
    }
}
