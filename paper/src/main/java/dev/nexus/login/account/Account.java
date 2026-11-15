package dev.nexus.login.account;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * nexus_accounts tablosundaki bir satır.
 *
 * @param passwordHash Argon2id PHC string'i ({@code $argon2id$v=19$m=...}); premium ve henüz
 *                     şifre belirlememiş hesaplarda {@code null}
 */
public record Account(
        long id,
        UUID uuid,
        String playerName,
        @Nullable String passwordHash,
        boolean premium,
        @Nullable String registrationIp,
        @Nullable String lastIp
) {

    public Account {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public Account withPasswordHash(String newHash) {
        return new Account(id, uuid, playerName, newHash, premium, registrationIp, lastIp);
    }

    public Account withPremium(boolean newPremium) {
        return new Account(id, uuid, playerName, passwordHash, newPremium, registrationIp, lastIp);
    }

    /** Hash ve IP'ler log dosyalarına asla düz yazılmaz. */
    @Override
    public String toString() {
        return "Account[id=%d, uuid=%s, name=%s, premium=%s, hasPassword=%s]"
                .formatted(id, uuid, playerName, premium, hasPassword());
    }
}
