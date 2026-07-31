package com.chua.protocol.support.network.protocol.request;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Request/response headers stub.
 * @author CH
 */
public class RequestHeaders {
    private final Map<String, List<String>> headers = new LinkedHashMap<>();

    public void add(String name, String value) {
        if (name == null) {
            return;
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    public void set(String name, String value) {
        if (name == null) {
            return;
        }
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
    }

    public void clear() {
        headers.clear();
    }

    public boolean isEmpty() {
        return headers.isEmpty();
    }

    public Set<String> getNames() {
        return headers.keySet();
    }

    public List<String> getAll(String name) {
        if (name == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() == null ? List.of() : e.getValue();
            }
        }
        return List.of();
    }

    public String getFirst(String name) {
        List<String> all = getAll(name);
        return all.isEmpty() ? null : all.get(0);
    }

    public String getContentType() {
        return getFirst("Content-Type");
    }

    public Collection<Map.Entry<String, List<String>>> entries() {
        return headers.entrySet();
    }
}
