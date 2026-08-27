---
layout: post
title: "The Connector That Was Missing"
date: 2026-07-19
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [data-pipeline, refactoring, architecture]
---

## Two paths for one job

DataSourceController and DataPipeline both managed DataSource lifecycle independently — connect a source, create a sink, guard against stale events, disconnect on teardown. The controller did it inline with its own `handleEvent()` method, duplicating the snapshot/append/replace/remove logic that DataSetManager already handles. The pipeline did it through a `connectedSources` Map with its own sink factory.

The problem wasn't that either path was wrong. Both worked. The problem was that controller-owned data never touched DataSetManager — no refresh timers, no cross-filtering, no eviction. Two parallel data flows through the same system, with no connection between them.

## SourceConnector

The fix was extracting what both paths had in common: the connect/disconnect/replace/refresh lifecycle with a stale-source guard. `SourceConnector` is a 90-line primitive in pages-data that manages one DataSource for one DataSetId, feeding events to a DataSetManager through a DataSink.

The stale-source guard uses a generation counter rather than captured source references — simpler, and immune to the identity-comparison edge case where a source object is reused after disconnect.

```typescript
const sink: DataSink = {
  apply(event) {
    if (generation !== capturedGen) return;
    manager.apply(id, event);
    options?.onEvent?.(event);
  },
};
```

The `onEvent` callback was an afterthought that turned out to be necessary. DataSetManager's `onChanged` fires with `(id, dataset)` but doesn't propagate event metadata like `totalRows` from snapshot events. Standalone consumers need that metadata — the callback passes through the raw event after it's been applied.

## What DataSourceController became

The controller shed `connect()`, `disconnect()`, `handleEvent()`, `connectSource()`, `disconnectSource()`, and the `_source` and `_connected` fields. What remains: Declaration (endpoint, sourceFactory, createSource, toBinding) and VizTarget state (loading, dataSet, error, totalRows, activeSort, activePage).

A `connector` property controls lifecycle. In standalone mode, `createStandaloneConnector` wires a controller to its own DataSetManager. In pipeline mode, the controller has no connector — the pipeline owns one and delivers data through the VizTarget interface.

The endpoint setter auto-connects when a connector is present:

```typescript
set endpoint(url: string | undefined) {
  if (url === this._endpoint) return;
  this._endpoint = url;
  if (this._connector) {
    if (url) {
      const source = this.createSource();
      if (source) this._connector.replace(source);
    } else {
      this._connector.disconnect();
    }
  }
}
```

## Pipeline adoption

The pipeline replaced its `connectedSources` Map with SourceConnector instances. `handleBindingRequest` gained source replacement (connector detects a different source in the binding), stale-while-revalidate (checks cache age against binding TTL), and polling refresh via `binding.refreshTime`.

The `pages-data-request` event detail now carries an optional `binding` field. When present, the pipeline routes directly to the binding handler — bypassing scope resolution. This is how controller-produced sources enter the pipeline without being declared in YAML.

The garden entry on refresh recursion (GE-20260714-b6ec65) was in mind throughout. The pipeline's `onChanged` callback calls `deliverDataSet` → `pushData`, which reads from the manager and delivers to VizTargets. No cycle — `pushData` never triggers a refresh, so `connector.refresh()` → `manager.apply()` → `onChanged` → `deliverDataSet` terminates cleanly.
