package com.nekyoh.nekylauncher.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Typed view over a JSON array. */
public final class JArray implements Iterable<Object> {
    private final List<Object> list;

    public JArray() { this(new ArrayList<>()); }

    public JArray(List<Object> list) { this.list = list; }

    @SuppressWarnings("unchecked")
    static JArray wrap(Object o) { return new JArray((List<Object>) o); }

    public static JArray parse(String json) {
        Object o = Json.parse(json);
        if (o instanceof List<?>) return wrap(o);
        throw new Json.JsonException("Expected a JSON array");
    }

    public List<Object> raw() { return list; }

    public int size() { return list.size(); }

    public boolean isEmpty() { return list.isEmpty(); }

    public Object get(int i) {
        Object v = list.get(i);
        if (v instanceof Map<?, ?>) return JObject.wrap(v);
        if (v instanceof List<?>) return wrap(v);
        return v;
    }

    public JObject obj(int i) {
        Object v = list.get(i);
        return v instanceof Map<?, ?> ? JObject.wrap(v) : null;
    }

    public String str(int i) {
        Object v = list.get(i);
        return v == null ? null : v.toString();
    }

    /** All elements that are objects. */
    public List<JObject> objects() {
        List<JObject> out = new ArrayList<>(list.size());
        for (Object v : list) if (v instanceof Map<?, ?>) out.add(JObject.wrap(v));
        return out;
    }

    /** All scalar elements as strings. */
    public List<String> strings() {
        List<String> out = new ArrayList<>(list.size());
        for (Object v : list) if (v != null && !(v instanceof Map) && !(v instanceof List)) out.add(v.toString());
        return out;
    }

    public JArray add(Object v) {
        list.add(Json.unwrap(v));
        return this;
    }

    @Override
    public Iterator<Object> iterator() {
        Iterator<Object> it = list.iterator();
        return new Iterator<>() {
            public boolean hasNext() { return it.hasNext(); }
            public Object next() {
                Object v = it.next();
                if (v instanceof Map<?, ?>) return JObject.wrap(v);
                if (v instanceof List<?>) return wrap(v);
                return v;
            }
        };
    }

    public String toJson(boolean pretty) { return Json.write(list, pretty); }

    @Override
    public String toString() { return toJson(false); }
}
