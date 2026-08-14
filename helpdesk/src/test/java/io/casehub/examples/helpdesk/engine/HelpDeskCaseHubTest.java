package io.casehub.examples.helpdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.CaseDefinition;
import io.casehub.examples.helpdesk.DemoTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DemoTestProfile.class)
class HelpDeskCaseHubTest {

    @Inject
    HelpDeskCaseHub caseHub;

    @Test
    void definitionLoadsFromYaml() {
        CaseDefinition def = caseHub.getDefinition();
        assertNotNull(def);
        assertEquals("helpdesk-ticket", def.getName());
        assertEquals("casehub-examples", def.getNamespace());
    }

    @Test
    void definitionHasExpectedCapabilities() {
        CaseDefinition def = caseHub.getDefinition();
        assertEquals(2, def.getCapabilities().size());
        assertTrue(def.getCapabilities().stream()
                .anyMatch(c -> c.name().equals("classify-ticket")));
        assertTrue(def.getCapabilities().stream()
                .anyMatch(c -> c.name().equals("notify-customer")));
    }

    @Test
    void definitionHasExpectedBindings() {
        CaseDefinition def = caseHub.getDefinition();
        assertEquals(3, def.getBindings().size());
    }

    @Test
    void definitionHasExpectedWorkers() {
        CaseDefinition def = caseHub.getDefinition();
        assertEquals(2, def.getWorkers().size());
    }
}
