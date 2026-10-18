package com.nekyoh.nekylauncher.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed, null-tolerant view over a JSON object. Mutations write through to the backing map. */
public final class JObject {
    private final Map<String, Object> map;

    public JObject() { this(new LinkedHashMap<>()); }

    public JObject(Map<String, Object> map) { this.map = map; }

    public static JObject parse(String json) {
        Object o = Json.parse(json);
        if (o instanceof Map<?, ?>) return wrap(o);
        throw new Json.JsonException("Expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    static JObject wrap(Object o) { return new JObject((Map<String, Object>) o); }

    public Map<String, Object> raw() { return map; }

    public boolean has(String key) { return map.containsKey(key) && map.get(key) != null; }

    public Set<String> keys() { return map.keySet(); }

    public int size() { return map.size(); }

    public Object get(String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?>) return wrap(v);
        if (v instanceof List<?>) return JArray.wrap(v);
        return v;
    }

    public String str(String key) { return str(key, null); }

    public String str(String key, String def) {
        Object v = map.get(key);
        if (v == null) return def;
        if (v instanceof String s) return s;
        if (v instanceof Map || v instanceof List) return def;
        if (v instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue());
        return v.toString();
    }

    public long lng(String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    public int integer(String key, int def) { return (int) lng(key, def); }

    public double dbl(String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    public boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    /** Nested object or {@code null}. */
    public JObject obj(String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> ? wrap(v) : null;
    }

    /** Nested object, or a detached empty object when absent. */
    public JObject objOrEmpty(String key) {
        JObject o = obj(key);
        return o != null ? o : new JObject();
    }

    public JArray arr(String key) {
        Object v = map.get(key);
        return v instanceof List<?> ? JArray.wrap(v) : null;
    }

    public JArray arrOrEmpty(String key) {
        JArray a = arr(key);
        return a != null ? a : new JArray();
    }

    public JObject put(String key, Object value) {
        map.put(key, Json.unwrap(value));
        return this;
    }

    public JObject remove(String key) {
        map.remove(key);
        return this;
    }

    /** Deep copy. */
    public JObject copy() { return parse(toJson(false)); }

    public String toJson(boolean pretty) { return Json.write(map, pretty); }

    @Override
    public String toString() { return toJson(false); }
}
