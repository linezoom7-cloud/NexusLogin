package dev.nexus.login.velocity;

import dev.nexus.login.sync.SyncKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/** {@code nexuslogin-sync.key}: yoksa üretilir; aynı dosya her NexusLogin backend'ine kopyalanır. */
final class SyncKeyFile {

    static final String NAME = "nexuslogin-sync.key";

    private SyncKeyFile() {
    }

    /** @return dosya yeni üretildiyse true */
    static boolean ensureExists(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(NAME);
        if (Files.exists(file)) {
            return false;
        }
        String content = "# NexusLogin sync anahtarı. GİZLİ tutun; Velocity forwarding.secret'tan farklıdır.\n"
                + "# Bu dosyayı her NexusLogin backend'inin plugins/NexusLogin/ klasörüne kopyalayın.\n"
                + "# Rotasyon: yeni satırı EN ÜSTE ekleyin, eski satırı tüm sunucular güncellenince silin.\n"
                + SyncKeys.generateLine(1, new SecureRandom()) + "\n";
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException raced) {
            return false;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // Windows: POSIX izinleri yok; klasör izinleri yöneticiye kalır.
        }
        return true;
    }

    static SyncKeys load(Path dataDirectory) throws IOException {
        return SyncKeys.parse(Files.readString(dataDirectory.resolve(NAME), StandardCharsets.UTF_8));
    }
}
