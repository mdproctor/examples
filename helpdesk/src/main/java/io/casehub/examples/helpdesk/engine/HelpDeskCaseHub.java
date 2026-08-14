package io.casehub.examples.helpdesk.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.examples.helpdesk.NotificationService;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class HelpDeskCaseHub extends YamlCaseHub {

    @Inject KeywordClassifierDispatcher classifier;
    @Inject NotificationService notificationService;

    public HelpDeskCaseHub() {
        super("casehub/helpdesk-ticket.yaml");
    }

    @Override
    protected void augment(CaseDefinition definition) {
        var workers = definition.getWorkers();
        workers.removeIf(w -> "keyword-classifier".equals(w.name())
                || "notification-sender".equals(w.name()));

        workers.add(Worker.builder()
                .name("keyword-classifier")
                .capabilityName("classify-ticket")
                .function(input -> WorkerResult.of(classifier.dispatch("", input).join()))
                .build());

        workers.add(Worker.builder()
                .name("notification-sender")
                .capabilityName("notify-customer")
                .function(input -> {
                    String customerRef = (String) input.get("customerRef");
                    String message = (String) input.get("message");
                    notificationService.notify(customerRef, message);
                    return WorkerResult.of(Map.of("notified", true));
                })
                .build());
    }
}
