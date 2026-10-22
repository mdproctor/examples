package io.casehub.examples.manor.agent;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import io.casehub.platform.agent.session.SessionLifecycleManager;
import io.casehub.platform.agent.session.SessionPipeline;
import io.casehub.platform.agent.session.SessionPoolRegistry;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class SessionPoolBeans {

    private static final Logger log = Logger.getLogger(SessionPoolBeans.class);

    @Produces
    @Singleton
    SessionPoolRegistry sessionPoolRegistry(BackendInstanceRegistry backendRegistry,
                                            @ConfigProperty(name = "casehub.platform.agent.claude.max-concurrent-sessions", defaultValue = "12") int maxSessions) {
        if (backendRegistry.resolveByKey("claude").isEmpty()) {
            log.info("No Claude backend registered — activating stub backend for testing");
            backendRegistry.register(new StubAgentBackend());
        }
        return new SessionPoolRegistry(
                List.of(new PoolDeclaration(
                        "character-pool", "character", "claude", 0, maxSessions, null, null, null)),
                backendRegistry);
    }

    @Produces
    @Singleton
    SessionPipeline sessionPipeline(SessionPoolRegistry poolRegistry,
                                     @ConfigProperty(name = "casehub.platform.agent.claude.max-concurrent-sessions", defaultValue = "12") int maxSessions) {
        var manager = new SessionLifecycleManager(poolRegistry);
        return new SessionPipeline(manager, "claude", maxSessions, 80_000);
    }

    static final class StubAgentBackend implements AgentBackend {
        @Override public String key() { return "claude"; }

        @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            return Multi.createFrom().item(new AgentEvent.TextDelta("{}"));
        }

        @Override public AgentSession openSession(AgentSessionInit init) {
            return new AgentSession() {
                @Override public Multi<AgentEvent> query(String prompt) {
                    return Multi.createFrom().item(new AgentEvent.TextDelta("{}"));
                }
                @Override public io.smallrye.mutiny.Uni<Void> interrupt() {
                    return io.smallrye.mutiny.Uni.createFrom().voidItem();
                }
                @Override public void close(Duration timeout) {}
            };
        }
    }
}
