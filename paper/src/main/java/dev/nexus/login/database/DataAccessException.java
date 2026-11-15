package dev.nexus.login.database;

/** Checked {@link java.sql.SQLException}'ı CompletableFuture zincirlerinde taşınabilir hale getirir. */
public final class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
