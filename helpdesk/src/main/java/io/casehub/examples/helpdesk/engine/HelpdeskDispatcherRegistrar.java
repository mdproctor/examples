package io.casehub.examples.helpdesk.engine;

import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.examples.helpdesk.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HelpdeskDispatcherRegistrar {

    @Inject CallableDispatchRegistry registry;
    @Inject NotificationService notificationService;

    private final KeywordClassifierDispatcher classifier = new KeywordClassifierDispatcher();

    @PostConstruct
    void register() {
        registry.register("classify-ticket", classifier);
        registry.register("notify-customer", new NotificationDispatcher(notificationService));
    }

    public KeywordClassifierDispatcher classifier() {
        return classifier;
    }
}
