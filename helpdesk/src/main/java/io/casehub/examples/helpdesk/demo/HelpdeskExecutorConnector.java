package io.casehub.examples.helpdesk.demo;

import io.casehub.examples.helpdesk.push.HelpdeskSessionSender;
import io.casehub.pages.push.PushRequest;
import io.casehub.pages.scenario.client.ActionRegistry;
import io.casehub.pages.scenario.client.ScenarioExecutorClient;
import io.casehub.pages.scenario.runtime.ScenarioOrchestrator;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@IfBuildProfile("demo")
public class HelpdeskExecutorConnector {

    private static final Logger LOG = Logger.getLogger(HelpdeskExecutorConnector.class);

    @Inject ScenarioOrchestrator orchestrator;
    @Inject HelpdeskSessionSender sender;
    @Inject HelpDeskScenarioActions scenarioActions;

    void connect(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        var connId = "local-executor-" + UUID.randomUUID();
        var unwrapped = io.quarkus.arc.ClientProxy.unwrap(scenarioActions);
        var actions = ActionRegistry.scan(List.of(unwrapped)).actions();

        orchestrator.onExecutorRegister(connId,
            new PushRequest.ExecutorRegister(UUID.randomUUID().toString(),
                "helpdesk", List.copyOf(actions)));

        var executorClient = ScenarioExecutorClient.create(
            "helpdesk", List.of(unwrapped),
            msg -> {
                PushRequest request = PushRequest.parse(msg);
                if (request instanceof PushRequest.StepResult result) {
                    orchestrator.onStepResult(result);
                }
            });

        sender.registerLocal(connId, executorClient::onMessage);

        LOG.infof("Local executor 'helpdesk' registered with %d actions: %s",
            actions.size(), actions);
    }
}
