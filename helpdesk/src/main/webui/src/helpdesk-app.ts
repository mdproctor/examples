import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';

interface Ticket {
  id: string;
  subject: string;
  category: string | null;
  priority: string | null;
  status: string;
  customerRef: string;
  assigneeId: string | null;
  resolution: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

interface Notification {
  to: string;
  message: string;
  sentAt: string;
}

@customElement('helpdesk-app')
export class HelpdeskApp extends LitElement {
  @state() private tickets: Ticket[] = [];
  @state() private notifications: Notification[] = [];
  @state() private events: string[] = [];
  @state() private name = '';
  @state() private issue = '';
  @state() private bootstrapped = false;

  private _pollTimer: ReturnType<typeof setInterval> | null = null;

  override connectedCallback() {
    super.connectedCallback();
    this._poll();
    this._pollTimer = setInterval(() => this._poll(), 2000);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._pollTimer) clearInterval(this._pollTimer);
  }

  private async _poll() {
    try {
      const [tRes, nRes] = await Promise.all([
        fetch('/scenario/verify/tickets'),
        fetch('/scenario/verify/notifications'),
      ]);
      if (tRes.ok) {
        const newTickets = await tRes.json() as Ticket[];
        if (newTickets.length !== this.tickets.length) {
          this._addEvent(`${newTickets.length} ticket(s) in system`);
        }
        this.tickets = newTickets;
      }
      if (nRes.ok) {
        const newNotifs = await nRes.json() as Notification[];
        if (newNotifs.length !== this.notifications.length) {
          this._addEvent(`Notification sent to ${newNotifs[newNotifs.length - 1]?.to}`);
        }
        this.notifications = newNotifs;
      }
    } catch { /* backend not ready yet */ }
  }

  private _addEvent(msg: string) {
    const ts = new Date().toLocaleTimeString();
    this.events = [...this.events, `[${ts}] ${msg}`];
  }

  private async _bootstrap() {
    const res = await fetch('/scenario/bootstrap/helpdesk', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ticketClassifications: [
          { match: 'laptop', category: 'HARDWARE', priority: 'HIGH' },
          { match: 'password', category: 'ACCESS', priority: 'LOW' },
          { match: 'install', category: 'SOFTWARE', priority: 'MEDIUM' },
          { match: 'printer', category: 'HARDWARE', priority: 'MEDIUM' },
          { match: 'email', category: 'SOFTWARE', priority: 'LOW' },
          { match: 'vpn', category: 'ACCESS', priority: 'HIGH' },
        ],
      }),
    });
    if (res.ok) {
      this.bootstrapped = true;
      this._addEvent('Classification data loaded');
    }
  }

  private async _submitTicket() {
    if (!this.name || !this.issue) return;
    const res = await fetch('/scenario/inject/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: this.name, channelId: 'support', text: this.issue }),
    });
    if (res.ok) {
      this._addEvent(`Chat message from ${this.name}: "${this.issue}"`);
      this.name = '';
      this.issue = '';
    }
  }

  private async _resolveTicket(ticket: Ticket) {
    const resolution = prompt('Resolution:');
    if (!resolution) return;
    const res = await fetch(`/tickets/${ticket.id}/resolve`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution }),
    });
    if (res.ok) {
      this._addEvent(`Ticket resolved: ${ticket.subject}`);
      this._poll();
    }
  }

  private _statusColor(status: string): string {
    switch (status) {
      case 'OPEN': return 'var(--warning)';
      case 'TRIAGED': return 'var(--accent)';
      case 'ASSIGNED': return '#a371f7';
      case 'RESOLVED': return 'var(--success)';
      default: return 'var(--text-muted)';
    }
  }

  private _priorityColor(priority: string | null): string {
    switch (priority) {
      case 'URGENT': return 'var(--danger)';
      case 'HIGH': return 'var(--warning)';
      case 'MEDIUM': return 'var(--accent)';
      case 'LOW': return 'var(--text-muted)';
      default: return 'var(--text-muted)';
    }
  }

  override render() {
    const open = this.tickets.filter(t => t.status !== 'RESOLVED').length;
    const resolved = this.tickets.filter(t => t.status === 'RESOLVED').length;

    return html`
      <header>
        <h1>IT Help Desk</h1>
        <span class="subtitle">CaseHub Example — Scenario-Driven Demo</span>
      </header>

      <div class="dashboard">
        <div class="metrics">
          <div class="metric">
            <span class="metric-value">${this.tickets.length}</span>
            <span class="metric-label">Total</span>
          </div>
          <div class="metric">
            <span class="metric-value" style="color: var(--warning)">${open}</span>
            <span class="metric-label">Open</span>
          </div>
          <div class="metric">
            <span class="metric-value" style="color: var(--success)">${resolved}</span>
            <span class="metric-label">Resolved</span>
          </div>
          <div class="metric">
            <span class="metric-value" style="color: var(--accent)">${this.notifications.length}</span>
            <span class="metric-label">Notified</span>
          </div>
        </div>

        <div class="panels">
          <div class="panel tickets-panel">
            <h2>Tickets</h2>
            ${this.tickets.length === 0
              ? html`<p class="empty">No tickets yet. Bootstrap data and submit a message.</p>`
              : html`
                <table>
                  <thead>
                    <tr>
                      <th>Subject</th>
                      <th>Status</th>
                      <th>Category</th>
                      <th>Priority</th>
                      <th>Customer</th>
                      <th>Assignee</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    ${this.tickets.map(t => html`
                      <tr>
                        <td>${t.subject}</td>
                        <td><span class="badge" style="background: ${this._statusColor(t.status)}">${t.status}</span></td>
                        <td>${t.category ?? '—'}</td>
                        <td><span class="badge" style="background: ${this._priorityColor(t.priority)}">${t.priority ?? '—'}</span></td>
                        <td>${t.customerRef}</td>
                        <td>${t.assigneeId ?? '—'}</td>
                        <td>${t.status !== 'RESOLVED'
                          ? html`<button class="resolve-btn" @click=${() => this._resolveTicket(t)}>Resolve</button>`
                          : html`<span class="resolved-label">✓ ${t.resolution}</span>`
                        }</td>
                      </tr>
                    `)}
                  </tbody>
                </table>
              `
            }
          </div>

          <div class="sidebar">
            <div class="panel">
              <h2>Submit Ticket</h2>
              ${!this.bootstrapped ? html`
                <button class="action-btn bootstrap-btn" @click=${this._bootstrap}>
                  Load Classification Data
                </button>
                <p class="hint">Load demo data before submitting tickets.</p>
              ` : html`
                <p class="hint success">✓ Classification data loaded</p>
              `}
              <input
                type="text"
                placeholder="Your name"
                .value=${this.name}
                @input=${(e: Event) => this.name = (e.target as HTMLInputElement).value}
              />
              <input
                type="text"
                placeholder="Describe your issue"
                .value=${this.issue}
                @input=${(e: Event) => this.issue = (e.target as HTMLInputElement).value}
                @keydown=${(e: KeyboardEvent) => e.key === 'Enter' && this._submitTicket()}
              />
              <button class="action-btn" @click=${this._submitTicket} ?disabled=${!this.name || !this.issue}>
                Send Message
              </button>
            </div>

            <div class="panel">
              <h2>Event Log</h2>
              <div class="event-log">
                ${this.events.length === 0
                  ? html`<p class="empty">Waiting for events...</p>`
                  : this.events.map(e => html`<div class="event-entry">${e}</div>`)
                }
              </div>
            </div>

            <div class="panel">
              <h2>Notifications</h2>
              ${this.notifications.length === 0
                ? html`<p class="empty">No notifications sent yet.</p>`
                : this.notifications.map(n => html`
                  <div class="notification">
                    <strong>To: ${n.to}</strong>
                    <p>${n.message}</p>
                  </div>
                `)
              }
            </div>
          </div>
        </div>
      </div>
    `;
  }

  static override styles = css`
    :host { display: block; min-height: 100vh; }

    header {
      padding: 16px 24px;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      display: flex;
      align-items: baseline;
      gap: 16px;
    }
    h1 { font-size: 20px; font-weight: 600; }
    .subtitle { font-size: 13px; color: var(--text-muted); }

    .dashboard { padding: 24px; }

    .metrics {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 16px;
      margin-bottom: 24px;
    }
    .metric {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 20px;
      text-align: center;
    }
    .metric-value { display: block; font-size: 36px; font-weight: 700; }
    .metric-label { font-size: 12px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }

    .panels {
      display: grid;
      grid-template-columns: 1fr 360px;
      gap: 24px;
    }

    .panel {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 20px;
      margin-bottom: 16px;
    }
    h2 { font-size: 14px; font-weight: 600; margin-bottom: 12px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }

    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; padding: 8px; color: var(--text-muted); font-weight: 500; border-bottom: 1px solid var(--border); }
    td { padding: 8px; border-bottom: 1px solid var(--border); }
    tr:hover { background: rgba(255,255,255,0.02); }

    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
      color: #fff;
    }

    .resolve-btn {
      padding: 4px 12px;
      border-radius: 4px;
      border: 1px solid var(--accent);
      background: transparent;
      color: var(--accent);
      cursor: pointer;
      font-size: 12px;
    }
    .resolve-btn:hover { background: var(--accent); color: #fff; }

    .resolved-label { font-size: 12px; color: var(--success); }

    input {
      display: block;
      width: 100%;
      padding: 8px 12px;
      margin-bottom: 8px;
      border: 1px solid var(--border);
      border-radius: 6px;
      background: var(--bg);
      color: var(--text);
      font-size: 13px;
    }
    input:focus { outline: none; border-color: var(--accent); }

    .action-btn {
      display: block;
      width: 100%;
      padding: 10px;
      border: none;
      border-radius: 6px;
      background: var(--accent);
      color: #fff;
      font-weight: 600;
      cursor: pointer;
      font-size: 13px;
      margin-bottom: 8px;
    }
    .action-btn:hover { opacity: 0.9; }
    .action-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .bootstrap-btn { background: #238636; }

    .hint { font-size: 12px; color: var(--text-muted); margin-bottom: 12px; }
    .hint.success { color: var(--success); }
    .empty { font-size: 13px; color: var(--text-muted); font-style: italic; }

    .event-log { max-height: 200px; overflow-y: auto; }
    .event-entry {
      font-size: 12px;
      font-family: 'SF Mono', Menlo, monospace;
      padding: 4px 0;
      color: var(--text-muted);
      border-bottom: 1px solid rgba(255,255,255,0.04);
    }

    .notification {
      padding: 8px;
      margin-bottom: 8px;
      background: rgba(56, 139, 253, 0.1);
      border-radius: 6px;
      border-left: 3px solid var(--accent);
    }
    .notification strong { font-size: 12px; color: var(--accent); }
    .notification p { font-size: 13px; margin-top: 4px; }

    .sidebar { display: flex; flex-direction: column; }
  `;
}
