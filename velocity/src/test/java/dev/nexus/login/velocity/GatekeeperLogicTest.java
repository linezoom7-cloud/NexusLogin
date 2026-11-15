package dev.nexus.login.velocity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GatekeeperLogicTest {

    private final ProxyAuthStates states = new ProxyAuthStates();
    private final UUID player = UUID.randomUUID();

    @Test
    void newConnectionStartsPendingAndAuthOkNeedsCurrentConnectionAndEpoch() {
        ProxyAuthStates.Entry e = states.start(player, false);
        assertFalse(states.isAuthed(player));

        assertFalse(states.acceptAuthOk(player, UUID.randomUUID(), 1), "başka connectionId");
        assertFalse(states.acceptAuthOk(player, e.connectionId, 2), "yanlış epoch");
        assertFalse(states.acceptAuthOk(UUID.randomUUID(), e.connectionId, 1), "başka oyuncu");
        assertFalse(states.isAuthed(player));

        assertTrue(states.acceptAuthOk(player, e.connectionId, 1));
        assertTrue(states.isAuthed(player));
        assertEquals(2, e.epoch(), "AUTHED sonrası epoch artar: eski PENDING push'ları geçersizleşir");
        assertFalse(states.acceptAuthOk(player, e.connectionId, 1), "aynı AUTH_OK ikinci kez uygulanmaz");
    }

    @Test
    void reconnectStartsOverAndOldConnectionCannotAuthenticateOrEndIt() {
        ProxyAuthStates.Entry first = states.start(player, false);
        assertTrue(states.acceptAuthOk(player, first.connectionId, 1));

        ProxyAuthStates.Entry second = states.start(player, false);
        assertFalse(states.isAuthed(player), "yeniden bağlanan oyuncu AUTHED devralmaz");
        assertFalse(states.acceptAuthOk(player, first.connectionId, 2), "eski bağlantının mesajı geçersiz");

        assertTrue(states.end(player, first.connectionId).isEmpty(), "eski DisconnectEvent yeni oturumu silmez");
        assertTrue(states.get(player).isPresent());
        assertTrue(states.end(player, second.connectionId).isPresent());
        assertTrue(states.get(player).isEmpty());
    }

    @Test
    void endedPlayerCannotBeAuthenticated() {
        ProxyAuthStates.Entry e = states.start(player, true);
        states.end(player, e.connectionId);
        assertFalse(states.acceptAuthOk(player, e.connectionId, 1));
        assertFalse(states.isAuthed(player));
    }

    @Test
    void sequenceNumbersStrictlyIncrease() {
        ProxyAuthStates.Entry e = states.start(player, false);
        long a = e.nextSeq();
        long b = e.nextSeq();
        assertTrue(b > a);
    }

    @Test
    void unauthenticatedPlayerMayOnlyReachAuthServers() {
        Set<String> auth = Set.of("auth", "auth2");
        assertTrue(GateRules.mayConnect(false, "auth", auth, null));
        assertTrue(GateRules.mayConnect(false, "auth2", auth, null));
        assertFalse(GateRules.mayConnect(false, "lobby", auth, null));
        assertFalse(GateRules.mayConnect(false, "AUTH", auth, null), "sunucu adları büyük/küçük harfe duyarlı");
        assertTrue(GateRules.mayConnect(true, "lobby", auth, "auth"));
    }

    @Test
    void unauthenticatedPlayerCannotHopBetweenAuthServers() {
        Set<String> auth = Set.of("auth", "auth2");
        assertFalse(GateRules.mayConnect(false, "auth2", auth, "auth"),
                "auth sunucuları arasında gezinmek ayrı sayaçlarla deneme hakkını çoğaltırdı");
        assertFalse(GateRules.mayConnect(false, "auth", auth, "auth"));
        assertTrue(GateRules.mayConnect(true, "auth2", auth, "lobby"), "doğrulanmış oyuncu serbest");
    }

    @Test
    void multipleAuthServersRequireSharedDatabaseAcknowledgement() {
        Properties two = new Properties();
        two.setProperty("auth-servers", "auth, auth2");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProxySettings.from(two));
        assertTrue(e.getMessage().contains("auth-servers-share-database"));

        two.setProperty("auth-servers-share-database", "true");
        assertEquals(Set.of("auth", "auth2"), ProxySettings.from(two).authServers());
        assertFalse(ProxySettings.from(new Properties()).authServersShareDatabase(), "varsayılan kapalı");
    }

    @Test
    void commandWhitelistIsNormalizedButExact() {
        Set<String> allowed = Set.of("login", "l");
        assertTrue(GateRules.mayRunCommand(false, "login 12345678", allowed));
        assertTrue(GateRules.mayRunCommand(false, "  LOGIN 1", allowed), "başta boşluk ve büyük harf");
        assertTrue(GateRules.mayRunCommand(false, "/l", allowed));
        assertTrue(GateRules.mayRunCommand(false, "login\t1", allowed));
        assertFalse(GateRules.mayRunCommand(false, "server lobby", allowed));
        assertFalse(GateRules.mayRunCommand(false, "loginx", allowed));
        assertFalse(GateRules.mayRunCommand(false, "evil:login", allowed), "namespace soyulmaz");
        assertFalse(GateRules.mayRunCommand(false, "", allowed));
        assertFalse(GateRules.mayRunCommand(false, "   ", allowed));
        assertTrue(GateRules.mayRunCommand(true, "server lobby", allowed));
    }

    @Test
    void settingsDefaultsAndValidation() {
        ProxySettings defaults = ProxySettings.from(new Properties());
        assertEquals(Set.of("auth"), defaults.authServers());
        assertTrue(defaults.allowedCommands().contains("login"));
        assertEquals(Duration.ofSeconds(45), defaults.authedTtl());

        Properties badHeartbeat = new Properties();
        badHeartbeat.setProperty("heartbeat-seconds", "45");
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.from(badHeartbeat));

        Properties noAuth = new Properties();
        noAuth.setProperty("auth-servers", " , ");
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.from(noAuth));

        Properties loop = new Properties();
        loop.setProperty("after-login-server", "auth");
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.from(loop));

        Properties upper = new Properties();
        upper.setProperty("allowed-commands", "Login, REG");
        assertEquals(Set.of("login", "reg"), ProxySettings.from(upper).allowedCommands());
    }
}
