package io.casehub.examples.manor.agent;

import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class SessionPoolBeans {

    @Produces
    @Singleton
    SessionPoolRegistry sessionPoolRegistry(BackendInstanceRegistry backendRegistry) {
        return new SessionPoolRegistry(
                List.of(new PoolDeclaration(
                        "character-pool", "character", "claude", 0, 12, null, null, null)),
                backendRegistry);
    }

    @Produces
    @Singleton
    SessionPipeline sessionPipeline(SessionPoolRegistry poolRegistry,
                                     @ConfigProperty(name = "casehub.platform.agent.claude.max-concurrent-sessions", defaultValue = "12") int maxSessions) {
        var manager = new SessionLifecycleManager(poolRegistry);
        return new SessionPipeline(manager, "claude", maxSessions, 80_000);
    }
}
