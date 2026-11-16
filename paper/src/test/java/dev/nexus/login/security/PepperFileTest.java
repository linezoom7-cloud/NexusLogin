package dev.nexus.login.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PepperFileTest {

    private static final Logger LOG = Logger.getLogger("PepperFileTest");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CONFIGURED = "config-pepper-0123456789";

    @TempDir
    Path dir;

    /** nexus_meta satırının bellekteki karşılığı (ortak veritabanı). */
    private static final class FakeMeta implements PepperFile.Meta {
        String value;

        @Override
        public String get() {
            return value;
        }

        @Override
        public String putIfAbsent(String v) {
            if (value == null) {
                value = v;
            }
            return value;
        }
    }

    private String resolve(Path folder, String configured, boolean accounts, FakeMeta meta) throws Exception {
        return PepperFile.resolve(folder, configured, accounts, meta, RANDOM, LOG);
    }

    @Test
    void freshInstallGeneratesPepperAndReusesItAfterRestart() throws Exception {
        FakeMeta meta = new FakeMeta();
        String first = resolve(dir, "", false, meta);
        assertTrue(first.length() >= 40, "32 bayt base64");
        assertTrue(Files.exists(dir.resolve(PepperFile.FILE)));
        assertNotNull(meta.value);
        assertFalse(Files.readString(dir.resolve(PepperFile.FILE)).contains(meta.value), "parmak izi pepper değil");

        assertEquals(first, resolve(dir, "", true, meta), "restart: aynı pepper");
    }

    @Test
    void existingAccountsWithoutPepperAreLeftAlone() throws Exception {
        assertEquals("", resolve(dir, "", true, new FakeMeta()));
        assertFalse(Files.exists(dir.resolve(PepperFile.FILE)), "eski PIN'ler bozulmasın diye üretilmemeli");
    }

    @Test
    void configuredPepperWinsAndIsNeverStored() throws Exception {
        FakeMeta meta = new FakeMeta();
        assertEquals(CONFIGURED, resolve(dir, CONFIGURED, false, meta));
        assertNull(meta.value, "zayıf olabilecek config pepper'ının parmak izi yazılmaz");
        assertFalse(Files.exists(dir.resolve(PepperFile.FILE)));
    }

    @Test
    void lostOrDifferentPepperFailsClosed(@TempDir Path other) throws Exception {
        FakeMeta meta = new FakeMeta();
        resolve(dir, "", false, meta);

        // Dosya silindi / ikinci sunucuya kopyalanmadı.
        assertThrows(IllegalStateException.class, () -> resolve(other, "", true, meta));
        // İkinci sunucu kendi pepper'ını üretmişti (aynı anda açılış yarışı).
        Files.writeString(other.resolve(PepperFile.FILE), "another-pepper-abcdefghijklmnop\n");
        assertThrows(IllegalStateException.class, () -> resolve(other, "", true, meta));
        // config'e farklı bir pepper yazıldı.
        assertThrows(IllegalStateException.class, () -> resolve(dir, CONFIGURED, true, meta));
        // Doğru dosya kopyalanınca açılır.
        Files.copy(dir.resolve(PepperFile.FILE), other.resolve(PepperFile.FILE),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(resolve(dir, "", true, meta), resolve(other, "", true, meta));
    }

    @Test
    void pepperFileOnOldPepperlessDatabaseIsRefused() throws Exception {
        Files.writeString(dir.resolve(PepperFile.FILE), "copied-pepper-abcdefghijklmnop\n");
        assertThrows(IllegalStateException.class, () -> resolve(dir, "", true, new FakeMeta()));
        Files.writeString(dir.resolve(PepperFile.FILE), "short\n");
        assertThrows(java.io.IOException.class, () -> resolve(dir, "", false, new FakeMeta()));
    }
}
