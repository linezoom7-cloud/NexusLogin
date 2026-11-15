package dev.nexus.login.proxy;

import dev.nexus.login.config.VelocityBridgeSettings;
import dev.nexus.login.sync.Direction;
import dev.nexus.login.sync.ReplayGuard;
import dev.nexus.login.sync.StatePayload;
import dev.nexus.login.sync.SyncCodec;
import dev.nexus.login.sync.SyncKeys;
import dev.nexus.login.sync.SyncMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProxyBridgeLogicTest {

    private static final SyncKeys KEYS = SyncKeys.parse(SyncKeys.generateLine(1, new SecureRandom()));
    private final UUID player = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();

    private byte[] state(StatePayload.AuthState s, String server, UUID forPlayer, SyncMessage.Op op, long seq) {
        long expires = s == StatePayload.AuthState.AUTHED ? System.currentTimeMillis() + 45_000 : 0;
        SyncMessage m = SyncMessage.outgoing(forPlayer, connection, 3, seq, op, new StatePayload(s, expires, true).encode());
        return SyncCodec.encode(m, KEYS, Direction.PROXY_TO_BACKEND, server);
    }

    private ProxyInbound inbound(String server) {
        return new ProxyInbound(KEYS, server, new ReplayGuard());
    }

    @Test
    void acceptsSignedStateForTheCarryingPlayer() {
        ProxyInbound.Result r = inbound("auth").accept(state(StatePayload.AuthState.PENDING, "auth", player, SyncMessage.Op.STATE, 1), player);
        assertTrue(r.accepted(), r.rejection());
        assertEquals(new ProxyContext(connection, 3, StatePayload.AuthState.PENDING, 0, true), r.context());
    }

    @Test
    void rejectsOtherPlayerOtherServerReplayAndUnsupportedOps() {
        ProxyInbound in = inbound("auth");
        assertEquals("PLAYER_MISMATCH",
                in.accept(state(StatePayload.AuthState.AUTHED, "auth", UUID.randomUUID(), SyncMessage.Op.STATE, 1), player).rejection());
        assertEquals("BAD_SIGNATURE",
                in.accept(state(StatePayload.AuthState.AUTHED, "lobby", player, SyncMessage.Op.STATE, 2), player).rejection(),
                "lobby'ye imzalanmış mesaj auth'ta geçmez");
        byte[] once = state(StatePayload.AuthState.AUTHED, "auth", player, SyncMessage.Op.STATE, 3);
        assertTrue(in.accept(once, player).accepted());
        assertEquals("REPLAYED_NONCE", in.accept(once, player).rejection());
        assertEquals("UNSUPPORTED_OP_REVOKE",
                in.accept(state(StatePayload.AuthState.AUTHED, "auth", player, SyncMessage.Op.REVOKE, 4), player).rejection());
    }

    @Test
    void forgetLetsANewConnectionStartItsSequenceAgain() {
        ProxyInbound in = inbound("auth");
        assertTrue(in.accept(state(StatePayload.AuthState.PENDING, "auth", player, SyncMessage.Op.STATE, 5), player).accepted());
        assertEquals("OUT_OF_ORDER", in.accept(state(StatePayload.AuthState.PENDING, "auth", player, SyncMessage.Op.STATE, 1), player).rejection());
        in.forget(connection);
        assertTrue(in.accept(state(StatePayload.AuthState.PENDING, "auth", player, SyncMessage.Op.STATE, 1), player).accepted());
    }

    @Test
    void authOkIsVerifiableByTheProxyAndBoundToConnectionAndServer() throws Exception {
        ProxyInbound in = inbound("auth");
        ProxyContext ctx = new ProxyContext(connection, 7, StatePayload.AuthState.PENDING, 0, false);
        byte[] a = in.authOk(player, ctx);
        byte[] b = in.authOk(player, ctx);

        SyncMessage first = SyncCodec.decode(a, KEYS, Direction.BACKEND_TO_PROXY, "auth");
        SyncMessage second = SyncCodec.decode(b, KEYS, Direction.BACKEND_TO_PROXY, "auth");
        assertEquals(SyncMessage.Op.AUTH_OK, first.op());
        assertEquals(player, first.playerUuid());
        assertEquals(connection, first.connectionId());
        assertEquals(7, first.epoch());
        assertTrue(second.seq() > first.seq(), "seq artmalı");
        assertThrows(Exception.class, () -> SyncCodec.decode(a, KEYS, Direction.BACKEND_TO_PROXY, "lobby"));
        assertThrows(Exception.class, () -> SyncCodec.decode(a, KEYS, Direction.PROXY_TO_BACKEND, "auth"),
                "AUTH_OK proxy→backend yönünde yansıtılamaz");
    }

    @Test
    void settingsValidation() {
        assertFalse(VelocityBridgeSettings.from(null).enabled());
        assertDoesNotThrow(() -> new VelocityBridgeSettings(false, ""));
        assertThrows(IllegalArgumentException.class, () -> new VelocityBridgeSettings(true, ""));
        assertThrows(IllegalArgumentException.class, () -> new VelocityBridgeSettings(true, "bad name"));
        assertThrows(IllegalArgumentException.class, () -> new VelocityBridgeSettings(true, "x".repeat(65)));
        assertEquals("auth-1", new VelocityBridgeSettings(true, " auth-1 ").serverName());
    }

    @Test
    void auditRequiresModernForwardingAndRejectsLegacy(@TempDir Path root) throws Exception {
        assertFalse(ProxyAudit.problems(root).isEmpty(), "paper-global.yml yoksa doğrulanamaz");

        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-global.yml"),
                "proxies:\n  velocity:\n    enabled: false\n    secret: ''\n");
        assertEquals(2, ProxyAudit.problems(root).size(), "forwarding kapalı + secret boş");

        Files.writeString(root.resolve("config/paper-global.yml"),
                "proxies:\n  velocity:\n    enabled: true\n    secret: 'abcdefghijklmnop'\n");
        assertTrue(ProxyAudit.problems(root).isEmpty());

        Files.writeString(root.resolve("spigot.yml"), "settings:\n  bungeecord: true\n");
        assertEquals(1, ProxyAudit.problems(root).size(), "legacy forwarding reddedilir");
    }
}
