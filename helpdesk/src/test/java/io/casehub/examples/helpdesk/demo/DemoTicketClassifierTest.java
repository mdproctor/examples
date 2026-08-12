package io.casehub.examples.helpdesk.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;

class DemoTicketClassifierTest {

    @Test
    void matchesLoadedClassification() {
        var classifier = new DemoTicketClassifier();
        classifier.loadClassifications(List.of(
                new DemoTicketClassifier.ClassificationEntry(
                        "laptop won't boot", TicketCategory.HARDWARE, TicketPriority.HIGH)));

        var result = classifier.classify("My laptop won't boot after the update", "Details here");
        assertThat(result.category()).isEqualTo(TicketCategory.HARDWARE);
        assertThat(result.priority()).isEqualTo(TicketPriority.HIGH);
    }

    @Test
    void returnsDefaultWhenNoMatch() {
        var classifier = new DemoTicketClassifier();
        classifier.loadClassifications(List.of());

        var result = classifier.classify("Something random", "No match");
        assertThat(result.category()).isEqualTo(TicketCategory.OTHER);
        assertThat(result.priority()).isEqualTo(TicketPriority.MEDIUM);
    }

    @Test
    void matchIsCaseInsensitiveSubstring() {
        var classifier = new DemoTicketClassifier();
        classifier.loadClassifications(List.of(
                new DemoTicketClassifier.ClassificationEntry(
                        "password reset", TicketCategory.ACCESS, TicketPriority.LOW)));

        var result = classifier.classify("I need a PASSWORD RESET please", "Urgent");
        assertThat(result.category()).isEqualTo(TicketCategory.ACCESS);
    }
}
