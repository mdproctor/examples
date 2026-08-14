package io.casehub.examples.helpdesk.engine;

import io.casehub.connectors.chat.model.ReceivedMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ChatCaseCreationHandler {

    private static final Logger LOG = Logger.getLogger(ChatCaseCreationHandler.class);

    @Inject HelpDeskCaseHub caseHub;

    public void onMessage(@ObservesAsync ReceivedMessage message) {
        String subject = message.content().text();
        String customerRef = message.sender().id();

        UUID caseId = caseHub.startCase(Map.of(
                "subject", subject,
                "customerRef", customerRef,
                "status", "OPEN"));

        LOG.infof("Help desk case %s created from chat message: %s", caseId, subject);
    }
}
