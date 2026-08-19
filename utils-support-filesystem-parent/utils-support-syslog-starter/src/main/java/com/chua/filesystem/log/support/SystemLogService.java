package com.chua.filesystem.log.support;

import com.chua.filesystem.log.support.bridge.PlatformSystems;
import com.chua.filesystem.log.support.bridge.SystemLogBridge;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.spi.SystemLogProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 系统日志服务门面 - 跨平台系统日志检索的统一入口
 * <p>
 * 自动检测当前运行平台并选择对应的 SystemLogProvider 实现：
 * <ul>
 *   <li>Windows : WindowsEventLogProvider (advapi32 FFM)</li>
 *   <li>Linux   : LinuxJournaldProvider (libsystemd FFM + /var/log)</li>
 *   <li>macOS   : MacOSUnifiedLogProvider (log show + /var/log)</li>
 * </ul>
 * </p>
 *
 * <h3>快速使用：</h3>
 * <pre>{@code
 * // 搜索包含 "disk" 的错误日志，最多返回 50 条
 * List<LogEntry> entries = SystemLogService.getInstance()
 *         .search("disk", LogLevel.ERROR, 50);
 *
 * // 高级查询
 * LogQuery query = LogQuery.builder()
 *         .source("System")
 *         .pattern("*.dll")
 *         .minLevel(LogLevel.WARNING)
 *         .maxResults(100)
 *         .build();
 * List<LogEntry> results = SystemLogService.getInstance().search(query);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SystemLogService {

    /** INSTANCE */
    private static volatile SystemLogService INSTANCE;

    /** 提供者 */
    private final SystemLogProvider provider;

    /** initialized */
    private volatile boolean initialized;

    /** 创建 SystemLogService 实例 */
    public SystemLogService() {
        SystemLogProvider p = null;

        // 1. 尝试通过 ServiceLoader 发现
        try {
            ServiceLoader<SystemLogProvider> loader = ServiceLoader.load(SystemLogProvider.class);
            Optional<SystemLogProvider> found = loader.stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(SystemLogProvider::isPlatformSupported)
                    .findFirst();
            if (found.isPresent()) {
                p = found.get();
                log.info("SystemLogProvider loaded via ServiceLoader: {}", p.getClass().getName());
            }
        } catch (Exception e) {
            log.trace("ServiceLoader discovery skipped: {}", e.getMessage());
        }

        // 2. 回退到桥接层创建
        if (p == null) {
            try {
                SystemLogBridge bridge = SystemLogBridge.getInstance();
                bridge.initialize();
                p = bridge.createProvider();
                log.info("SystemLogProvider created via bridge: {}", p.getClass().getName());
            } catch (Exception e) {
                log.warn("Failed to create SystemLogProvider: {}", e.getMessage());
            }
        }

        this.provider = p;
        this.initialized = true;
    }

    /** 获取Instance */
    public static SystemLogService getInstance() {
        if (INSTANCE == null) {
            synchronized (SystemLogService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SystemLogService();
                }
            }
        }
        return INSTANCE;
    }

    /** 是否Available */
    public boolean isAvailable() {
        return provider != null && provider.isPlatformSupported();
    }

    /** 搜索 */
    public List<LogEntry> search(String pattern) {
        return search(pattern, null);
    }

    /** 搜索 */
    public List<LogEntry> search(String pattern, LogLevel minLevel) {
        return search(pattern, minLevel, 100);
    }

    /** 搜索 */
    public List<LogEntry> search(String pattern, LogLevel minLevel, int maxResults) {
        LogQuery query = LogQuery.builder()
                .pattern(pattern)
                .minLevel(minLevel)
                .maxResults(maxResults)
                .build();
        return search(query);
    }

    /** 搜索 */
    public List<LogEntry> search(LogQuery query) {
        if (!isAvailable()) {
            log.warn("SystemLogService not available on {}", PlatformSystems.getOsName());
            return Collections.emptyList();
        }
        try {
            return provider.search(query);
        } catch (Exception e) {
            log.error("Error searching system logs: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** 获取Sources */
    public List<String> getSources() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        return provider.getSources();
    }

    /** 获取Provider */
    public SystemLogProvider getProvider() {
        return provider;
    }
}
