# Server-Dependent Examples Tab in Showcase Gallery

**Issue:** #294
**Date:** 2026-08-20
**Revised:** 2026-08-20 — addressed R1-02 through R1-16 from light review

## Problem

The examples gallery runs entirely client-side. With durable EventStore backends (#113) landed, there's no way to demonstrate push subscriptions, event replay, or durable persistence without a running server.

## Design

### 1. Gallery UI — Top-Level Tabs

Two tabs above the category sidebar: **Client** (default, existing samples) and **Server** (new).

**`samples.json` change:** Each category gains an optional `requiresServer: boolean` field. When absent or false, the category appears under the Client tab. When true, under the Server tab.

```json
{
  "category": "Live Data",
  "requiresServer": true,
  "samples": [...]
}
```

**Tab rendering** in `app.js`:
- Two tab buttons above `#categories-nav`
- Active tab filters `samplesData.categories` by `requiresServer`
- Tab state persisted in the URL hash prefix: `#server/Live%20Data/sample-path` vs `#client/Charts/sample-path` (backward compatible — bare `#Charts/sample-path` defaults to Client)

**Server connection indicator:** When the Server tab is active, a status dot in the tab bar. On tab switch to Server, fire a health check against a proxied path:
```javascript
fetch('/api/demo/health').then(r => r.ok ? setConnected() : setDisconnected())
                         .catch(() => setDisconnected());
```
Disconnected state shows: "Server not running — start with `docker compose up` in `examples/`"

The health endpoint is a simple REST resource in the demo server (not Quarkus SmallRye Health — avoids an extra dependency).

**Sample count per tab:** The stats display updates to show the count for the active tab only.

### 2. Backend — `examples/server/`

Minimal Quarkus app. Dependencies:
- `casehub-pages-push-runtime` (CDI producers for EventBroadcaster, TopicRegistry, EventStore)
- `casehub-pages-push-store-jdbc` (Postgres durable store — overrides `@DefaultBean InMemoryEventStore`)
- `quarkus-websockets-next` (WebSocket endpoint for push protocol)
- `quarkus-jdbc-postgresql` + `quarkus-agroal` (Postgres connection)
- `quarkus-scheduler` (for `@Scheduled` demo event generation)

**JDBC only (R1-06):** The demo server uses `push-store-jdbc` with Postgres. No Redis — one fewer infrastructure dependency, simpler Docker Compose, and avoids the CDI priority conflict where both stores on the classpath results in only Redis being active. The persistence demo works identically with JDBC.

#### WebSocket Endpoint (`PushWebSocket.java`)

The WebSocket endpoint bridges the push SDK to WebSocket transport. Topic registration is message-driven per the push protocol — the client sends `listen`/`unlisten` messages after the connection opens.

```java
@WebSocket(path = "/ws/push")
public class PushWebSocket {
    @Inject EventBroadcaster broadcaster;
    @Inject TopicRegistry topicRegistry;
    @Inject EventStore eventStore;

    private final ConcurrentHashMap<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        connections.put(connection.id(), connection);
    }

    @OnTextMessage
    void onMessage(WebSocketConnection connection, String message) {
        PushRequest request = PushRequest.parse(message);
        switch (request) {
            case PushRequest.Listen listen -> handleListen(connection, listen);
            case PushRequest.Unlisten unlisten -> handleUnlisten(connection, unlisten);
            default -> { /* Subscribe/Unsubscribe/CommandResult — not used by event demos */ }
        }
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        topicRegistry.removeConnection(connection.id());
        connections.remove(connection.id());
    }

    private void handleListen(WebSocketConnection conn, PushRequest.Listen listen) {
        topicRegistry.listen(conn.id(), listen.topics());

        // Replay from EventStore for reconnect (since map has per-topic seq numbers)
        List<String> gaps = new ArrayList<>();
        for (var entry : listen.since().entrySet()) {
            String topic = entry.getKey();
            long sinceSeq = entry.getValue();
            List<StoredEvent> replayed = eventStore.replay(topic, sinceSeq, 500);
            if (replayed.isEmpty() && sinceSeq > 0) {
                gaps.add(topic);
            }
            for (StoredEvent event : replayed) {
                conn.sendText(event.payload());
            }
        }

        // Send ack with resolved topics and any gap topics
        conn.sendText(PushMessage.ack(listen.id(), listen.topics(), gaps));
    }

    private void handleUnlisten(WebSocketConnection conn, PushRequest.Unlisten unlisten) {
        topicRegistry.unlisten(conn.id(), unlisten.topics());
    }
}
```

#### SessionSender Bridge (`WebSocketSessionSender.java`)

Bridges `SessionSender` (push SDK) to WebSocket connections using a shared connection map:

```java
@ApplicationScoped
public class WebSocketSessionSender implements SessionSender {
    private final ConcurrentHashMap<String, WebSocketConnection> connections;

    // Shared with PushWebSocket via CDI event or @Inject
    public void send(String connectionId, String message) {
        WebSocketConnection conn = connections.get(connectionId);
        if (conn != null) {
            conn.sendText(message);
        }
    }
}
```

The connection map is shared between `PushWebSocket` and `WebSocketSessionSender` — either via a shared `@ApplicationScoped` connection registry bean, or by having `WebSocketSessionSender` use `OpenConnections` from quarkus-websockets-next.

#### Demo Data Generator (`DemoEventGenerator.java`)

- REST endpoint `POST /api/demo/generate` — generates a burst of events on configurable topics
- REST endpoint `GET /api/demo/health` — returns `{"status":"ok","storeType":"jdbc"}` for health check and store info
- REST endpoint `GET /api/demo/info` — returns EventStore state: total events, oldest timestamp, store type
- Scheduled timer `@Scheduled(every = "2s")` — continuous stream of demo events on `demo:events`, `demo:metrics`, `demo:alerts`
- Events are realistic: timestamps, random metrics, status changes, alert levels

**Topic naming (R1-03):** All topics use `:` as the segment delimiter per platform convention: `demo:events`, `demo:metrics`, `demo:alerts`, `demo:persistence`. Wildcard subscriptions use `demo:*` (single-segment) or `demo:**` (multi-segment).

**`application.properties`:**
```properties
quarkus.http.port=8090
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/pages_demo
quarkus.datasource.username=pages
quarkus.datasource.password=pages
casehub.pages.push.max-events-per-topic=500
```

### 3. Docker Compose — `examples/docker-compose.yml`

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: pages_demo
      POSTGRES_USER: pages
      POSTGRES_PASSWORD: pages
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U pages -d pages_demo"]
      interval: 5s
      timeout: 3s
      retries: 5

  server:
    build: ./server
    depends_on:
      postgres:
        condition: service_healthy
    ports: ["8090:8090"]
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/pages_demo
```

No Redis service — JDBC-only simplifies the stack to Postgres + Quarkus.

### 4. Server-Tab Example Dashboards

Four YAML samples in `examples/samples/Server/`. Each uses the push WebSocket source with `:` topic delimiters.

**YAML push source syntax (R1-10):**
```yaml
datasets:
  - id: events
    push:
      url: ws://localhost:8090/ws/push
      topics: ["demo:events"]
```

The `push:` key activates `PushSource` in the data pipeline. `topics` is the list of topics to listen on. The runtime sends a `listen` message with these topics after the WebSocket connects.

**4a. Live Event Stream** (`live-events.yaml`)
```yaml
datasets:
  - id: events
    push:
      url: ws://localhost:8090/ws/push
      topics: ["demo:events"]
pages:
  - name: Live Events
    components:
      - type: event-timeline
        lookup: { dataSetId: events }
      - type: metric
        props: { label: "Events", expression: "#{datasets.events.rowCount}" }
```

**4b. Reconnect Replay** (`reconnect-replay.yaml`)
- Same `demo:events` subscription
- Companion TypeScript file (`Reconnect Replay.ts`) provides disconnect/reconnect logic via a `hostPanel` that controls the WebSocket lifecycle
- On reconnect, the `listen` message includes `since: { "demo:events": lastSeq }` — EventStore replays missed events

**4c. Persistence Demo** (`persistence.yaml`)
- Subscribes to `demo:persistence` topic
- Fetches EventStore info from `GET /api/demo/info` (REST endpoint returning `{ totalEvents, oldestTimestamp, storeType }`)
- Instructions text: "Restart the server, then reload — events survive"

**4d. Multi-Topic Dashboard** (`multi-topic.yaml`)
- Wildcard subscription `demo:*` — receives events from all demo topics
- Cross-topic data table with a topic column
- Selector component filtering by topic prefix
- Metric tiles per topic

### 5. Webpack Dev Server Configuration

`examples/webpack.config.js` gains a complete `devServer` block (none exists currently):

```javascript
module.exports = {
  // ... existing config ...
  devServer: {
    port: 8080,
    proxy: [
      {
        context: ['/api/', '/ws/'],
        target: 'http://localhost:8090',
        ws: true,
        changeOrigin: true,
      }
    ],
    historyApiFallback: true,
  }
};
```

This proxies `/ws/push`, `/api/demo/*` to the Quarkus server (port 8090). The gallery runs on port 8080 via `yarn serve`.

## Package Changes

| Location | Change | New |
|----------|--------|-----|
| `examples/src/app.js` | Tab UI, server status indicator, tab-filtered categories | No |
| `examples/src/index.html` | Tab markup | No |
| `examples/src/styles.css` | Tab styles, status dot | No |
| `examples/samples.json` | `requiresServer` field on new categories | No |
| `examples/samples/Server/` | 4 YAML dashboards + companion TS for reconnect | Yes |
| `examples/server/` | Quarkus app (pom.xml, WebSocket endpoint, SessionSender, DemoEventGenerator) | Yes |
| `examples/docker-compose.yml` | Postgres + Quarkus | Yes |
| `examples/webpack.config.js` | Full `devServer` block with proxy | No |

## Testing

### Gallery UI
1. Tab switching shows correct categories (client vs server)
2. URL hash with `server/` prefix loads server tab
3. Backward-compatible: bare `#Charts/sample` still works
4. Server status indicator shows disconnected when backend not running
5. Sample count updates per tab

### Backend
6. WebSocket endpoint handles `Listen` request — calls `topicRegistry.listen()`
7. WebSocket endpoint handles `Unlisten` request — calls `topicRegistry.unlisten()`
8. WebSocket endpoint replays events from EventStore on reconnect (since map)
9. DemoEventGenerator produces events on scheduled interval
10. Events persist in Postgres via JdbcEventStore
11. `GET /api/demo/health` returns store info
12. `GET /api/demo/info` returns EventStore state

### Integration
13. `docker compose up` starts Postgres + Quarkus (healthcheck-gated)
14. Gallery with Server tab loads live data from backend
15. Disconnect/reconnect shows replay behavior
16. Server restart preserves events (persistence demo)

## Out of Scope

- Authentication on the demo server
- Production deployment configuration
- EventStore retention/cleanup policies (configurable via existing `max-events-per-topic`)
- Redis EventStore (JDBC sufficient for demo; Redis can be added later as an optional profile)

## References

- examples/src/app.js — current gallery application
- examples/samples.json — sample registry
- backend/push/src/main/java/io/casehub/pages/push/EventBroadcaster.java — broadcast engine
- backend/push/src/main/java/io/casehub/pages/push/EventStore.java — store SPI
- backend/push/src/main/java/io/casehub/pages/push/PushRequest.java — sealed request types (Listen, Unlisten, Subscribe, Unsubscribe, CommandResult)
- backend/push/src/main/java/io/casehub/pages/push/PushMessage.java — server-to-client message builders
- backend/push/src/main/java/io/casehub/pages/push/TopicRegistry.java — listen(), unlisten(), removeConnection()
- backend/push/src/main/java/io/casehub/pages/push/StoredEvent.java — replay payload
- backend/push-runtime/src/main/java/io/casehub/pages/push/runtime/PushProducers.java — CDI producers
- backend/push-store-jdbc/ — JDBC durable store
- backend/push/src/main/java/io/casehub/pages/push/SessionSender.java — WebSocket bridge SPI
- packages/pages-data/src/push/ — client-side EventConnection, PushSource, topic-matching
- docs/specs/2026-07-05-tokens-and-push-protocol-maturation-design.md — wire protocol, seq numbers
- docs/specs/2026-07-08-push-runtime-cdi-design.md — CDI producers, SessionSender contract
- docs/specs/2026-07-07-event-mode-push-api-design.md — event mode
- GitHub #113 — durable EventStore (landed)
- GitHub #294 — this issue
