package com.chua.common.support.objects;

import com.chua.common.support.config.parser.ConfigParser;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
* 从 类路径 上的 application 配置文件加载 {@link ObjectContextConfig}。
* <p>行为对齐 Spring Boot：优先读取 {@code application.yml} / {@code application.yaml}，
* 其次 {@code application.properties}。</p>
* <p>支持的配置键：
* <pre>
* object-context:
*   spi-enabled: true
*   annotation-scan-enabled: true
*   scan-packages:
*     - com.example.app
*     - com.example.other
* </pre>
* 扁平写法：{@code object-context.scan-packages=com.example.app,com.example.other}</p>
*
* @author CH
* @since 2026/07/20
 */
@Slf4j
public final class ObjectContextConfigLoader {

    /**
    * 配置文件搜索路径列表
     */
    private static final String[] CONFIG_LOCATIONS = {
            "application.yml",
            "application.yaml",
            "application.properties",
            "application.json",
            "application.jsonl"
    };

    /**
    * 配置属性前缀
     */
    private static final String PREFIX = "object-context";

    /**
    * SPI 开关配置键
     */
    private static final String KEY_SPI = PREFIX + ".spi-enabled";

    /**
    * 注解扫描开关配置键
     */
    private static final String KEY_ANNOTATION_SCAN = PREFIX + ".annotation-scan-enabled";

    /**
    * 扫描包列表配置键
     */
    private static final String KEY_SCAN_PACKAGES = PREFIX + ".scan-packages";

    /**
    * 私有构造函数，防止实例化
     */
    private ObjectContextConfigLoader() {
    }

    /**
    * 从 类路径 加载配置，文件不存在或解析失败时返回 构建器 默认值。
    *
    * @return 解析后的配置
     */
    public static ObjectContextConfig load() {
        PropertySource source = loadApplicationPropertySource();
        if (source == null || source == PropertySource.EMPTY) {
            return ObjectContextConfig.builder().build();
        }
        return bind(source);
    }

    /**
    * 将属性源绑定为 {@link ObjectContextConfig}。
    *
    * @param source 属性源
    * @return 配置
     */
    public static ObjectContextConfig bind(PropertySource source) {
        if (source == null || source == PropertySource.EMPTY) {
            return ObjectContextConfig.builder().build();
        }

        boolean spiEnabled = resolveBoolean(source, KEY_SPI, true);
        List<String> scanPackages = resolveScanPackages(source);
        boolean annotationScanEnabled = resolveBoolean(source, KEY_ANNOTATION_SCAN, !scanPackages.isEmpty());

        if (annotationScanEnabled && scanPackages.isEmpty()) {
            String mainPackage = detectMainPackage();
            if (mainPackage != null && !mainPackage.isEmpty()) {
                scanPackages = List.of(mainPackage);
            }
        }

        return ObjectContextConfig.builder()
                .spiEnabled(spiEnabled)
                .annotationScanEnabled(annotationScanEnabled)
                .scanPackages(scanPackages)
                .build();
    }

    /**
    * 加载 application 属性源。
    *
    * @return 属性源，如果未找到则返回 空
     */
    private static PropertySource loadApplicationPropertySource() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ObjectContextConfigLoader.class.getClassLoader();
        }
        for (String location : CONFIG_LOCATIONS) {
            try (InputStream is = cl.getResourceAsStream(location)) {
                if (is == null) {
                    continue;
                }
                PropertySource source = parse(location, is);
                if (source != null && source != PropertySource.EMPTY) {
                    log.debug("ObjectContext 加载配置文件: {}", location);
                    return source;
                }
            } catch (Exception e) {
                log.debug("加载配置文件失败: {}", location, e);
            }
        }
        return null;
    }

    /**
    * 解析输入流为属性源。
    *
    * @param location 配置文件位置
    * @param is       输入流
    * @return 解析后的属性源
     */
    private static PropertySource parse(String location, InputStream is) {
        String extension = FileUtils.getExtension(location);
        ConfigParser parser = ServiceProvider.of(ConfigParser.class).getNewExtension(extension);
        if (parser == null) {
            return PropertySource.EMPTY;
        }
        return parser.parse(location, is);
    }

    /**
    * 解析布尔类型的配置属性。
    *
    * @param source      属性源
    * @param key         配置键
    * @param defaultValue 默认值
    * @return 解析后的布尔值
     */
    private static boolean resolveBoolean(PropertySource source, String key, boolean defaultValue) {
        Object value = source.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    /**
    * 解析扫描包列表配置。
    *
    * @param source 属性源
    * @return 扫描包列表
     */
    private static List<String> resolveScanPackages(PropertySource source) {
        Object value = source.getProperty(KEY_SCAN_PACKAGES);
        if (value == null) {
            value = source.getProperty(PREFIX + ".scanPackages");
        }
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    String pkg = String.valueOf(item).trim();
                    if (!pkg.isEmpty()) {
                        result.add(pkg);
                    }
                }
            }
            return result;
        }
        if (value instanceof String[] arr) {
            List<String> result = new ArrayList<>(arr.length);
            for (String item : arr) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = text.split("[,;\\s]+");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    /**
    * 类似 Spring Boot：从启动 main 类推断默认扫描包。
    *
    * @return 主类的包名，如果无法推断则返回 空
     */
    private static String detectMainPackage() {
        try {
            String command = System.getProperty("sun.java.command");
            if (command != null && !command.isBlank()) {
                String mainClassName = command.split("\\s+")[0];
                if (mainClassName.contains(".") && !mainClassName.endsWith(".jar")) {
                    Class<?> mainClass = ReflectUtils.forName(mainClassName,
                            Thread.currentThread().getContextClassLoader());
                    Package pkg = mainClass.getPackage();
                    if (pkg != null && pkg.getName() != null && !pkg.getName().isEmpty()) {
                        return pkg.getName();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("推断 main 类包名失败", e);
        }
        return null;
    }
}
