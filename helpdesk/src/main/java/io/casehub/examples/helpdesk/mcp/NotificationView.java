package io.casehub.examples.helpdesk.mcp;

import java.time.Instant;

public record NotificationView(String to, String message, Instant sentAt) {}
