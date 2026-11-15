package dev.nexus.login.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

/** 1.1.0 iskeleti: henüz hiçbir davranış yok; kapı bekçisi sonraki adımlarda eklenir. */
@Plugin(id = "nexuslogin", name = "NexusLogin", version = "1.1.0-SNAPSHOT",
        description = "NexusLogin proxy gatekeeper", authors = {"Nexus Development"})
public final class NexusLoginVelocity {

    private final Logger logger;

    @Inject
    public NexusLoginVelocity(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        logger.warn("NexusLogin Velocity modülü bu sürümde yalnızca iskelettir; oyuncuları KORUMAZ.");
    }
}
