package com.chua.filesystem.log.support.bridge;

import com.chua.filesystem.log.support.spi.SystemLogProvider;
import com.chua.filesystem.log.support.spi.impl.LinuxJournaldProvider;
import com.chua.filesystem.log.support.spi.impl.MacOSUnifiedLogProvider;
import com.chua.filesystem.log.support.spi.impl.WindowsEventLogProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统日志桥接器 - 统一管理各平台 FFM FunctionRegistry
 * <p>
 * 负责：
 * <ul>
 *   <li>识别当前运行平台</li>
 *   <li>初始化对应平台的 FFM 绑定</li>
 *   <li>提供统一的原生函数注册表</li>
 * </ul>
 * </p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public final class SystemLogBridge {

    /** 实例 */
    private static final SystemLogBridge INSTANCE = new SystemLogBridge();

    /** win32Registry */
    private volatile NativeFunctionRegistry win32Registry;
    /** linuxRegistry */
    private volatile NativeFunctionRegistry linuxRegistry;
    /** initialized */
    private volatile boolean initialized;

    /** 创建 SystemLogBridge 实例 */
    private SystemLogBridge() {
    }

    /** 获取Instance */
    public static SystemLogBridge getInstance() {
        return INSTANCE;
    }

    /** 初始化 */
    public void initialize() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            if (PlatformSystems.isWindows()) {
                win32Registry = initializeWindows();
                log.info("Windows Event Log FFM bridge initialized: {}", win32Registry != null);
            } else if (PlatformSystems.isLinux()) {
                linuxRegistry = initializeLinux();
                log.info("Linux journald FFM bridge initialized: {}", linuxRegistry != null);
            }
            initialized = true;
        }
    }

    /** 获取WinRegistry */
    public NativeFunctionRegistry getWin32Registry() {
        return win32Registry;
    }

    /** 获取LinuxRegistry */
    public NativeFunctionRegistry getLinuxRegistry() {
        return linuxRegistry;
    }

    /** 创建Provider */
    public SystemLogProvider createProvider() {
        if (PlatformSystems.isWindows()) {
            return new WindowsEventLogProvider(this);
        }
        if (PlatformSystems.isLinux()) {
            return new LinuxJournaldProvider(this);
        }
        if (PlatformSystems.isMacOs()) {
            return new MacOSUnifiedLogProvider();
        }
        throw new UnsupportedOperationException("Unsupported platform for system log");
    }

    /** 初始化Windows */
    private NativeFunctionRegistry initializeWindows() {
        try {
            return NativeFunctionRegistry.ofLibrary("Advapi32");
        } catch (Exception e) {
            log.warn("Failed to initialize Windows Event Log FFM bindings: {}", e.getMessage());
            return null;
        }
    }

    /** 初始化Linux */
    private NativeFunctionRegistry initializeLinux() {
        try {
            return NativeFunctionRegistry.ofLibrary("systemd");
        } catch (Exception e) {
            log.debug("libsystemd not available, falling back to /var/log file reading: {}", e.getMessage());
            return null;
        }
    }
}
