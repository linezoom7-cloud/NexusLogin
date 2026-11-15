package dev.nexus.login.listener;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Giriş yapmamış oyuncunun komut beyaz listesi: ad alanı sıyırma açığı kapalı kalmalı. */
class RestrictionListenerCommandTest {

    private static final Set<String> ALLOWED = Set.of("giris", "login", "l", "authme:register");

    @Test
    void plainAllowedCommandsPass() {
        assertTrue(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/login 1234")));
        assertTrue(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/GIRIS")));
    }

    @Test
    void ownNamespacePasses() {
        assertTrue(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/NexusLogin:giris")));
    }

    @Test
    void foreignNamespaceWithSameLabelIsDenied() {
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/essentials:l")),
                "başka eklentinin 'l' komutu giriş yapmadan çalışmamalı");
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/otherplugin:login")));
    }

    @Test
    void explicitlyConfiguredNamespacedCommandPasses() {
        assertTrue(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/authme:register x")));
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/register x")));
    }

    /** Yalnızca sahibini bildiren sahte komut. */
    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;

        OwnedCommand(String pluginName) {
            super("l");
            this.plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                    new Class<?>[]{Plugin.class}, (proxy, method, args) -> pluginName);
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }

    @Test
    void gateCommandOwnershipIsCheckedByPluginName() {
        assertTrue(RestrictionListener.ownedByUs(new OwnedCommand("NexusLogin")));
        assertFalse(RestrictionListener.ownedByUs(new OwnedCommand("AuthMe")));
    }

    @Test
    void unknownAndEmptyAreDenied() {
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/op me")));
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/")));
        assertEquals("nexuslogin:giris", RestrictionListener.label("/NexusLogin:giris arg"));
    }
}
