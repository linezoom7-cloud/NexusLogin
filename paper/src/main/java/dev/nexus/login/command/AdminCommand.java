package dev.nexus.login.command;

import dev.nexus.login.account.Account;
import dev.nexus.login.account.AccountRepository;
import dev.nexus.login.auth.AuthAudit;
import dev.nexus.login.auth.AuthService;
import dev.nexus.login.auth.SessionManager;
import dev.nexus.login.concurrent.NexusExecutors;
import dev.nexus.login.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** /nexus info | unregister | resetpass | unlock | unlockip | forcelogin | reload — yetki: nexuslogin.admin */
public final class AdminCommand implements TabExecutor {

    /** Başarılı/başarısız reload sonucu. */
    public record ReloadOutcome(boolean success, boolean restartRequired, @Nullable String error) {
        public static ReloadOutcome ok(boolean restartRequired) {
            return new ReloadOutcome(true, restartRequired, null);
        }

        public static ReloadOutcome failed(String error) {
            return new ReloadOutcome(false, false, error);
        }
    }

    /** {@code /nexus reload}: config'i doğrular, uygulanabilenleri canlı uygular. */
    @FunctionalInterface
    public interface ReloadHandler {
        ReloadOutcome reload();
    }

    private static final List<String> SUBCOMMANDS =
            List.of("info", "unregister", "resetpass", "unlock", "unlockip", "forcelogin", "2fa-reset", "reload");

    private volatile @Nullable AccountRepository accounts;
    private volatile @Nullable AuthService auth;
    private volatile @Nullable SessionManager sessions;
    private volatile @Nullable NexusExecutors executors;
    private volatile @Nullable Messages messages;
    private volatile @Nullable AuthAudit audit;

    private volatile @Nullable ReloadHandler reloadHandler;

    public AdminCommand(@Nullable AccountRepository accounts, @Nullable AuthService auth,
                        @Nullable SessionManager sessions, @Nullable NexusExecutors executors,
                        @Nullable Messages messages, @Nullable AuthAudit audit) {
        this.accounts = accounts;
        this.auth = auth;
        this.sessions = sessions;
        this.executors = executors;
        this.messages = messages;
        this.audit = audit;
    }

    public void bind(AccountRepository accounts, AuthService auth, SessionManager sessions,
                     NexusExecutors executors, Messages messages, AuthAudit audit) {
        this.accounts = accounts;
        this.auth = auth;
        this.sessions = sessions;
        this.executors = executors;
        this.messages = messages;
        this.audit = audit;
    }

    public void setReloadHandler(@Nullable ReloadHandler reloadHandler) {
        this.reloadHandler = reloadHandler;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            handleReload(sender);
            return true;
        }
        if (accounts == null || auth == null || messages == null) {
            sender.sendMessage(Component.text("[NexusLogin] Eklenti kilitli veya henüz başlatılamadı. Yalnızca /nexus reload çalıştırılabilir.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2 || !SUBCOMMANDS.contains(sub)) {
            messages.list("command.admin.usage").forEach(sender::sendMessage);
            return true;
        }
        String target = args[1];
        TagResolver player = Placeholder.unparsed("player", target);
        switch (sub) {
            case "info" -> withAccount(sender, target, account -> {
                audit.admin(sender.getName(), "info", account.playerName());
                messages.list("command.admin.info",
                        Placeholder.unparsed("player", account.playerName()),
                        Placeholder.unparsed("uuid", account.uuid().toString()),
                        Placeholder.unparsed("premium", flag(account.premium(), "common.yes", "common.no")),
                        Placeholder.unparsed("has_pin", flag(account.hasPassword(), "common.present", "common.absent")),
                        Placeholder.unparsed("reg_ip", ipOrNone(account.registrationIp())),
                        Placeholder.unparsed("last_ip", ipOrNone(account.lastIp())))
                        .forEach(sender::sendMessage);
            });
            case "unregister", "resetpass" -> accounts.findByName(target)
                    .thenCompose(found -> found.isEmpty()
                            ? CompletableFuture.completedFuture(DeleteOutcome.missing())
                            : accounts.delete(found.get().uuid())
                                    .thenApply(ok -> new DeleteOutcome(Optional.of(found.get()), ok)))
                    .whenCompleteAsync((outcome, error) -> {
                        if (error != null) {
                            sender.sendMessage(messages.get("command.admin.error",
                                    Placeholder.unparsed("error", errorMessage(error))));
                        } else if (outcome.account().isEmpty()) {
                            sender.sendMessage(messages.get("command.admin.not-found", player));
                        } else if (!outcome.deleted()) {
                            // delete() false döndüyse hesap hâlâ duruyor; başarı mesajı verme.
                            sender.sendMessage(messages.get("command.admin.delete-failed", player));
                        } else {
                            Account deleted = outcome.account().get();
                            Player online = Bukkit.getPlayer(deleted.uuid());
                            if (online != null) {
                                online.kick(messages.kick("kick.unregistered"));
                            }
                            // Kick sonrası: çıkışta yazılan "beni hatırla" kaydı da silinsin.
                            sessions.forget(deleted.uuid());
                            // Silinen hesabın kalıcı cezası yeni kayda taşınmasın. Hesabın
                            // bilinen IP cezaları da temizlenir; yoksa oyuncu kilitli kalır.
                            auth.clearThrottle(deleted);
                            // Yeni hesap eski hesabın 2FA'sını (ve onu kuranın uygulamasını) devralmasın.
                            auth.resetTwoFactor(deleted.uuid()).exceptionally(e -> false);
                            audit.admin(sender.getName(), sub, deleted.playerName());
                            String key = sub.equals("resetpass")
                                    ? "command.admin.resetpass"
                                    : "command.admin.unregistered";
                            sender.sendMessage(messages.get(key, player));
                        }
                    }, executors.mainThread());
            case "unlock" -> withAccount(sender, target, account -> {
                auth.clearThrottle(account);
                audit.admin(sender.getName(), "unlock", account.playerName());
                sender.sendMessage(messages.get("command.admin.unlocked",
                        Placeholder.unparsed("player", account.playerName())));
            });
            case "unlockip" -> {
                // Yalnızca gerçek bir IPv4/IPv6 adresi kabul edilir. InetAddress.getByName
                // hostname de çözebildiği için önce biçim kontrolü yapılır; aksi halde
                // "localhost" yazan admin sessizce 127.0.0.1 cezasını temizleyebilirdi.
                String ip = normalizeIpLiteral(target);
                if (ip == null) {
                    sender.sendMessage(messages.get("command.admin.invalid-ip",
                            Placeholder.unparsed("ip", target)));
                    return true;
                }
                auth.clearIpThrottle(ip);
                audit.admin(sender.getName(), "unlockip", ip);
                sender.sendMessage(messages.get("command.admin.unlocked-ip",
                        Placeholder.unparsed("ip", ip)));
            }
            case "forcelogin" -> {
                Player online = Bukkit.getPlayerExact(target);
                if (online == null) {
                    sender.sendMessage(messages.get("command.admin.not-online", player));
                } else if (auth.forceLogin(online)) {
                    audit.admin(sender.getName(), "forcelogin", target);
                    sender.sendMessage(messages.get("command.admin.forcelogin", player));
                } else {
                    sender.sendMessage(messages.get("command.already-logged-in"));
                }
            }
            case "2fa-reset" -> {
                // Ele geçirilmiş bir OP hesabı oyun içinden başkasının 2FA'sını kaldıramasın: yalnız konsol.
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                    sender.sendMessage(messages.get("command.admin.console-only"));
                    return true;
                }
                withAccount(sender, target, account -> auth.resetTwoFactor(account.uuid())
                        .whenCompleteAsync((removed, error) -> {
                            if (error != null) {
                                sender.sendMessage(messages.get("command.admin.error",
                                        Placeholder.unparsed("error", errorMessage(error))));
                                return;
                            }
                            TagResolver name = Placeholder.unparsed("player", account.playerName());
                            if (removed) {
                                audit.admin(sender.getName(), "2fa-reset", account.playerName());
                                sender.sendMessage(messages.get("command.admin.2fa-reset", name));
                            } else {
                                sender.sendMessage(messages.get("command.admin.2fa-none", name));
                            }
                        }, executors.mainThread()));
            }
            default -> messages.list("command.admin.usage").forEach(sender::sendMessage);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        ReloadHandler handler = this.reloadHandler;
        if (handler == null) {
            sender.sendMessage(messages != null
                    ? messages.get("command.admin.reload-failed", Placeholder.unparsed("error", "reload handler not ready"))
                    : Component.text("[NexusLogin] Reload handler henüz hazır değil.", NamedTextColor.RED));
            return;
        }
        ReloadOutcome outcome;
        try {
            outcome = handler.reload();
        } catch (RuntimeException e) {
            outcome = ReloadOutcome.failed(errorMessage(e));
        }
        if (audit != null) {
            audit.admin(sender.getName(), "reload", outcome.success() ? "ok" : "failed");
        }
        if (!outcome.success()) {
            sender.sendMessage(messages != null
                    ? messages.get("command.admin.reload-failed", Placeholder.unparsed("error", outcome.error() == null ? "?" : outcome.error()))
                    : Component.text("[NexusLogin] Yapılandırma yüklenemedi: " + outcome.error(), NamedTextColor.RED));
        } else if (outcome.restartRequired()) {
            sender.sendMessage(messages != null
                    ? messages.get("command.admin.reloaded-restart")
                    : Component.text("[NexusLogin] Yapılandırma yüklendi (bazı ayarlar için sunucuyu yeniden başlatmanız gerekir).", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(messages != null
                    ? messages.get("command.admin.reloaded")
                    : Component.text("[NexusLogin] Yapılandırma başarıyla yeniden yüklendi.", NamedTextColor.GREEN));
        }
    }

    private String flag(boolean value, String trueKey, String falseKey) {
        return messages.text(value ? trueKey : falseKey, value ? "yes" : "no");
    }

    private String ipOrNone(@Nullable String ip) {
        return ip == null || ip.isBlank() ? messages.text("common.none", "-") : ip;
    }

    /**
     * Verilen metni IPv4/IPv6 literal olarak doğrular ve kanonik biçimine çevirir.
     * DNS çözümlemesi yapılmaz: yalnızca rakam/nokta (IPv4) veya hex/iki nokta (IPv6)
     * karakterlerinden oluşan girdiler kabul edilir.
     *
     * @return kanonik adres, geçersizse {@code null}
     */
    static @Nullable String normalizeIpLiteral(String raw) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty() || candidate.length() > 45) {
            return null;
        }
        if (candidate.indexOf(':') >= 0) {
            // İki nokta içeren girdi hostname olamaz; InetAddress burada DNS'e gitmez.
            if (!candidate.matches("[0-9a-fA-F:.]+")) {
                return null;
            }
            try {
                return InetAddress.getByName(candidate).getHostAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        // IPv4'ü kendimiz ayrıştırırız: "999.999.999.999" gibi geçersiz bir değer
        // InetAddress tarafından hostname sanılıp DNS sorgusuna dönüşebilirdi.
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(candidate.length());
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || (part.length() > 1 && part.charAt(0) == '0')) {
                return null;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            if (!normalized.isEmpty()) {
                normalized.append('.');
            }
            normalized.append(octet);
        }
        return normalized.toString();
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void withAccount(CommandSender sender, String name, Consumer<Account> action) {
        accounts.findByName(name).whenCompleteAsync((found, error) -> {
            if (error != null) {
                sender.sendMessage(messages.get("command.admin.error",
                        Placeholder.unparsed("error", errorMessage(error))));
            } else if (found.isEmpty()) {
                sender.sendMessage(messages.get("command.admin.not-found",
                        Placeholder.unparsed("player", name)));
            } else {
                action.accept(found.get());
            }
        }, executors.mainThread());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (accounts == null || auth == null) {
            return args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("reload") : List.of();
        }
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")
                && !args[0].equalsIgnoreCase("unlockip")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(p -> p.getName())
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private record DeleteOutcome(Optional<Account> account, boolean deleted) {
        static DeleteOutcome missing() {
            return new DeleteOutcome(Optional.empty(), false);
        }
    }
}
