package dev.nexus.login.proxy;

import dev.nexus.login.sync.Direction;
import dev.nexus.login.sync.ReplayGuard;
import dev.nexus.login.sync.StatePayload;
import dev.nexus.login.sync.SyncCodec;
import dev.nexus.login.sync.SyncException;
import dev.nexus.login.sync.SyncKeys;
import dev.nexus.login.sync.SyncMessage;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy mesajlarının Bukkit'ten bağımsız doğrulaması ve AUTH_OK üretimi (testlenebilir).
 * Mesajı taşıyan bağlantının oyuncusu ile mesajın oyuncusu aynı olmalıdır.
 */
public final class ProxyInbound {

    /** Sonuç: kabul edilen durum ya da ret sebebi (log için). */
    public record Result(ProxyContext context, String rejection) {
        static Result ok(ProxyContext c) {
            return new Result(c, null);
        }

        static Result rejected(String why) {
            return new Result(null, why);
        }

        public boolean accepted() {
            return context != null;
        }
    }

    private final SyncKeys keys;
    private final String serverName;
    private final ReplayGuard guard;
    /** Proxy her bağlantı için seq'in artmasını ister; yeniden başlatmada da artsın diye saatle tohumlanır. */
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

    public ProxyInbound(SyncKeys keys, String serverName, ReplayGuard guard) {
        this.keys = keys;
        this.serverName = serverName;
        this.guard = guard;
    }

    public Result accept(byte[] data, UUID carrier) {
        SyncMessage message;
        try {
            message = SyncCodec.decode(data, keys, Direction.PROXY_TO_BACKEND, serverName);
            guard.check(message);
        } catch (SyncException e) {
            return Result.rejected(e.reason().name());
        }
        if (!message.playerUuid().equals(carrier)) {
            return Result.rejected("PLAYER_MISMATCH");
        }
        if (message.op() != SyncMessage.Op.STATE) {
            // REVOKE/HEARTBEAT şu an proxy tarafından gönderilmiyor; tanımadığımızı uygulamayız.
            return Result.rejected("UNSUPPORTED_OP_" + message.op());
        }
        StatePayload payload;
        try {
            payload = StatePayload.decode(message.payload());
        } catch (SyncException e) {
            return Result.rejected("BAD_STATE");
        }
        return Result.ok(new ProxyContext(message.connectionId(), message.epoch(), payload.state(),
                payload.expiresAtMs(), payload.premium()));
    }

    /** Auth sunucusunda PIN doğrulandıktan sonra proxy'ye gönderilecek imzalı AUTH_OK. */
    public byte[] authOk(UUID player, ProxyContext context) {
        SyncMessage ok = SyncMessage.outgoing(player, context.connectionId(), context.epoch(), seq.incrementAndGet(),
                SyncMessage.Op.AUTH_OK, new byte[0]);
        return SyncCodec.encode(ok, keys, Direction.BACKEND_TO_PROXY, serverName);
    }

    public void forget(UUID connectionId) {
        guard.forget(connectionId);
    }
}
