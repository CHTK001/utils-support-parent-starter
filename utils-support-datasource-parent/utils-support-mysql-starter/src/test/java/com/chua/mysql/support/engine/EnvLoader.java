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
 * 加载指定方言的 .env.{dialect} 配置文件，支持覆盖。
 * <p>
 * 加载优先级（单次加载，结果缓存）：
 * <ol>
 *   <li>-D 系统属性（如 -DADMIN_HOST=10.0.0.1）</li>
 *   <li>环境变量（如 ADMIN_HOST=xxx）</li>
 *   <li>classpath: .env.{dialect}（如 .env.mysql）</li>
 *   <li>项目根目录 .env.{dialect}</li>
 *   <li>硬编码默认值（{@link Defaults}）</li>
 * </ol>
 * 键名规范化后统一为大写，ADMIN_HOST 与 CONN_HOST 等不同写法均可支持。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class EnvLoader {

    /** 方言名称，用于文件名，如 mysql、postgresql */
    private static final String DIALECT = "mysql";
    private static final String RES_NAME = ".env." + DIALECT;

    private static final Map<String, String> CACHE = new HashMap<>();
    private static volatile boolean loaded;

    static {
        load();
    }

    private EnvLoader() {}

    private static void load() {
        if (loaded) return;
        synchronized (EnvLoader.class) {
            if (loaded) return;
            // 1. classpath .env.mysql
            try (InputStream is = EnvLoader.class.getClassLoader().getResourceAsStream(RES_NAME)) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    props.forEach((k, v) -> CACHE.put(norm(k.toString()), v.toString()));
                }
            } catch (IOException ignored) {}
            // 2. 项目根目录 .env.mysql（向上最多6层）
            try {
                Path root = Paths.get("").toAbsolutePath().normalize();
                for (int i = 0; i < 6; i++) {
                    Path envFile = root.resolve(".env." + DIALECT);
                    if (Files.exists(envFile)) {
                        try (InputStream is = Files.newInputStream(envFile)) {
                            Properties props = new Properties();
                            props.load(is);
                            props.forEach((k, v) -> CACHE.putIfAbsent(norm(k.toString()), v.toString()));
                        }
                        break;
                    }
                    root = root.getParent();
                }
            } catch (IOException ignored) {}
            loaded = true;
        }
    }

    /**
     * 获取配置值，优先级：-D 属性 > 环境变量 > .env > defaultValue
     */
    static String get(String key, String defaultValue) {
        String n = norm(key);
        String val = System.getProperty(n);
        if (val != null) return val;
        val = System.getenv(n);
        if (val != null) return val;
        return CACHE.getOrDefault(n, defaultValue);
    }

    static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String norm(String key) {
        return key == null ? "" : key.trim().toUpperCase();
    }

    /** 硬编码默认值，当 .env 文件不存在时使用 */
    static final class Defaults {
        public static final String HOST       = "172.16.0.40";
        public static final int    PORT       = 3308;
        public static final String ADMIN_USER = "root";
        public static final String ADMIN_PASS = "root";
        public static final String DEFAULT_ENGINE   = "InnoDB";
        public static final String DEFAULT_CHARSET  = "utf8mb4";
        public static final String DEFAULT_COLLATE  = "utf8mb4_unicode_ci";
    }
}
