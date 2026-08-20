package io.casehub.examples.helpdesk.mcp;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;

import java.util.List;

@McpDomain("helpdesk")
public interface HelpdeskOperations {

    @PlatformMutation("Load keyword→category→priority classification entries for ticket triage")
    BootstrapResult bootstrapClassifications(List<ClassificationInput> entries);

    @PlatformMutation("Bootstrap classifications and inject a chat message to create a ticket in one call")
    InjectTicketResult injectTicket(String from, String channelId, String text,
                                    List<ClassificationInput> classifications);

    @PlatformQuery("Return helpdesk case contexts projected as ticket data")
    List<TicketView> tickets();

    @PlatformQuery("Return sent notifications for verification")
    List<NotificationView> notifications(Integer limit);
}
