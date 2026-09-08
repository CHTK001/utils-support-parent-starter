package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;

/**
 * Debug tool to inspect TcpServer filter chain state.
 */
public class DebugTcpServer {
    public static void main(String[] args) throws Exception {
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(0);
        setting.setHost("127.0.0.1");
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(bytes -> bytes);
        server.registerMapping("/hello", (req, res) -> res.end("hello"));

        // Use reflection to check private fields
        java.lang.reflect.Field urlField = JdkTcpServer.class.getSuperclass()
                .getDeclaredField("urlMappingFilter");
        urlField.setAccessible(true);
        UrlMappingServerFilter umf = (UrlMappingServerFilter) urlField.get(server);

        System.out.println("Before start:");
        System.out.println("  urlMappingFilter=null: " + (umf == null));
        if (umf != null) {
            System.out.println("  routeCount: " + umf.getFactory().routeCount());
        }
        System.out.println("  filters size: " + server.getFilters().size());
        for (ServerFilter f : server.getFilters()) {
            System.out.println("    " + f.getClass().getSimpleName() + " order=" + f.getOrder()
                    + " protocols=" + java.util.Arrays.toString(f.supportProtocols()));
        }

        server.start();
        System.out.println("\nAfter start:");
        umf = (UrlMappingServerFilter) urlField.get(server);
        System.out.println("  urlMappingFilter=null: " + (umf == null));
        if (umf != null) {
            System.out.println("  routeCount: " + umf.getFactory().routeCount());
        }
        System.out.println("  filters size: " + server.getFilters().size());
        for (ServerFilter f : server.getFilters()) {
            System.out.println("    " + f.getClass().getSimpleName() + " order=" + f.getOrder()
                    + " protocols=" + java.util.Arrays.toString(f.supportProtocols()));
        }

        server.stop();
    }
}
