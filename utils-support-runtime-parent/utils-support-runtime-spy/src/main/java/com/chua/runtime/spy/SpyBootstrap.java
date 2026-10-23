package com.chua.runtime.spy;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.plugin.loader.PluginManager;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Spy 启动器 — 通过 Instrumentation 注册字节码转换器。
 *
 * <p>典型调用链：</p>
 * <pre>
 * RuntimeAgent.agentmain(Inst)
 *   -> SpyBootstrap.init(agentArgs, Inst)
 *       -> 创建 SpyTransformer
 *       -> 注册 ClassFileTransformer
 *       -> 扫描插件并加载
 *       -> 对已加载类执行 retransform
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpyBootstrap {

    /**
     * JUL 日志记录器 — 不依赖 slf4j，避免与外部 日志记录器 框架冲突
     */
    private static final Logger LOG = Logger.getLogger(SpyBootstrap.class.getName());

    /**
     * 字节码转换器
     */
    private static volatile SpyTransformer transformer;

    /**
     * Instrumentation 实例
     */
    private static volatile Instrumentation instrumentation;

    /**
     * 插件管理器
     */
    private static volatile PluginManager pluginManager;

    /**
     * 是否已初始化
     */
    private static volatile boolean initialized;

    /**
     * 最近一次解析的 Agent options（key 已小写）。
     *
     * <p>agentmain 线程解析后保留在此处，供 {@code ApmBootstrap} 等启动链上的模块
     * 读取（如 {@code storage.type} / {@code storage.retention.ms} / {@code storage.capacity} /
     * {@code storage.path}）。未解析过或参数为空时为空 Map（永不为 null）。</p>
     */
    private static volatile Map<String, String> agentOptions = Wraps.map();

    /**
     * 创建 spybootstrap 实例
    */
    private SpyBootstrap() {
    }

    /**
     * 初始化 Spy 引擎。
     *
     * @param args 智能体 参数
     * @param inst Instrumentation 实例
     * @return 是否成功
     */
    public static boolean init(String args, Instrumentation inst) {
        if (initialized) {
            LOG.warning("Spy 引擎已初始化，跳过");
            return true;
        }
        try {
            instrumentation = inst;
            parseAgentArgs(args);
            initTransformer(inst);
            initPluginManager(inst);
            LOG.info("Spy 引擎初始化完成");
            initialized = true;
            return true;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Spy 引擎初始化失败", e);
            return false;
        }
    }

    /**
     * 关闭 Spy 引擎。
     */
    public static void destroy() {
        if (!initialized) {
            return;
        }
        if (transformer != null && instrumentation != null) {
            try {
                instrumentation.removeTransformer(transformer);
                LOG.info("已移除字节码转换器");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "移除转换器失败", e);
            }
        }
        if (pluginManager != null) {
            pluginManager.stop();
        }
        initialized = false;
    }

    /**
     * 解析 智能体 参数。
     *
     * <p>解析结果同时写入 {@link #agentOptions}，供同 classloader 内的
     * APM 启动链（{@code ApmBootstrap}）按 key 读取生效。</p>
     *
     * @param args 智能体 参数字符串
     */
    private static void parseAgentArgs(String args) {
        if (args == null || args.isBlank()) {
            agentOptions = Wraps.map();
            return;
        }
        Map<String, String> params = Wraps.map();
        for (String pair : args.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            // 只按首个 = 分割：value 允许再含 =（如 storage.path=/tmp/a=b）
            params.put(pair.substring(0, eq).trim().toLowerCase(), pair.substring(eq + 1).trim());
        }
        agentOptions = params;
        LOG.info("Agent 参数: " + params);
    }

    /**
     * 获取最近一次解析的 Agent options（key 已小写，永不为 null）。
     *
     * @return options 只读快照
     */
    public static Map<String, String> agentOptions() {
        return Map.copyOf(agentOptions);
    }

    /**
     * 初始化字节码转换器。
     *
     * <p><b>不做全量 retransform</b>：实测对运行中 JVM（Tomcat 已承载流量）逐类
     * retransform 会触发 {@code InternalError: invalid class} 或长时间挂起，
     * 导致 agentmain 整体失败。这里只注册 retransformable transformer ——
     * 对<b>未来加载</b>的类生效（应用请求处理会持续懒加载新类，覆盖足够）。
     * handler 的拦截规则注册后，新类加载时即被织入。</p>
     *
     * @param inst Instrumentation 实例
     */
    private static void initTransformer(Instrumentation inst) throws Exception {
        List<Pattern> includes = Arrays.asList(
                Pattern.compile("com\\.example\\..*"),
                Pattern.compile("org\\..*"),
                Pattern.compile("java\\.net\\..*"),
                Pattern.compile("java\\.io\\..*")
        );
        List<Pattern> excludes = Arrays.asList(
                Pattern.compile("java\\.lang\\..*"),
                Pattern.compile("com\\.chua\\..*")
        );

        transformer = new SpyTransformer(null, includes, excludes);
        inst.addTransformer(transformer, true);
        LOG.info("字节码转换器已注册（attach 模式：仅对未来加载的类织入，"
                + "不做全量 retransform 以避免 invalid class/挂起）");
    }

    /**
     * 初始化插件管理器。
     *
     * @param inst Instrumentation 实例
     */
    private static void initPluginManager(Instrumentation inst) {
        String pluginDir = System.getProperty("runtime.plugin.dir",
                System.getProperty("user.dir") + "/plugins");
        pluginManager = new PluginManager(new java.io.File(pluginDir).toPath());
        pluginManager.load();
        LOG.info("插件加载完成，共 " + pluginManager.size() + " 个插件");
    }

    /**
     * 注册插桩点。
     *
     * @param point       插桩点
     * @param descriptor  方法描述符
     */
    public static void registerInterceptPoint(InterceptPoint point, String descriptor) {
        LOG.fine("注册插桩点（旧式，已迁移至精确规则）: " + point.getKey());
    }

    /**
     * 获取当前 Instrumentation 实例。
     *
     * @return Instrumentation 实例
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * 获取字节码转换器。
     *
     * @return SpyTransformer 实例
     */
    public static SpyTransformer getTransformer() {
        return transformer;
    }

    /**
     * 获取插件管理器。
     *
     * @return PluginManager 实例
     */
    public static PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * 是否已初始化。
     *
     * @return 已初始化返回 true
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取已插桩的类数量。
     *
     * @return 数量
     */
    public static int getTransformedClassCount() {
        if (transformer != null) {
            return transformer.getTransformedClassCount();
        }
        return 0;
    }
}
