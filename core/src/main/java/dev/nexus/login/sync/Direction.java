package dev.nexus.login.sync;

/** Mesaj yönü. Her yön ayrı türetilmiş anahtarla imzalanır: bir yönün mesajı diğerine yansıtılamaz. */
public enum Direction {
    PROXY_TO_BACKEND((byte) 1, "p2b"),
    BACKEND_TO_PROXY((byte) 2, "b2p");

    final byte wire;
    final String label;

    Direction(byte wire, String label) {
        this.wire = wire;
        this.label = label;
    }
}
