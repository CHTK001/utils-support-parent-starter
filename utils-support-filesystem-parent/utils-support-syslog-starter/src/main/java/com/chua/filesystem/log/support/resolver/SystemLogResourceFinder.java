package com.chua.filesystem.log.support.resolver;

import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.file.resource.ResourceFinder;
import com.chua.filesystem.log.support.bridge.SystemLogBridge;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.spi.SystemLogProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * 系统 日志 resource查找 implementation - 注册 "syslog:" 协议
 * <p>
 * Integrates 系统 日志 retrieval into the resource提供者 框架,
 * 查询 系统 日志 on each platform via the unified syslog: 协议.
 * </p>
 *
 * <h3>Ant-style glob syntax:</h3>
 * <pre>
 * syslog:*error*              - messages containing "error" (case-insensitive)
 * syslog:*error*&amp;maxResults=10  - same, limit to 10 results
 * syslog:disk                  - exact match "disk"
 * syslog:?error               - "error" with any single leading char
 * </pre>
 *
 * <h3>Legacy ?key=value syntax (still supported):</h3>
 * <pre>
 * syslog:?pattern=*error*&amp;maxResults=50
 * syslog:?pattern=*disk*&amp;level=ERROR
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SystemLogResourceFinder implements ResourceFinder {

    /**
     * 协议 名称
     */
    public static final String PROTOCOL = "syslog";

    /**
     * 提供者, lazily 初始化
     */
    private volatile SystemLogProvider provider;

    @Override
    /** 查找 */
    public Set<Resource> find(String name) {
        LogQuery query = parseQuery(name);
        if (query == null) {
            log.warn("Invalid syslog query: {}", name);
            return Set.of();
        }

        SystemLogProvider provider = getOrCreateProvider();
        if (provider == null) {
            log.warn("No SystemLogProvider available for current platform");
            return Set.of();
        }

        List<LogEntry> entries = provider.search(query);

        return entries.stream()
                .map(this::toResource)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Nullable
    /**
    * 解析查询
    *
    * @param raw raw
    * @return 解析查询的结果
    */
    private LogQuery parseQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String withoutProtocol = raw;
        if (withoutProtocol.startsWith(PROTOCOL + ":")) {
            withoutProtocol = withoutProtocol.substring((PROTOCOL + ":").length());
        }

        if (withoutProtocol.isBlank()) {
            return LogQuery.of("*");
        }

        if (withoutProtocol.contains("?")) {
            return parseQueryParamStyle(withoutProtocol);
        }

        return parseGlobStyle(withoutProtocol);
    }

    /**
     * 解析globstyle
     *
     * @param s s
     * @return 解析globstyle的结果
     */
    private LogQuery parseGlobStyle(String s) {
        int maxResults = 100;
        String order = LogQuery.ORDER_DESC;
        String globPart = s;

        int ampIdx = globPart.indexOf('&');
        if (ampIdx >= 0) {
            String paramsPart = globPart.substring(ampIdx + 1);
            globPart = globPart.substring(0, ampIdx);
            for (String pair : paramsPart.split("&")) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx > 0) {
                    String key = pair.substring(0, eqIdx).toLowerCase();
                    String val = pair.substring(eqIdx + 1);
                    switch (key) {
                        case "maxresults", "max", "size" -> {
                            try {
                                maxResults = Integer.parseInt(val);
                            } catch (NumberFormatException ignored) {}
                        }
                        case "order" -> {
                            if (LogQuery.ORDER_ASC.equalsIgnoreCase(val)
                                    || LogQuery.ORDER_DESC.equalsIgnoreCase(val)) {
                                order = val.toLowerCase();
                            }
                        }
                    }
                }
            }
        }

        return LogQuery.builder()
                .pattern(globPart)
                .maxResults(maxResults)
                .order(order)
                .build();
    }

    /**
     * 解析查询参数style
     *
     * @param withGlob withglob
     * @return 解析查询参数style的结果
     */
    private LogQuery parseQueryParamStyle(String withGlob) {
        String queryString;
        int qmarkIdx = withGlob.indexOf('?');
        if (qmarkIdx >= 0) {
            queryString = withGlob.substring(qmarkIdx + 1);
        } else {
            queryString = "";
        }

        Map<String, String> params = new LinkedHashMap<>();
        if (!queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx > 0) {
                    params.put(
                            urlDecode(pair.substring(0, eqIdx)).toLowerCase(),
                            urlDecode(pair.substring(eqIdx + 1))
                    );
                }
            }
        }

        LogQuery.Builder builder = LogQuery.builder();

        builder.source(params.get("source"));
        builder.pattern(params.get("pattern"));

        String levelStr = params.get("level");
        if (levelStr != null) {
            try {
                builder.minLevel(LogLevel.valueOf(levelStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid log level: {}, ignoring", levelStr);
            }
        }

        String maxStr = params.get("maxresults");
        if (maxStr != null) {
            try {
                builder.maxResults(Integer.parseInt(maxStr));
            } catch (NumberFormatException ignored) {
            }
        }

        String orderStr = params.get("order");
        if (orderStr != null
                && (LogQuery.ORDER_ASC.equalsIgnoreCase(orderStr)
                    || LogQuery.ORDER_DESC.equalsIgnoreCase(orderStr))) {
            builder.order(orderStr.toLowerCase());
        }

        return builder.build();
    }

    /**
     * 获取或创建提供者
     *
     * @return 获取或创建提供者的结果
     */
    private SystemLogProvider getOrCreateProvider() {
        if (provider != null) {
            return provider;
        }
        synchronized (this) {
            if (provider != null) {
                return provider;
            }
 // 尝试 SPI discovery via 服务加载
            ServiceLoader<SystemLogProvider> loader = ServiceLoader.load(SystemLogProvider.class);
            Optional<SystemLogProvider> found = loader.stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(SystemLogProvider::isPlatformSupported)
                    .findFirst();
            if (found.isPresent()) {
                provider = found.get();
            } else {
 // 降级 转为 bridge 创建
                SystemLogBridge bridge = SystemLogBridge.getInstance();
                bridge.initialize();
                provider = bridge.createProvider();
            }
            return provider;
        }
    }

    /**
     * 转为resource
     *
     * @param entry entry
     * @return 转为resource的结果
     */
    private Resource toResource(LogEntry entry) {
        String content = String.format("[%s] [%s] [%s] %s",
                entry.timestamp(),
                entry.level().name(),
                entry.source(),
                entry.message()
        );
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new VirtualResourceImpl(entry, bytes);
    }

    /**
     * 虚拟resourceimpl
     *
     * @param entry entry
     * @param content 内容
     * @return 虚拟resourceimpl的结果
     */
    private record VirtualResourceImpl(LogEntry entry, byte[] content) implements Resource {

        @Override
        /** 打开流 */
        public InputStream openStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        /** 获取url路径 */
        public String getUrlPath() {
        
            return "syslog-" + entry.timestamp() + "-" + entry.source();
        
    }

        @Override
        /** 获取Url */
        public URL getUrl() {
            try {
                return new URL("syslog", "", getUrlPath());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create syslog URL", e);
            }
        }

        @Override
        /** 最后一个modified */
        public long lastModified() {
        
            return 0;
        
    }

        @Override
        /** 转为字符串 */
        public String toString() {
        
            return new String(content, StandardCharsets.UTF_8);
        
    }
    }

    /**
        * Url解码
        *
        * @param s s
        * @return urlDecode的结果
        */
    private static String urlDecode(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("+", " ")
                .replace("%20", " ")
                .replace("%2A", "*")
                .replace("%3F", "?")
                .replace("%26", "&");
    }
}

