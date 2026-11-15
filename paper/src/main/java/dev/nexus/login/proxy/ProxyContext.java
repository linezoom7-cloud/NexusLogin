package dev.nexus.login.proxy;

import dev.nexus.login.sync.StatePayload;

import java.util.UUID;

/**
 * Proxy'den gelen doğrulanmış durum. AUTH_OK bu bağlantı kimliği ve epoch ile geri gönderilir.
 *
 * @param expiresAtMs AUTHED için proxy'nin verdiği son geçerlilik (ms); PENDING için 0
 */
public record ProxyContext(UUID connectionId, long epoch, StatePayload.AuthState state, long expiresAtMs,
                           boolean premium) {
}
