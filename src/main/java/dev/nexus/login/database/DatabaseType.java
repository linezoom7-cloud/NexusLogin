package dev.nexus.login.database;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum DatabaseType {
    SQLITE("org.sqlite.JDBC"),
    MARIADB("org.mariadb.jdbc.Driver");

    private final String driverClassName;

    DatabaseType(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public static DatabaseType parse(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            String allowed = Arrays.stream(values()).map(t -> t.name()).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("database.type geçersiz: '" + raw + "'. İzin verilenler: " + allowed);
        }
    }
}
