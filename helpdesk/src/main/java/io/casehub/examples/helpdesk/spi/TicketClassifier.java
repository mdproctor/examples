package io.casehub.examples.helpdesk.spi;

public interface TicketClassifier {
    Classification classify(String subject, String description);
}
