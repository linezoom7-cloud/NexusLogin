package dev.nexus.login.auth;

public enum LoginKind {
    PIN,
    REGISTER,
    PREMIUM,
    REMEMBERED,
    FORCED,
    /** Velocity arkasında: başka (auth) sunucuda doğrulandı, proxy imzalı AUTHED gönderdi. */
    PROXY
}
