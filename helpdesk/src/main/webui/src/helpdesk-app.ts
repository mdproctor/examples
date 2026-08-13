import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { EventStreamController } from '@casehubio/pages-component';
import { fromRows, ColumnType, columnId } from '@casehubio/pages-data';
import type { TypedDataSet, ColumnId } from '@casehubio/pages-data';
import type { MetricDefinition } from '@casehubio/blocks-ui-kpi-metric-row';
import type { TableColumnConfig } from '@casehubio/pages-table';
import { HelpdeskPipelineStrategy } from './pipeline-strategy.js';
import type { TicketSnapshot } from './pipeline-strategy.js';
import { HELPDESK_SCENARIO } from './scenarios/help-desk-basic.js';
import type { ScenarioStep } from './scenarios/help-desk-basic.js';

// side-effect imports — register custom elements
import '@casehubio/blocks-ui-kpi-metric-row';
import '@casehubio/blocks-ui-blocks-timeline';
import '@casehubio/pages-table';

interface TicketEvent {
  type: 'CREATED' | 'CLASSIFIED' | 'ASSIGNED' | 'RESOLVED';
  ticket: Ticket;
}

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

interface NotificationEvent {
  to: string;
  message: string;
}

interface MetricsSnapshot {
  total: number;
  open: number;
  resolved: number;
  notified: number;
}

const TICKET_COLUMNS: ColumnId[] = ['subject', 'status', 'category', 'priority', 'customerRef', 'assigneeId'].map(s => columnId(s));

const TICKET_COLUMN_CONFIG: readonly TableColumnConfig[] = [
  { id: columnId('subject'), name: 'Subject' },
  { id: columnId('status'), name: 'Status' },
  { id: columnId('category'), name: 'Category' },
  { id: columnId('priority'), name: 'Priority' },
  { id: columnId('customerRef'), name: 'Customer' },
  { id: columnId('assigneeId'), name: 'Assignee' },
];

@customElement('helpdesk-app')
export class HelpdeskApp extends LitElement {
  // Push controllers — shared pool reuses one WebSocket connection
  private _ticketPush!: EventStreamController<TicketEvent>;
  private _notifPush!: EventStreamController<NotificationEvent>;
  private _metricsPush!: EventStreamController<MetricsSnapshot>;

  private _initPush() {
    const wsUrl = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/push`;
    this._ticketPush = new EventStreamController<TicketEvent>(this, wsUrl, 'helpdesk:tickets');
    this._notifPush = new EventStreamController<NotificationEvent>(this, wsUrl, 'helpdesk:notifications');
    this._metricsPush = new EventStreamController<MetricsSnapshot>(this, wsUrl, 'helpdesk:metrics');
  }

  // Pipeline strategy
  private _pipelineStrategy = new HelpdeskPipelineStrategy();

  // Scenario state
  @state() private _scenarioStep = 0;
  @state() private _scenarioRunning = false;
  @state() private _scenarioStatus = '';
  @state() private _standalone = false;
  @state() private _showInfo = false;

  // Track ticket IDs for resolve action in scenario
  private _trackedTicketIds: string[] = [];

  override connectedCallback() {
    this._initPush();
    super.connectedCallback();
    this._standalone = new URLSearchParams(location.search).has('standalone');
  }

  // --- Derived state from push events ---

  private get _metrics(): MetricsSnapshot {
    return this._metricsPush.latest ?? { total: 0, open: 0, resolved: 0, notified: 0 };
  }

  private get _metricDefs(): MetricDefinition[] {
    return [
      { key: 'total', value: this._metrics.total, label: 'Total' },
      { key: 'open', value: this._metrics.open, label: 'Open', status: 'warning' },
      { key: 'resolved', value: this._metrics.resolved, label: 'Resolved', status: 'normal' },
      { key: 'notified', value: this._metrics.notified, label: 'Notified' },
    ];
  }

  private get _tickets(): Ticket[] {
    const map = new Map<string, Ticket>();
    for (const event of this._ticketPush.all) {
      map.set(event.ticket.id, event.ticket);
    }
    return [...map.values()];
  }

  private get _ticketDataSet(): TypedDataSet {
    return fromRows(this._tickets, [
      { id: columnId('subject'), name: 'Subject', type: ColumnType.TEXT, getValue: (t: Ticket) => t.subject },
      { id: columnId('status'), name: 'Status', type: ColumnType.LABEL, getValue: (t: Ticket) => t.status },
      { id: columnId('category'), name: 'Category', type: ColumnType.TEXT, getValue: (t: Ticket) => t.category },
      { id: columnId('priority'), name: 'Priority', type: ColumnType.LABEL, getValue: (t: Ticket) => t.priority },
      { id: columnId('customerRef'), name: 'Customer', type: ColumnType.TEXT, getValue: (t: Ticket) => t.customerRef },
      { id: columnId('assigneeId'), name: 'Assignee', type: ColumnType.TEXT, getValue: (t: Ticket) => t.assigneeId },
    ]);
  }

  private get _ticketSnapshots(): TicketSnapshot[] {
    return this._tickets.map(t => ({
      id: t.id,
      subject: t.subject,
      status: t.status,
      createdAt: t.createdAt,
      resolvedAt: t.resolvedAt,
      assigneeId: t.assigneeId,
    }));
  }

  private get _notifications(): NotificationEvent[] {
    return [...this._notifPush.all];
  }

  // --- Scenario controller ---

  private get _currentStep(): ScenarioStep | undefined {
    return HELPDESK_SCENARIO[this._scenarioStep];
  }

  private get _scenarioDone(): boolean {
    return this._scenarioStep >= HELPDESK_SCENARIO.length;
  }

  private async _executeStep() {
    const step = this._currentStep;
    if (!step || this._scenarioRunning) return;

    this._scenarioRunning = true;
    this._scenarioStatus = 'Executing...';

    try {
      switch (step.action) {
        case 'bootstrap': {
          const res = await fetch('/scenario/bootstrap/helpdesk', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(step.params),
          });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          this._scenarioStatus = 'Done';
          break;
        }
        case 'submit': {
          const beforeLen = this._ticketPush.all.length;
          const res = await fetch('/scenario/inject/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(step.params),
          });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          this._scenarioStatus = 'Waiting for ticket...';
          await this._waitForTicketEvent(beforeLen, 'CREATED');
          const created = this._ticketPush.all.slice(beforeLen).find(e => e.type === 'CREATED');
          if (created) {
            this._trackedTicketIds.push(created.ticket.id);
            this._scenarioStatus = 'Waiting for classification...';
            await this._waitForTicketById(created.ticket.id, 'CLASSIFIED');
            this._scenarioStatus = 'Waiting for assignment...';
            await this._waitForTicketById(created.ticket.id, 'ASSIGNED');
          }
          this._scenarioStatus = 'Done';
          break;
        }
        case 'resolve': {
          const ticketId = this._trackedTicketIds[0];
          if (!ticketId) {
            this._scenarioStatus = 'No ticket to resolve';
            break;
          }
          const res = await fetch(`/tickets/${ticketId}/resolve`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resolution: step.previewText ?? 'Resolved' }),
          });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          this._trackedTicketIds.shift();
          await this._waitForTicketById(ticketId, 'RESOLVED');
          this._scenarioStatus = 'Done';
          break;
        }
      }
    } catch (e) {
      this._scenarioStatus = `Error: ${(e as Error).message}`;
    } finally {
      this._scenarioRunning = false;
    }
  }

  private _nextStep() {
    if (this._scenarioStep < HELPDESK_SCENARIO.length) {
      this._scenarioStep++;
      this._scenarioStatus = '';
    }
  }

  private _waitForTicketEvent(afterIndex: number, type: string): Promise<void> {
    return new Promise(resolve => {
      const check = () => {
        const found = this._ticketPush.all.slice(afterIndex).some(e => e.type === type);
        if (found) { resolve(); return; }
        requestAnimationFrame(check);
      };
      check();
    });
  }

  private _waitForTicketById(id: string, type: string): Promise<void> {
    return new Promise(resolve => {
      const check = () => {
        const found = this._ticketPush.all.some(e => e.ticket.id === id && e.type === type);
        if (found) { resolve(); return; }
        requestAnimationFrame(check);
      };
      check();
    });
  }

  // --- Render ---

  override render() {
    if (this._standalone) {
      return html`
        <div class="standalone">
          <header>
            <h1>Scenario Controller</h1>
            <span class="subtitle">Standalone Mode</span>
          </header>
          ${this._renderScenarioPanel()}
        </div>
      `;
    }

    return html`
      <header>
        <h1>IT Help Desk</h1>
        <span class="subtitle">CaseHub Example — Scenario-Driven Demo</span>
        <button class="info-btn" @click=${() => { this._showInfo = true; }} title="How this works">?</button>
        <span class="connection-status ${this._ticketPush.status}">
          ${this._ticketPush.status}
        </span>
      </header>

      ${this._showInfo ? this._renderInfoOverlay() : nothing}

      <div class="split-layout">
        <div class="dashboard">
          <blocks-kpi-metric-row
            .metrics=${this._metricDefs}
            columns="4"
            density="compact"
          ></blocks-kpi-metric-row>

          <div class="panel">
            <h2>Tickets</h2>
            ${this._tickets.length === 0
              ? html`<p class="empty">No tickets yet. Run the scenario to submit messages.</p>`
              : html`
                <pages-data-table
                  .dataSet=${this._ticketDataSet}
                  .columnConfig=${TICKET_COLUMN_CONFIG}
                  sortable
                  clientSort
                  clientFilter
                  embedded
                  row-height="40"
                ></pages-data-table>
              `
            }
          </div>

          ${this._tickets.length > 0 ? html`
            <div class="panel">
              <h2>Pipeline</h2>
              <blocks-timeline
                .data=${this._ticketSnapshots}
                .strategy=${this._pipelineStrategy}
              ></blocks-timeline>
            </div>
          ` : nothing}

          ${this._notifications.length > 0 ? html`
            <div class="panel">
              <h2>Notifications</h2>
              ${this._notifications.map(n => html`
                <div class="notification">
                  <strong>To: ${n.to}</strong>
                  <p>${n.message}</p>
                </div>
              `)}
            </div>
          ` : nothing}
        </div>

        <div class="scenario-sidebar">
          ${this._renderScenarioPanel()}
        </div>
      </div>
    `;
  }

  private _renderScenarioPanel() {
    const step = this._currentStep;
    const stepNum = this._scenarioStep + 1;
    const total = HELPDESK_SCENARIO.length;

    return html`
      <div class="scenario-panel">
        <div class="scenario-header">
          <h2>Scenario</h2>
          <span class="step-indicator">
            ${this._scenarioDone ? 'Complete' : `Step ${stepNum}/${total}`}
          </span>
        </div>

        ${this._scenarioDone ? html`
          <div class="scenario-complete">
            All steps complete. The dashboard shows the final state.
          </div>
        ` : step ? html`
          <div class="step-content">
            <div class="step-label">${step.label}</div>
            <p class="step-description">${step.description}</p>

            ${step.previewText ? html`
              <div class="preview-box">
                <div class="preview-label">Preview</div>
                <div class="preview-text">${step.previewText}</div>
              </div>
            ` : nothing}

            ${this._scenarioStatus ? html`
              <div class="scenario-status ${this._scenarioStatus === 'Done' ? 'done' : ''}">${this._scenarioStatus}</div>
            ` : nothing}

            <div class="step-actions">
              ${this._scenarioStatus === 'Done' ? html`
                <button class="action-btn" @click=${this._nextStep}>
                  ${this._scenarioStep < HELPDESK_SCENARIO.length - 1 ? 'Next' : 'Finish'}
                </button>
              ` : html`
                <button
                  class="action-btn submit-btn"
                  @click=${this._executeStep}
                  ?disabled=${this._scenarioRunning}
                >
                  ${step.action === 'bootstrap' ? 'Load Data' : step.action === 'submit' ? 'Submit' : 'Resolve'}
                </button>
              `}
            </div>
          </div>
        ` : nothing}
      </div>
    `;
  }

  private _renderInfoOverlay() {
    return html`
      <div class="info-overlay" @click=${() => { this._showInfo = false; }}>
        <div class="info-content" @click=${(e: Event) => e.stopPropagation()}>
          <div class="info-header">
            <h2 style="color: var(--pages-neutral-12); font-size: 16px; text-transform: none; letter-spacing: normal;">How This Demo Works</h2>
            <button class="info-close" @click=${() => { this._showInfo = false; }}>&times;</button>
          </div>

          <div class="info-body">
            <p class="info-intro">This helpdesk is a working application built on the CaseHub platform.
            It shows how the platform's strategic capabilities compose to solve a real problem —
            every update arrives via push, every integration point is swappable, and the same
            scenario that drives this demo also runs as an automated test.</p>

            <div class="info-section">
              <h3>What happens when you submit a message</h3>
              <div class="info-flow">
                <span class="flow-step">Chat message</span>
                <span class="flow-arrow">&rarr;</span>
                <span class="flow-step">Qhorus channel</span>
                <span class="flow-arrow">&rarr;</span>
                <span class="flow-step">Work item created</span>
                <span class="flow-arrow">&rarr;</span>
                <span class="flow-step">Classified</span>
                <span class="flow-arrow">&rarr;</span>
                <span class="flow-step">Assigned</span>
                <span class="flow-arrow">&rarr;</span>
                <span class="flow-step">Dashboard updates</span>
              </div>
              <p style="margin-top: 10px;">The entire chain completes in milliseconds. Each stage transition fires
              an event that the push layer broadcasts to all connected browsers in real time.</p>
            </div>

            <div class="info-section">
              <h3>Platform capabilities</h3>
              <div class="info-grid">
                <div class="info-card">
                  <div class="info-card-label">Blocks</div>
                  <div class="info-card-component">Composable case management</div>
                  <div class="info-card-detail">This helpdesk is assembled from platform blocks —
                  ticket creation, classification, assignment, notification. Each block
                  encapsulates one capability with an SPI boundary. Compose different
                  blocks for different domains: insurance claims, patient intake, incident response.</div>
                </div>
                <div class="info-card">
                  <div class="info-card-label">Work</div>
                  <div class="info-card-component">Work item lifecycle</div>
                  <div class="info-card-detail">Each ticket follows a managed lifecycle: created &rarr; classified &rarr;
                  assigned &rarr; resolved. Status transitions, assignment rules, and resolution
                  tracking — the same patterns that power queues, SLAs, and routing at scale.</div>
                </div>
                <div class="info-card">
                  <div class="info-card-label">Qhorus</div>
                  <div class="info-card-component">Agent channels &amp; message routing</div>
                  <div class="info-card-detail">Chat messages arrive through a Qhorus channel connector.
                  The platform routes them to the ticket handler — the same channel abstraction
                  that supports Slack, Teams, email, or multi-agent collaboration.</div>
                </div>
                <div class="info-card">
                  <div class="info-card-label">Engine</div>
                  <div class="info-card-component">Scenario-driven orchestration</div>
                  <div class="info-card-detail">The scenario engine bootstraps SPIs, injects events, and verifies
                  outcomes. The same scenario file drives this interactive demo and runs
                  as an automated test in CI — one format, two purposes.</div>
                </div>
              </div>
            </div>

            <div class="info-section">
              <h3>SPIs — everything is swappable</h3>
              <p>The ticket classifier, notification sender, and chat connector are all
              <strong>Service Provider Interfaces</strong>. This demo loads lightweight demo
              implementations at bootstrap. In production, swap them for ML classifiers,
              real notification channels, and production chat platforms — the application
              code doesn't change. The platform provides the integration surface;
              you provide the implementation.</p>
            </div>

            <div class="info-section">
              <h3>Real-time push — no polling</h3>
              <p>A single WebSocket connection carries all event topics (tickets, metrics,
              notifications). The dashboard subscribes to the topics it cares about and
              reacts to events as they arrive. Disconnect your network and reconnect —
              missed events replay automatically via sequence-numbered cursors.</p>
            </div>
          </div>
        </div>
      </div>
    `;
  }

  static override styles = css`
    :host {
      display: block;
      min-height: 100vh;
      font-family: var(--pages-font-family, system-ui, sans-serif);
      color: var(--pages-neutral-12, #e6edf3);
      background: var(--pages-neutral-1, #0f1117);
    }

    header {
      padding: 16px 24px;
      background: var(--pages-neutral-2, #161b22);
      border-bottom: 1px solid var(--pages-neutral-6, #30363d);
      display: flex;
      align-items: baseline;
      gap: 16px;
    }
    h1 { font-size: 20px; font-weight: 600; margin: 0; }
    .subtitle { font-size: 13px; color: var(--pages-neutral-9, #8b949e); }
    .connection-status {
      margin-left: auto;
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 12px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .connection-status.connected { color: var(--pages-success-9, #3fb950); background: rgba(63, 185, 80, 0.1); }
    .connection-status.connecting, .connection-status.reconnecting { color: var(--pages-warning-9, #d29922); background: rgba(210, 153, 34, 0.1); }
    .connection-status.disconnected { color: var(--pages-danger-9, #f85149); background: rgba(248, 81, 73, 0.1); }

    .split-layout {
      display: grid;
      grid-template-columns: 1fr 340px;
      gap: 0;
      min-height: calc(100vh - 53px);
    }

    .dashboard {
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
      overflow-y: auto;
    }

    .scenario-sidebar {
      border-left: 1px solid var(--pages-neutral-6, #30363d);
      background: var(--pages-neutral-2, #161b22);
      overflow-y: auto;
    }

    .panel {
      background: var(--pages-neutral-2, #161b22);
      border: 1px solid var(--pages-neutral-6, #30363d);
      border-radius: var(--pages-radius-md, 6px);
      padding: 20px;
    }
    h2 { font-size: 12px; font-weight: 600; margin: 0 0 12px; color: var(--pages-neutral-9, #8b949e); text-transform: uppercase; letter-spacing: 0.05em; }

    .empty { font-size: 13px; color: var(--pages-neutral-9, #8b949e); font-style: italic; }

    .notification {
      padding: 8px 12px;
      margin-bottom: 8px;
      background: var(--pages-accent-3, rgba(56, 139, 253, 0.1));
      border-radius: var(--pages-radius-sm, 4px);
      border-left: 3px solid var(--pages-accent-9, #58a6ff);
    }
    .notification strong { font-size: 12px; color: var(--pages-accent-9, #58a6ff); }
    .notification p { font-size: 13px; margin: 4px 0 0; }

    /* Scenario panel */
    .scenario-panel { padding: 20px; }
    .scenario-header { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; }
    .step-indicator {
      font-size: 12px;
      font-weight: 600;
      color: var(--pages-accent-9, #58a6ff);
      padding: 2px 8px;
      background: rgba(56, 139, 253, 0.1);
      border-radius: 12px;
    }

    .step-content { display: flex; flex-direction: column; gap: 12px; }
    .step-label { font-size: 14px; font-weight: 600; color: var(--pages-neutral-12, #e6edf3); }
    .step-description { font-size: 13px; color: var(--pages-neutral-10, #b1bac4); line-height: 1.5; margin: 0; }

    .preview-box {
      background: var(--pages-neutral-3, #1c2128);
      border: 1px solid var(--pages-neutral-6, #30363d);
      border-radius: var(--pages-radius-sm, 4px);
      padding: 12px;
    }
    .preview-label { font-size: 11px; color: var(--pages-neutral-9, #8b949e); text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 6px; }
    .preview-text { font-size: 13px; font-style: italic; color: var(--pages-neutral-11, #c9d1d9); }

    .scenario-status {
      font-size: 12px;
      color: var(--pages-warning-9, #d29922);
      padding: 6px 10px;
      background: rgba(210, 153, 34, 0.1);
      border-radius: var(--pages-radius-sm, 4px);
    }
    .scenario-status.done {
      color: var(--pages-success-9, #3fb950);
      background: rgba(63, 185, 80, 0.1);
    }

    .step-actions { margin-top: 4px; }

    .action-btn {
      display: block;
      width: 100%;
      padding: 10px;
      border: none;
      border-radius: var(--pages-radius-sm, 4px);
      background: var(--pages-accent-9, #58a6ff);
      color: #fff;
      font-weight: 600;
      cursor: pointer;
      font-size: 13px;
      transition: transform var(--pages-duration-fast, 120ms) ease, opacity var(--pages-duration-fast, 120ms) ease;
    }
    .action-btn:hover { opacity: 0.9; }
    .action-btn:active { transform: scale(0.98); }
    .action-btn:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }
    .submit-btn { background: var(--pages-success-9, #238636); }

    .scenario-complete {
      text-align: center;
      padding: 32px 16px;
      color: var(--pages-success-9, #3fb950);
      font-size: 14px;
    }

    .standalone { min-height: 100vh; background: var(--pages-neutral-1, #0f1117); }
    .standalone .scenario-panel { max-width: 480px; margin: 0 auto; }

    /* Info button */
    .info-btn {
      width: 24px; height: 24px;
      border-radius: 50%;
      border: 1px solid var(--pages-neutral-6, #30363d);
      background: transparent;
      color: var(--pages-neutral-9, #8b949e);
      font-size: 14px; font-weight: 700;
      cursor: pointer;
      display: flex; align-items: center; justify-content: center;
      transition: all var(--pages-duration-fast, 120ms) ease;
    }
    .info-btn:hover { border-color: var(--pages-accent-9, #58a6ff); color: var(--pages-accent-9, #58a6ff); }

    /* Info overlay */
    .info-overlay {
      position: fixed; inset: 0; z-index: 1000;
      background: rgba(0, 0, 0, 0.7);
      display: flex; align-items: center; justify-content: center;
      padding: 24px;
      animation: fade-in var(--pages-duration-fast, 120ms) ease;
    }
    @keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }

    .info-content {
      background: var(--pages-neutral-2, #161b22);
      border: 1px solid var(--pages-neutral-6, #30363d);
      border-radius: var(--pages-radius-md, 6px);
      max-width: 640px; width: 100%;
      max-height: 80vh; overflow-y: auto;
    }
    .info-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 16px 20px;
      border-bottom: 1px solid var(--pages-neutral-6, #30363d);
    }
    .info-close {
      background: none; border: none; color: var(--pages-neutral-9, #8b949e);
      font-size: 24px; cursor: pointer; padding: 0 4px;
    }
    .info-close:hover { color: var(--pages-neutral-12, #e6edf3); }
    .info-body { padding: 20px; }
    .info-intro { font-size: 14px; color: var(--pages-neutral-11, #c9d1d9); line-height: 1.6; margin: 0 0 20px; }
    .info-section { margin-bottom: 20px; }
    .info-section h3 {
      font-size: 13px; font-weight: 600; color: var(--pages-accent-9, #58a6ff);
      text-transform: uppercase; letter-spacing: 0.05em;
      margin: 0 0 10px; padding-bottom: 6px;
      border-bottom: 1px solid var(--pages-neutral-6, #30363d);
    }
    .info-section p { font-size: 13px; color: var(--pages-neutral-10, #b1bac4); line-height: 1.6; margin: 0; }
    .info-section code {
      font-family: 'SF Mono', Menlo, monospace; font-size: 12px;
      background: var(--pages-neutral-3, #1c2128); padding: 1px 5px;
      border-radius: 3px; color: var(--pages-accent-9, #58a6ff);
    }

    .info-flow {
      display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
      padding: 12px; background: var(--pages-neutral-3, #1c2128);
      border-radius: var(--pages-radius-sm, 4px);
    }
    .flow-step {
      font-size: 12px; font-weight: 500; padding: 4px 10px;
      background: var(--pages-neutral-4, #21262d); border-radius: 4px;
      color: var(--pages-neutral-12, #e6edf3);
    }
    .flow-arrow { color: var(--pages-neutral-8, #6e7681); font-size: 14px; }

    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .info-card {
      background: var(--pages-neutral-3, #1c2128); border-radius: var(--pages-radius-sm, 4px);
      padding: 12px; border-left: 3px solid var(--pages-accent-9, #58a6ff);
    }
    .info-card-label { font-size: 12px; font-weight: 600; color: var(--pages-neutral-12, #e6edf3); margin-bottom: 4px; }
    .info-card-component { font-size: 12px; font-family: 'SF Mono', Menlo, monospace; color: var(--pages-accent-9, #58a6ff); margin-bottom: 4px; }
    .info-card-detail { font-size: 11px; color: var(--pages-neutral-9, #8b949e); }

    @media (max-width: 900px) {
      .split-layout { grid-template-columns: 1fr; }
      .scenario-sidebar { border-left: none; border-top: 1px solid var(--pages-neutral-6, #30363d); }
      .info-grid { grid-template-columns: 1fr; }
    }
  `;
}
