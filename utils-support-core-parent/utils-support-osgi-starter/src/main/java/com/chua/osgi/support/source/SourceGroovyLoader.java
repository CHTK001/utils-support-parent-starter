package com.chua.osgi.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.task.script.ScriptProviders;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把源码目录下的 {@code .groovy} 脚本编译为 SPI 扩展点声明。
 *
 * <p>复用既有的 {@link com.chua.common.support.task.script.ScriptProvider} SPI
 * （{@code groovy} 引擎由 {@code utils-support-groovy-starter} 提供），
 * 不直接依赖 Groovy API，因此 groovy 不在 classpath 时本组件自动失效而不报错。</p>
 *
 * <p><b>脚本如何声明自己是扩展点</b>：脚本返回一个 Map，
 * 形如 {@code [spi: 'com.example.MySpi', name: 'myExt', value: 扩展实例]}，
 * 或返回 List 承载多个声明。约定字段：</p>
 * <ul>
 *   <li>{@code spi}（必需）— SPI 类型的全限定类名</li>
 *   <li>{@code name}（必需）— 扩展名</li>
 *   <li>{@code value}（必需）— 扩展点实例</li>
 * </ul>
 *
 * <p>脚本返回值类型不受限（Groovy 动态类型），因此无法像 Java 那样由字段静态类型
 * 推导 SPI 类型——这也是 {@link SourceExtensionFactory} 存在的理由：
 * 两种语言共用同一个契约，调度方无需分支。</p>
 *
 * <p><b>热重载语义</b>：脚本路径与 {@link ScriptProvider#loadScript} 的缓存键一致，
 * 源码变更后先 {@code unloadScript} 再 {@code loadScript}，
 * 否则复用旧脚本类会导致「改了代码不生效」。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SourceGroovyLoader {

    /**
     * 脚本返回的 SPI 类型字段名。
     */
    private static final String FIELD_SPI = "spi";

    /**
     * 脚本返回的扩展名字段名。
     */
    private static final String FIELD_NAME = "name";

    /**
     * 脚本返回的实例字段名。
     */
    private static final String FIELD_VALUE = "value";

    /**
     * 源码目录。
     */
    private final Path sourceDirectory;

    /**
     * 引擎名，对应 {@code ScriptProvider#engineName}。
     */
    private final String engine;

    /**
     * 已加载的脚本路径，重载时据此卸载。
     */
    private final Set<Path> loadedScripts = Wraps.set();

    /**
     * 创建 Groovy 源码加载器。
     *
     * @param sourceDirectory 源码目录
     * @param engine          脚本引擎名，默认 {@code groovy}
     */
    public SourceGroovyLoader(Path sourceDirectory, String engine) {
        this.sourceDirectory = sourceDirectory;
        this.engine = engine == null || engine.isBlank() ? "groovy" : engine;
    }

    /**
     * 执行一轮编译并产出扩展点声明。
     *
     * <p>引擎不可用（groovy 不在 classpath）时返回空列表并告警一次，
     * 不影响 Java 源码侧。</p>
     *
     * @return 本轮扩展点声明
     */
    public List<SourceExtensionFactory> load() {
        if (ScriptProviders.resolve(engine) == null) {
            log.warn("[osgi-source] 脚本引擎 {} 不可用（缺少对应 ScriptProvider 实现），跳过 .groovy 源码",
                    engine);
            return List.of();
        }
        List<Path> scripts = listScripts();
        if (scripts.isEmpty()) {
            return List.of();
        }
        // 先卸载全部旧脚本：ScriptProvider 内部按路径缓存脚本类，
        // 不卸载则源码改动后 loadScript 仍命中旧类，表现为「改了没生效」
        unloadAll();
        List<SourceExtensionFactory> batch = Wraps.list();
        for (Path script : scripts) {
            batch.addAll(loadOne(script));
        }
        if (!batch.isEmpty()) {
            log.info("[osgi-source] Groovy 编译完成，产出 {} 个扩展点（{} 个脚本）",
                    batch.size(), scripts.size());
        }
        return batch;
    }

    /**
     * 卸载本加载器已加载的全部脚本。
     */
    public void unloadAll() {
        for (Path script : loadedScripts) {
            try {
                ScriptProviders.resolve(engine).unloadScript(script);
            } catch (Exception e) {
                log.debug("[osgi-source] 卸载脚本 {} 失败: {}", script.getFileName(), e.getMessage());
            }
        }
        loadedScripts.clear();
    }

    /**
     * 加载单个脚本并解析其声明。
     *
     * @param script 脚本路径
     * @return 扩展点声明列表
     */
    private List<SourceExtensionFactory> loadOne(Path script) {
        var provider = ScriptProviders.resolve(engine);
        try {
            if (!provider.loadScript(script)) {
                log.warn("[osgi-source] 脚本加载失败: {}", script.getFileName());
                return List.of();
            }
            loadedScripts.add(script);
            Object result = provider.executeScript(script, null);
            return parseDeclarations(result, script);
        } catch (Exception e) {
            log.error("[osgi-source] 脚本执行失败: {}", script.getFileName(), e);
            return List.of();
        }
    }

    /**
     * 解析脚本返回值中的扩展点声明。
     *
     * <p>支持单个 Map 与 List 两种形态；Map 缺字段、类型不存在、
     * value 为 null 等情况一律跳过并告警，不影响同批其他声明。</p>
     *
     * @param result 脚本返回值
     * @param script 脚本路径，用于日志
     * @return 扩展点声明列表
     */
    @SuppressWarnings("unchecked")
    private List<SourceExtensionFactory> parseDeclarations(Object result, Path script) {
        List<SourceExtensionFactory> batch = Wraps.list();
        if (result instanceof List<?> list) {
            for (Object item : list) {
                batch.addAll(parseDeclarations(item, script));
            }
            return batch;
        }
        if (!(result instanceof Map<?, ?> map)) {
            log.warn("[osgi-source] 脚本 {} 返回类型不是 Map/List: {}",
                    script.getFileName(), result == null ? "null" : result.getClass().getName());
            return batch;
        }
        Object spiName = map.get(FIELD_SPI);
        Object name = map.get(FIELD_NAME);
        Object value = map.get(FIELD_VALUE);
        if (spiName == null || name == null || value == null) {
            log.warn("[osgi-source] 脚本 {} 声明字段缺失（需要 spi/name/value 三个字段），已跳过",
                    script.getFileName());
            return batch;
        }
        Class<?> spiType = resolveSpiType(String.valueOf(spiName));
        if (spiType == null) {
            log.warn("[osgi-source] 脚本 {} 声明的 SPI 类型不存在: {}", script.getFileName(), spiName);
            return batch;
        }
        final Object instance = value;
        batch.add(new SourceExtensionFactory() {

            @Override
            public Object create() {
                return instance;
            }

            @Override
            public Class<?> spiType() {
                return spiType;
            }

            @Override
            public String extensionName() {
                return String.valueOf(name);
            }

            @Override
            public String toString() {
                return script.getFileName() + " -> " + spiType.getSimpleName() + "#" + name;
            }
        });
        return batch;
    }

    /**
     * 解析 SPI 类型的 Class 对象。
     *
     * @param className 全限定类名
     * @return 类型对象，解析失败返回 {@code null}
     */
    private Class<?> resolveSpiType(String className) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = SourceGroovyLoader.class.getClassLoader();
        }
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            log.warn("[osgi-source] SPI 类型加载失败: {}", className);
            return null;
        }
    }

    /**
     * 列出源码目录下的 Groovy 脚本。
     *
     * @return 脚本路径列表
     */
    private List<Path> listScripts() {
        if (!Files.isDirectory(sourceDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(sourceDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".groovy"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("[osgi-source] 扫描 groovy 脚本失败: {}", e.getMessage());
            return List.of();
        }
    }
}
