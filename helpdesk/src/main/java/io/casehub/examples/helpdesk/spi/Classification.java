package io.casehub.examples.helpdesk.spi;

import io.casehub.examples.helpdesk.model.TicketCategory;
import io.casehub.examples.helpdesk.model.TicketPriority;

public record Classification(TicketCategory category, TicketPriority priority) {}
