package io.casehub.examples.helpdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeywordClassifierDispatcherTest {

    private KeywordClassifierDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new KeywordClassifierDispatcher();
        dispatcher.loadClassifications(List.of(
                new KeywordClassifierDispatcher.ClassificationEntry("laptop", "HARDWARE", "HIGH"),
                new KeywordClassifierDispatcher.ClassificationEntry("password", "ACCESS", "LOW"),
                new KeywordClassifierDispatcher.ClassificationEntry("install", "SOFTWARE", "MEDIUM")));
    }

    @Test
    void classifiesMatchingKeyword() throws Exception {
        var result = dispatcher.dispatch("wf-1", Map.of("text", "My laptop won't boot")).get();
        assertEquals("HARDWARE", result.get("category"));
        assertEquals("HIGH", result.get("priority"));
    }

    @Test
    void fallsBackToOtherMedium() throws Exception {
        var result = dispatcher.dispatch("wf-1", Map.of("text", "Something unrelated")).get();
        assertEquals("OTHER", result.get("category"));
        assertEquals("MEDIUM", result.get("priority"));
    }

    @Test
    void caseInsensitiveMatching() throws Exception {
        var result = dispatcher.dispatch("wf-1", Map.of("text", "LAPTOP screen broken")).get();
        assertEquals("HARDWARE", result.get("category"));
    }
}
