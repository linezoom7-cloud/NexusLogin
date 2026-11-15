package dev.nexus.login.menu;

public enum PinMenuMode {
    LOGIN("login", true),
    REGISTER("register", true),
    REGISTER_CONFIRM("register-confirm", true),
    CHANGE_CURRENT("change-current", false),
    CHANGE_NEW("change-new", false),
    CHANGE_CONFIRM("change-confirm", false);

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
     * {@code false}: giriş yapmış oyuncunun isteğe bağlı PIN değiştirme menüsü.
     */
    public boolean isGate() {
        return gate;
    }
}
