package dev.nexus.login.sync;

/** Reddedilen sync mesajı. {@link #reason()} log/metrik içindir; gönderene geri iletilmez. */
public final class SyncException extends Exception {

    public enum Reason {
        MALFORMED, UNSUPPORTED_VERSION, WRONG_DIRECTION, UNKNOWN_KEY, BAD_SIGNATURE, UNKNOWN_OP,
        STALE_TIMESTAMP, REPLAYED_NONCE, OUT_OF_ORDER, CAPACITY
    }

    private final Reason reason;

    SyncException(Reason reason, String message) {
        super(message, null, false, false); // yığın izi gerekmez; saldırgan bunu bol bol tetikleyebilir
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
