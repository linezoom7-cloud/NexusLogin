package dev.nexus.login.listener;

import org.junit.jupiter.api.Test;

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

    @Test
    void unknownAndEmptyAreDenied() {
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/op me")));
        assertFalse(RestrictionListener.isAllowed(ALLOWED, RestrictionListener.label("/")));
        assertEquals("nexuslogin:giris", RestrictionListener.label("/NexusLogin:giris arg"));
    }
}
