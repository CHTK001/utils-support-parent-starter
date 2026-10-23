package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.TransmissionRecord;
import lombok.extern.java.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * 存储管理器 — SPI 加载 / 全局访问 / 写入分发。
 *
 * <p>启动时通过 {@link #init(StorageConfig)} 加载 SPI；运行时各 Handler 通过
 * {@link #appendTransmission(TransmissionRecord)} 等便捷方法写入。</p>
 *
 * <p>选择策略：</p>
 * <ol>
 *   <li>SPI 第一个有效实现作为默认</li>
 *   <li>若 {@code apm.storage.type} 配置与默认实现名不同，尝试加载同名实现</li>
 *   <li>找不到匹配实现时 fallback 到 {@link NoopStorage}</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Log
public final class StorageManager {

    /**
     * 全局
     */
    private static final AtomicReference<ApmStorage> GLOBAL = new AtomicReference<>(new NoopStorage());
    /**
     * 注册
     */
    private static final Map<String, ApmStorage> REGISTERED = new ConcurrentHashMap<>();

    /**
     * 创建 storage管理器 实例
    */
    private StorageManager() {
    }

    /**
     * 初始化全局存储。
     *
     * <p>任何存储实现启动失败（含 {@link NoClassDefFoundError} 这类 {@link Error}，
     * 例如 agent classloader 下缺少 JDBC 类）都降级到 {@link NoopStorage}，
     * 绝不让观测组件把宿主进程带崩。</p>
     *
     * @param config 配置
     */
    public static synchronized void init(StorageConfig config) {
        ApmStorage resolved = resolve(config);
        try {
            resolved.start(config);
        } catch (Throwable e) {
            log.log(Level.WARNING, "Storage 启动失败，降级为 noop: " + resolved.name(), e);
            resolved = new NoopStorage();
            try {
                resolved.start(config);
            } catch (Throwable ignored) {
                // NoopStorage 自身不会失败；兜底吞掉，保持全局引用可用
            }
        }
        GLOBAL.set(resolved);
        log.info("StorageManager 已初始化: " + resolved.name());
    }

    /**
     * 关闭并清理。
     */
    public static synchronized void shutdown() {
        ApmStorage s = GLOBAL.get();
        try {
            s.stop();
        } catch (Exception e) {
            log.log(Level.WARNING, "Storage 关闭失败: " + s.name(), e);
        }
        GLOBAL.set(new NoopStorage());
        REGISTERED.clear();
    }

    /**
     * 获取当前全局存储。
     * @return 获取的结果
     */
    public static ApmStorage get() {
        return GLOBAL.get();
    }

    /**
     * 注册自定义存储（可绕过 SPI 直接注入）。
     * @param storage storage
     */
    public static void register(ApmStorage storage) {
        if (storage != null) {
            REGISTERED.put(storage.name(), storage);
        }
    }

    // ====== 便捷写入接口 ======

    /**
     * 从 {@link TransmissionRecord} 转扁平并写入。
     * @param record record
     */
    public static void appendTransmission(TransmissionRecord record) {
        ApmStorage s = GLOBAL.get();
        if (record == null) {
            return;
        }
        try {
            s.appendTransmission(TransmissionEvent.fromRecord(record));
        } catch (Exception e) {
            log.log(Level.FINE, "appendTransmission 异常: " + e.getMessage());
        }
    }

    /**
     * 解析要使用的存储实现。
     *
     * <p>查找顺序:</p>
     * <ol>
     *   <li>已显式 register() 的 storage</li>
     *   <li>SPI 加载 + 按 type 精确匹配</li>
     *   <li>找不到 type 时,使用 NoopStorage(不盲选第一个 SPI 实现,避免配置与实际不一致)</li>
     * </ol>
     * @param config 配置
     * @return resolve的结果
     */
    private static ApmStorage resolve(StorageConfig config) {
 // 1. 先按 类型 字段精确匹配
        String type = config.get("apm.storage.type", ApmStorage.DEFAULT_NAME);
        ApmStorage explicit = REGISTERED.get(type);
        if (explicit != null) {
            return explicit;
        }

        // 2. 需要 JDBC 的存储（sqlite）在 agent bootstrap CL 下无法链接 java.sql，
        //    改用 parent=system 的隔离 ClassLoader 实例化；无驱动时返回 null 沿用默认 CL
        if (ApmStorageClassLoader.isolatedClassName(type) != null) {
            ClassLoader isolated = ApmStorageClassLoader.resolve(config.getProperties());
            if (isolated != null) {
                ApmStorage s = loadFrom(config, isolated);
                if (s != null) {
                    return s;
                }
            }
        }

        // 3. SPI 扫描(优先 context CL,回退 system CL,确保 agent bootstrap CL 也能加载)
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        try {
            for (ApmStorage s : ServiceLoader.load(ApmStorage.class, cl)) {
                REGISTERED.put(s.name(), s);
                if (type.equals(s.name())) {
                    return s;
                }
            }
        } catch (Throwable e) {
            // 某些实现在 agent classloader 下类缺失（如 JDBC 依赖不可见）会抛 Error，
            // 这里整体兜住，让上层按「未找到 type」降级到 NoopStorage
            log.log(Level.WARNING, "存储 SPI 扫描失败: " + e.getMessage(), e);
        }

        // 4. 找不到指定 type — 不盲选第一个 SPI(避免用户配置 "sqlite" 但实际用 "inmemory"),
 // 返回 noopstorage 让 处理器 安全退化
        log.warning("未找到 " + type + " 存储实现,使用 NoopStorage");
        return new NoopStorage();
    }

    /**
     * 从隔离 ClassLoader 直接实例化 JDBC 存储实现。
     *
     * <p>不走 {@link ServiceLoader}：隔离加载器里的 {@code NoopStorage}/{@code InMemoryStorage}
     * 会被桥接回调用方（与 {@link ApmStorage} 接口同源），而 {@code SqliteStorage} 由隔离加载器
     * 自己定义，SPI 枚举会把两者混在一起。这里只按类名精确加载需要的实现。</p>
     *
     * @param config 配置
     * @param cl     隔离 ClassLoader
     * @return 存储实现；type 无对应实现或加载失败返回 null
     */
    private static ApmStorage loadFrom(StorageConfig config, ClassLoader cl) {
        String type = config.get("apm.storage.type", ApmStorage.DEFAULT_NAME);
        String className = ApmStorageClassLoader.isolatedClassName(type);
        if (className == null) {
            log.warning("存储类型 " + type + " 需要隔离加载但无对应类，回退默认 ClassLoader");
            return null;
        }
        try {
            Class<?> storageType = Class.forName(className, true, cl);
            ApmStorage storage = (ApmStorage) storageType.getDeclaredConstructor().newInstance();
            log.info("存储实现 " + type + " 由隔离 ClassLoader 加载: " + cl);
            return storage;
        } catch (Throwable e) {
            log.log(Level.WARNING, "隔离 ClassLoader 加载存储失败: " + e.getMessage(), e);
        }
        return null;
    }
}