package dev.nexus.login.command;

import dev.nexus.login.auth.AuthService;
import dev.nexus.login.auth.SessionManager;
import dev.nexus.login.ui.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /giris (menüyü yeniden aç) ve /pin (PIN değiştir). */
public final class PlayerCommands implements CommandExecutor {

    private final AuthService auth;
    private final SessionManager sessions;
    private final Messages messages;

    public PlayerCommands(AuthService auth, SessionManager sessions, Messages messages) {
        this.auth = auth;
        this.sessions = sessions;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("command.player-only"));
            return true;
        }
        switch (command.getName()) {
            case "giris" -> {
                if (sessions.isRestricted(player)) {
                    auth.reopenGate(player);
                } else {
                    player.sendMessage(messages.get("command.already-logged-in"));
                }
            }
            case "pin" -> {
                if (sessions.isRestricted(player)) {
                    player.sendMessage(messages.get("command.must-login"));
                } else if (!auth.openChangeMenu(player)) {
                    player.sendMessage(messages.get("command.no-account"));
                }
            }
            case "2fa" -> auth.openTwoFactorMenu(player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
