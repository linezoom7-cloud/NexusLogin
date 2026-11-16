package dev.nexus.login.velocity;

import java.util.Locale;
import java.util.Set;

/** Doğrulanmamış oyuncu için proxy kuralları; saf mantık. */
final class GateRules {

    private GateRules() {
    }

    /**
     * @param current oyuncunun şu an bulunduğu sunucu; ilk bağlantıda null
     */
    static boolean mayConnect(boolean authed, String target, Set<String> authServers, String current) {
        if (authed) {
            return true;
        }
        // Doğrulanmamış oyuncu yalnızca İLK yerleşimde auth sunucusuna gider; auth sunucuları arasında
        // gezinmek her sunucunun ayrı hata sayacıyla deneme hakkını çoğaltırdı.
        return current == null && authServers.contains(target);
    }

    /**
     * İlk bağlantıda {@code failed} auth sunucusuna ulaşılamadıysa denenecek sıradaki auth sunucusu
     * (config sırasına göre, başa dönmeden; böylece döngü olmaz).
     */
    static java.util.Optional<String> nextAuthServer(Set<String> authServers, String failed) {
        boolean seen = false;
        for (String name : authServers) {
            if (seen) {
                return java.util.Optional.of(name);
            }
            seen = name.equals(failed);
        }
        return java.util.Optional.empty();
    }

    /**
     * Velocity komutu baştaki "/" olmadan verir; başa boşluk eklenerek çalıştırılabildiği için
     * normalize edilir. Namespace ("x:login") soyulmaz: yalnızca listede tam olarak geçen ad geçer.
     */
    static boolean mayRunCommand(boolean authed, String rawCommand, Set<String> allowed) {
        if (authed) {
            return true;
        }
        String command = rawCommand.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).strip();
        }
        int space = indexOfWhitespace(command);
        String label = (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
        return !label.isEmpty() && allowed.contains(label);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
