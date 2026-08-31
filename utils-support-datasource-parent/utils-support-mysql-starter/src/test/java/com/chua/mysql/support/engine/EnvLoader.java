package com.chua.mysql.support.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 加载 .env 配置，优先顺序：-D 系统属性 > 环境变量 > src/test/resources/.env > 硬编码默认值。
 * <p>
 * 仅在测试阶段使用，不引入额外依赖。
 *
 * @author CH
 * @since 4.0.0.42
 */
class EnvLoader {

    private static final Map<String, String> CACHE = new HashMap<>();
    private static volatile boolean loaded;

    static {
        load();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (EnvLoader.class) {
            if (loaded) {
                return;
            }
            // 1. 读取 classpath .env
            try (InputStream is = EnvLoader.class.getClassLoader().getResourceAsStream(".env")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    props.forEach((k, v) -> CACHE.put(k.toString(), v.toString()));
                }
            } catch (IOException ignored) {
                // fallback
            }
            // 2. 读取项目根目录 .env（IDEA 运行时工作目录通常是 module 目录，尝试向上两级）
            try {
                Path root = Paths.get("").toAbsolutePath().normalize();
                for (int i = 0; i < 4; i++) {
                    Path envFile = root.resolve(".env");
                    if (Files.exists(envFile)) {
                        try (InputStream is = Files.newInputStream(envFile)) {
                            Properties props = new Properties();
                            props.load(is);
                            props.forEach((k, v) -> CACHE.putIfAbsent(k.toString(), v.toString()));
                        }
                        break;
                    }
                    root = root.getParent();
                }
            } catch (IOException ignored) {
                // fallback
            }
            loaded = true;
        }
    }

    private EnvLoader() {}

    /**
     * 获取配置值，优先级：-D 系统属性 > 环境变量 > .env 文件 > defaultValue
     */
    static String get(String key, String defaultValue) {
        if (System.getProperty(key) != null) {
            return System.getProperty(key);
        }
        if (System.getenv(key) != null) {
            return System.getenv(key);
        }
        return CACHE.getOrDefault(key, defaultValue);
    }

    static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 供测试基类使用的便捷方法 */
    static final class Defaults {
        public static final String HOST = "172.16.0.40";
        public static final int PORT = 3308;
        public static final String ADMIN_USER = "root";
        public static final String ADMIN_PASS = "root";
    }
}
