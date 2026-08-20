package io.casehub.examples.helpdesk.mcp;

import java.time.Instant;

public record TicketView(String caseId, String status, String category,
                         String priority, String customerRef, String subject,
                         String assigneeId, String resolution, Instant createdAt) {}
