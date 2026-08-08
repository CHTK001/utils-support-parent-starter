package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.TransmissionRecord;
import lombok.extern.java.Log;

import java.util.HashMap;
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

    private static final AtomicReference<ApmStorage> GLOBAL = new AtomicReference<>(new NoopStorage());
    private static final Map<String, ApmStorage> REGISTERED = new HashMap<>();

    private StorageManager() {
    }

    /**
     * 初始化全局存储。
     */
    public static synchronized void init(StorageConfig config) {
        ApmStorage resolved = resolve(config);
        try {
            resolved.start(config);
        } catch (Exception e) {
            log.log(Level.WARNING, "Storage 启动失败: " + resolved.name(), e);
            resolved = new NoopStorage();
            resolved.start(config);
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
     */
    public static ApmStorage get() {
        return GLOBAL.get();
    }

    /**
     * 注册自定义存储（可绕过 SPI 直接注入）。
     */
    public static void register(ApmStorage storage) {
        if (storage != null) {
            REGISTERED.put(storage.name(), storage);
        }
    }

    // ====== 便捷写入接口 ======

    /**
     * 从 {@link TransmissionRecord} 转扁平并写入。
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
     */
    private static ApmStorage resolve(StorageConfig config) {
        // 1. 先按 type 字段精确匹配
        String type = config.get("apm.storage.type", ApmStorage.DEFAULT_NAME);
        ApmStorage explicit = REGISTERED.get(type);
        if (explicit != null) {
            return explicit;
        }

        // 2. SPI 扫描
        for (ApmStorage s : ServiceLoader.load(ApmStorage.class)) {
            REGISTERED.put(s.name(), s);
            if (type.equals(s.name())) {
                return s;
            }
        }

        // 3. SPI 第一个实现作为兜底
        if (!REGISTERED.isEmpty()) {
            return REGISTERED.values().iterator().next();
        }

        // 4. NoopStorage
        log.warning("未找到 " + type + " 存储实现，使用 NoopStorage");
        return new NoopStorage();
    }
}