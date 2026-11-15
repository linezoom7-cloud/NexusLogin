package dev.nexus.login.velocity;

import java.util.Locale;
import java.util.Set;

/** Doğrulanmamış oyuncu için proxy kuralları; saf mantık. */
final class GateRules {

    private GateRules() {
    }

    static boolean mayConnect(boolean authed, String target, Set<String> authServers) {
        return authed || authServers.contains(target);
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
