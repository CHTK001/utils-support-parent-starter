package com.chua.protocol.support.network.protocol.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request parameters stub.
 * @author CH
 */
public class RequestParameters {
    private final Map<String, List<String>> params = new LinkedHashMap<>();

    public void add(String name, String value) {
        if (name == null) {
            return;
        }
        params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    public Map<String, List<String>> asMap() {
        return params;
    }
}
