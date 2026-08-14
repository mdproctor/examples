package io.casehub.examples.helpdesk.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HelpdeskDispatcherRegistrar {

    @Inject KeywordClassifierDispatcher classifier;

    public KeywordClassifierDispatcher classifier() {
        return classifier;
    }
}
