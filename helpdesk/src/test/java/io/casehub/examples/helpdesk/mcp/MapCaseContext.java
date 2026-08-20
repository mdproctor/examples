package io.casehub.examples.helpdesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextChangeEvent;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.context.Subscription;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

final class MapCaseContext implements CaseContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Object> data;

    MapCaseContext(final Map<String, Object> data) {
        this.data = new HashMap<>(data);
    }

    @Override public ReadableLayer layer(final String name) { throw new UnsupportedOperationException(); }
    @Override public Map<String, Object> getData() { return Collections.unmodifiableMap(data); }
    @Override public CaseContext set(final String key, final Object value) { data.put(key, value); return this; }
    @Override public Object get(final String key) { return data.get(key); }
    @Override public <T> T getAs(final String key, final Class<T> type) { return type.cast(data.get(key)); }
    @Override @SuppressWarnings("unchecked") public <T> T getOrDefault(final String key, final T defaultValue) { Object v = data.get(key); return v != null ? (T) v : defaultValue; }
    @Override public Object computeIfAbsent(final String key, final Function<String, Object> fn) { return data.computeIfAbsent(key, fn); }
    @Override public Object putIfAbsent(final String key, final Object value) { return data.putIfAbsent(key, value); }
    @Override public boolean compareAndSet(final String key, final Object expected, final Object newValue) { if (java.util.Objects.equals(data.get(key), expected)) { data.put(key, newValue); return true; } return false; }
    @Override public CaseContext update(final String key, final Function<Object, Object> fn) { data.compute(key, (k, v) -> fn.apply(v)); return this; }
    @Override public String getString(final String key) { Object v = data.get(key); return v instanceof String s ? s : null; }
    @Override public Integer getInt(final String key) { Object v = data.get(key); return v instanceof Number n ? n.intValue() : null; }
    @Override public Long getLong(final String key) { Object v = data.get(key); return v instanceof Number n ? n.longValue() : null; }
    @Override public Double getDouble(final String key) { Object v = data.get(key); return v instanceof Number n ? n.doubleValue() : null; }
    @Override public Boolean getBoolean(final String key) { Object v = data.get(key); return v instanceof Boolean b ? b : null; }
    @Override @SuppressWarnings("unchecked") public <T> List<T> getList(final String key, final Class<T> elementType) { Object v = data.get(key); return v instanceof List ? (List<T>) v : List.of(); }
    @Override @SuppressWarnings("unchecked") public Object getPath(final String path) { String[] parts = path.split("\\."); Object cur = data; for (String p : parts) { if (cur instanceof Map<?, ?> m) cur = m.get(p); else return null; } return cur; }
    @Override public String getPathAsString(final String path) { Object v = getPath(path); return v instanceof String s ? s : (v != null ? v.toString() : null); }
    @Override public CaseContext setPath(final String path, final Object value) { throw new UnsupportedOperationException(); }
    @Override public Optional<JsonNode> applyAndDiff(final String path, final Object value) { throw new UnsupportedOperationException(); }
    @Override public CaseContext setAll(final Map<String, Object> values) { data.putAll(values); return this; }
    @Override public Map<String, Object> getAll(final String... keys) { Map<String, Object> r = new HashMap<>(); for (String k : keys) if (data.containsKey(k)) r.put(k, data.get(k)); return r; }
    @Override public boolean contains(final String key) { return data.containsKey(key); }
    @Override public CaseContext remove(final String key) { data.remove(key); return this; }
    @Override public CaseContext clear() { data.clear(); return this; }
    @Override public Set<String> getKeys() { return data.keySet(); }
    @Override public boolean isEmpty() { return data.isEmpty(); }
    @Override public int size() { return data.size(); }
    @Override public JsonNode asJsonNode() { return MAPPER.valueToTree(data); }
    @Override public CaseContext merge(final CaseContext other) { data.putAll(other.getData()); return this; }
    @Override public CaseContext snapshot() { return new MapCaseContext(new HashMap<>(data)); }
    @Override public JsonNode diff(final CaseContext other) { throw new UnsupportedOperationException(); }
    @Override public void applyDiff(final JsonNode diff) { throw new UnsupportedOperationException(); }
    @Override public long getVersion() { return 0; }
    @Override public Subscription onChange(final String key, final Consumer<ContextChangeEvent> listener) { return () -> {}; }
    @Override public Subscription onAnyChange(final Consumer<ContextChangeEvent> listener) { return () -> {}; }
}
