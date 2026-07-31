package com.chua.protocol.support.network.protocol.utils;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.protocol.support.network.protocol.server.ProtocolServer;

/**
 * Minimal protocol server utility stub. * @author CH
 */
public final class ProtocolServerUtils {
    private ProtocolServerUtils() {
    }

    public static ProtocolServer openProxyServer(String type, ServerSetting setting) {
        return null;
    }

    public static String proxyProtocolTypesJson() {
        return "[]";
    }

    public static String gatewaySpiProvidersJson() {
        return "{}";
    }
}
