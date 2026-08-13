export interface ScenarioStep {
  label: string;
  description: string;
  action: 'bootstrap' | 'submit' | 'resolve';
  params?: Record<string, unknown>;
  previewText?: string;
}

export const HELPDESK_SCENARIO: ScenarioStep[] = [
  {
    label: 'Load classification data',
    description: 'Bootstrap the ticket classifier with keyword-to-category mappings.',
    action: 'bootstrap',
    params: {
      ticketClassifications: [
        { match: 'laptop', category: 'HARDWARE', priority: 'HIGH' },
        { match: 'password', category: 'ACCESS', priority: 'LOW' },
        { match: 'install', category: 'SOFTWARE', priority: 'MEDIUM' },
        { match: 'printer', category: 'HARDWARE', priority: 'MEDIUM' },
        { match: 'email', category: 'SOFTWARE', priority: 'LOW' },
        { match: 'vpn', category: 'ACCESS', priority: 'HIGH' },
      ],
    },
  },
  {
    label: 'Submit: laptop issue',
    description: 'A user reports a broken laptop. The system creates a ticket, classifies it as HARDWARE/HIGH, and assigns to hw-specialist.',
    action: 'submit',
    params: { from: 'alice', channelId: 'support', text: 'My laptop screen is broken and I cannot work' },
    previewText: 'My laptop screen is broken and I cannot work',
  },
  {
    label: 'Submit: password reset',
    description: 'A user needs a password reset. Classified as ACCESS/LOW, assigned to access-specialist.',
    action: 'submit',
    params: { from: 'bob', channelId: 'support', text: 'I forgot my password and cannot log in' },
    previewText: 'I forgot my password and cannot log in',
  },
  {
    label: 'Resolve: laptop issue',
    description: 'The hardware specialist resolves the laptop ticket. The customer receives a notification.',
    action: 'resolve',
    previewText: 'Replaced screen — new laptop model deployed',
  },
];
