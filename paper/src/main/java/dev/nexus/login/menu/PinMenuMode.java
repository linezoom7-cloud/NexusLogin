package dev.nexus.login.menu;

public enum PinMenuMode {
    LOGIN("login", true),
    REGISTER("register", true),
    REGISTER_CONFIRM("register-confirm", true),
    CHANGE_CURRENT("change-current", false),
    CHANGE_NEW("change-new", false),
    CHANGE_CONFIRM("change-confirm", false),
    /** PIN doğru, 2FA kodu bekleniyor. */
    TOTP_VERIFY("totp-verify", true),
    /** Zorunlu grupta: giriş için önce 2FA kurulmalı. */
    TOTP_ENROLL_GATE("totp-enroll-gate", true),
    /** /2fa ile isteğe bağlı kurulum. */
    TOTP_ENROLL("totp-enroll", false),
    /** /2fa ile kapatma: geçerli kod ister. */
    TOTP_DISABLE("totp-disable", false);

    private final String messageKey;
    private final boolean gate;

    PinMenuMode(String messageKey, boolean gate) {
        this.messageKey = messageKey;
        this.gate = gate;
    }

    public String messageKey() {
        return "menu.mode." + messageKey;
    }

    /**
     * {@code true}: oyuncu bu menüyü geçmeden oyuna giremez (kapatılırsa yeniden açılır).
     * {@code false}: giriş yapmış oyuncunun isteğe bağlı menüsü.
     */
    public boolean isGate() {
        return gate;
    }

    /** 6 haneli doğrulama kodu girilen modlar (PIN değil). */
    public boolean isCode() {
        return this == TOTP_VERIFY || this == TOTP_ENROLL_GATE || this == TOTP_ENROLL || this == TOTP_DISABLE;
    }
}
