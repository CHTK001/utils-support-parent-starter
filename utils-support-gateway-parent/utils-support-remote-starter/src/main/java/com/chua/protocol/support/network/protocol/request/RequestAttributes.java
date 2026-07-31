package com.chua.protocol.support.network.protocol.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request attributes stub.
 * @author CH
 */
public class RequestAttributes {
    private final Map<String, Object> attrs = new LinkedHashMap<>();

    public void set(String name, Object value) {
        attrs.put(name, value);
    }

    public Object get(String name) {
        return attrs.get(name);
    }
}
