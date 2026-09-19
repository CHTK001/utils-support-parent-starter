package com.chua.common.support.env;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CUDA 运行时库配置加载器（读取 classpath 下 {@code env/cuda.env}）。
 *
 * <p>单例 + 懒加载 + 缓存：JVM 生命周期内仅首次访问时读取一次 .env 文件，
 * 之后全部命中内存缓存，对性能无影响。可通过 {@link #reload()} 强制重读
 * （例如外部修改 .env 后希望生效的场景）。</p>
 *
 * <p>配置项（均不硬编码，见 {@code env/cuda.env}）：
 * <ul>
 *   <li>{@code CUDA_MAJOR} — 目标 CUDA 主版本</li>
 *   <li>{@code ORT_VERSION} — 配套 onnxruntime-gpu 版本</li>
 *   <li>{@code TARGET_DIR} — CUDA 运行库目标目录</li>
 *   <li>{@code SCRIPT_WINDOWS/LINUX/MACOS} — 三平台安装脚本名</li>
 *   <li>{@code DOWNLOAD_TIMEOUT_SEC} / {@code AUTO_PATH}</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class CudaEnvLoader {

    /**
     * classpath 资源路径
    */
    public static final String RESOURCE_PATH = "env/cuda.env";

    /**
     * 单例缓存：null=未加载
    */
    private static volatile Map<String, String> CACHE;

    /**
     * 构造方法，创建 Cuda环境Loader 实例。
     */
    private CudaEnvLoader() {
    }

    /**
     * 获取配置值。
     *
     * @param key 配置键，如 {@code CUDA_MAJOR}
     * @return 配置值；缺失返回 null
     */
    public static String get(String key) {
        return load().get(key);
    }

    /**
     * 获取配置值（带默认值）。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值；缺失或为空返回默认值
     */
    public static String get(String key, String defaultValue) {
        String v = load().get(key);
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    /**
     * 获取 int 配置值（带默认值）。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return int 配置值
     */
    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取 bool 配置值（带默认值）。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return bool 配置值
     */
    public static boolean getBool(String key, boolean defaultValue) {
        String v = get(key, String.valueOf(defaultValue)).trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * 获取全部配置（只读视图）。
     *
     * @return 配置键值对
     */
    public static Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(load());
    }

    /**
     * 强制重载（清空缓存，下次访问重新读取）。
     */
    public static synchronized void reload() {
        CACHE = null;
        load();
    }

    /**
     * 懒加载单例：double-check，仅首次读取 .env。
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, String> load() {
        Map<String, String> cached = CACHE;
        if (cached != null) {
            return cached;
        }
        synchronized (CudaEnvLoader.class) {
            cached = CACHE;
            if (cached != null) {
                return cached;
            }
            Map<String, String> map = new ConcurrentHashMap<>();
            Properties props = new Properties();
            try (InputStream in = CudaEnvLoader.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
                if (in == null) {
                    log.warn("[cuda-env] 未找到 classpath 资源 {}，使用空配置", RESOURCE_PATH);
                } else {
                    try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        props.load(reader);
                    }
                }
            } catch (IOException e) {
                log.warn("[cuda-env] 读取 {} 失败: {}", RESOURCE_PATH, e.getMessage());
            }
            for (String name : props.stringPropertyNames()) {
                map.put(name, props.getProperty(name).trim());
            }
            log.debug("[cuda-env] 已加载 {} 个配置项 (首次加载，此后走缓存)", map.size());
            CACHE = map;
            return map;
        }
    }
}
