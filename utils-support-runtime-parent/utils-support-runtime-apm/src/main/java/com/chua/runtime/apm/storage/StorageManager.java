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
     * 当前生效的存储配置（{@link #reconfigure(StorageConfig)} 时用于比对差异）
     */
    private static final AtomicReference<StorageConfig> CURRENT_CONFIG = new AtomicReference<>();
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
        // 先关掉上一轮实例：热更新外直接 init 是公开入口，反复 init 不关旧实例
        // 会累积自维护线程与 SQLite 连接（已实测泄漏）
        ApmStorage stale = GLOBAL.get();
        if (stale != null && !(stale instanceof NoopStorage)) {
            closeQuietly(stale, "init 前关闭旧存储");
        }
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
        CURRENT_CONFIG.set(copy(config));
        log.info("StorageManager 已初始化: " + resolved.name());
    }

    /**
     * 关闭存储实例，异常只记日志。
     *
     * <p>关闭失败不能让流程中断：调用方通常正在进行热更新或初始化，
     * 此时更重要的保证是「不因为清理失败而丢掉新实例」。</p>
     *
     * @param storage 存储实例
     * @param scene   场景描述（用于日志）
     */
    private static void closeQuietly(ApmStorage storage, String scene) {
        try {
            storage.stop();
        } catch (Throwable e) {
            log.log(Level.FINE, scene + " 异常: " + e.getMessage());
        }
    }

    /**
     * 运行时切换存储配置（热更新）。
     *
     * <p><b>为什么需要</b>：落盘路径、保留期、容量这类参数改完理应重启生效，
     * 但生产环境重启一个业务进程代价很高。本方法允许在进程存活期间切换存储实例。</p>
     *
     * <p><b>安全边界</b>：</p>
     * <ul>
     *   <li>配置无差异时直接返回，不做无谓的断开重连</li>
     *   <li>新实例先完整启动（建连 + 建表），成功后才与旧实例交换，
     *       失败则保留旧实例继续工作 —— 热更新绝不把可用的存储换成坏的</li>
     *   <li>新旧交替期间不丢数据：旧实例在排空写队列后才关闭</li>
     *   <li>整个过程串行化，避免并发热更新互相踩踏</li>
     * </ul>
     *
     * @param config 新的存储配置
     * @return 切换成功（或无需切换）返回 true；新配置启动失败返回 false（旧实例保留）
     */
    public static synchronized boolean reconfigure(StorageConfig config) {
        if (config == null) {
            return false;
        }
        StorageConfig previous = CURRENT_CONFIG.get();
        if (previous != null && sameConfig(previous, config)) {
            log.info("存储配置无变化，跳过热更新");
            return true;
        }
        ApmStorage old = GLOBAL.get();
        ApmStorage fresh = resolve(config);
        boolean started = false;
        try {
            fresh.start(config);
            started = true;
        } catch (Throwable e) {
            log.log(Level.WARNING, "热更新：新存储启动失败，保留原存储 " + old.name(), e);
        }
        if (!started) {
            try {
                if (fresh != old) {
                    fresh.stop();
                }
            } catch (Throwable ignored) {
                // 新实例关闭失败不影响旧实例
            }
            return false;
        }
        GLOBAL.set(fresh);
        CURRENT_CONFIG.set(copy(config));
        if (old != null && old != fresh) {
            try {
                old.stop();
            } catch (Throwable e) {
                log.log(Level.FINE, "热更新：旧存储关闭异常: " + e.getMessage());
            }
        }
        log.info(String.format("存储已热更新: %s -> %s", old == null ? "null" : old.name(),
                fresh.name()));
        return true;
    }

    /**
     * 当前生效的存储配置（只读副本）。
     *
     * @return 配置；尚未初始化返回 null
     */
    public static StorageConfig currentConfig() {
        return CURRENT_CONFIG.get();
    }

    /**
     * 当前存储的运行时状态。
     *
     * @return 状态映射
     */
    public static Map<String, Object> describe() {
        try {
            return GLOBAL.get().describe();
        } catch (Throwable e) {
            log.log(Level.FINE, "读取存储状态失败: " + e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * 复制配置（避免调用方之后修改原始 Map 影响比对）。
     *
     * @param config 配置
     * @return 副本
     */
    private static StorageConfig copy(StorageConfig config) {
        StorageConfig copy = new StorageConfig();
        copy.setProperties(new java.util.HashMap<>(config.getProperties()));
        return copy;
    }

    /**
     * 两份配置是否等价。
     *
     * @param a 配置 A
     * @param b 配置 B
     * @return 等价返回 true
     */
    private static boolean sameConfig(StorageConfig a, StorageConfig b) {
        Map<String, String> x = a.getProperties();
        Map<String, String> y = b.getProperties();
        return x == null ? y == null || y.isEmpty() : x.equals(y);
    }

    /**
     * 关闭并清理。
     */
    public static synchronized void shutdown() {
        ApmStorage s = GLOBAL.get();
        GLOBAL.set(new NoopStorage());
        CURRENT_CONFIG.set(null);
        REGISTERED.clear();
        if (s != null && !(s instanceof NoopStorage)) {
            closeQuietly(s, "Storage 关闭");
        }
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

        // 2. 需要 JDBC 的存储（sqlite）在 agent bootstrap CL 下无法链接 java.sql，
        //    改用 parent=system 的隔离 ClassLoader 实例化；无驱动时返回 null 沿用默认 CL。
        //    注意：这一步必须早于 register() 查表，否则热更新会复用上一次的实例
        //   （同一实例再次 start() 只会把旧连接换成新连接，旧库的队列不会被排空）。
        if (ApmStorageClassLoader.isolatedClassName(type) != null) {
            ClassLoader isolated = ApmStorageClassLoader.resolve(config.getProperties());
            if (isolated != null) {
                ApmStorage s = loadFrom(config, isolated);
                if (s != null) {
                    return s;
                }
            }
        }

        ApmStorage explicit = REGISTERED.get(type);
        if (explicit != null) {
            return explicit;
        }

        // 3. SPI 扫描(优先 context CL,回退 system CL,确保 agent bootstrap CL 也能加载)
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        try {
            for (ApmStorage s : ServiceLoader.load(ApmStorage.class, cl)) {
                if (!type.equals(s.name())) {
                    continue;
                }
                // SPI 在部分实现下会缓存实例，这里不写回 REGISTERED：
                // 注册表只保存 register() 显式注入的对象，避免热更新串到旧实例。
                return s;
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
            Object instance = storageType.getDeclaredConstructor().newInstance();
            // 隔离侧实现的 ApmStorage 就是父侧接口：STORAGE_API 名单保证
            // ApmStorage 由 bootstrap 定义（父优先），隔离加载器加载 SqliteStorage 时
            // 其 implements 的接口仍从父链解析到同一份 —— 已实测 instanceof 为 true，
            // 可以直接 cast，不需要反射桥接（旧架构的 ClassCastException 已不存在）。
            ApmStorage storage = (ApmStorage) instance;
            log.info("存储实现 " + type + " 由 ClassLoader 加载: " + cl);
            return storage;
        } catch (Throwable e) {
            log.log(Level.WARNING, "隔离 ClassLoader 加载存储失败: " + e.getMessage(), e);
        }
        return null;
    }
}