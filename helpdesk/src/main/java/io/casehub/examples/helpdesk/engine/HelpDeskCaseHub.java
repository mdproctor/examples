package io.casehub.examples.helpdesk.engine;

import io.casehub.api.engine.YamlCaseHub;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class HelpDeskCaseHub extends YamlCaseHub {

    public HelpDeskCaseHub() {
        super("casehub/helpdesk-ticket.yaml");
    }
}
