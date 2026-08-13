import type { TimelineStrategy, TimelineNode, Layout } from '@casehubio/blocks-ui-blocks-timeline/types.js';

const STAGES = ['created', 'classified', 'assigned', 'resolved'] as const;

const STATUS_TO_STAGE: Record<string, number> = {
  OPEN: 0,
  TRIAGED: 1,
  ASSIGNED: 2,
  RESOLVED: 3,
  CLOSED: 3,
};

export interface TicketSnapshot {
  id: string;
  subject: string;
  status: string;
  createdAt: string;
  resolvedAt: string | null;
  assigneeId: string | null;
}

export class HelpdeskPipelineStrategy implements TimelineStrategy<TicketSnapshot[]> {
  defaultLayout: Layout = 'horizontal';

  toNodes(tickets: TicketSnapshot[]): TimelineNode[] {
    return tickets.flatMap(ticket => {
      const completedIdx = STATUS_TO_STAGE[ticket.status] ?? 0;
      return STAGES.map((stage, i) => ({
        key: `${ticket.id}:${stage}`,
        label: `${ticket.subject} — ${stage}`,
        status: (i < completedIdx ? 'completed'
              : i === completedIdx ? 'active'
              : 'pending') as TimelineNode['status'],
        timestamp: stage === 'created' ? ticket.createdAt
                 : stage === 'resolved' ? (ticket.resolvedAt ?? undefined)
                 : undefined,
        actor: stage === 'assigned' ? (ticket.assigneeId ?? undefined) : undefined,
        category: ticket.id,
      }));
    });
  }
}
