package com.chua.deeplearning.support.spring.config;

import com.chua.deeplearning.support.core.api.ImageTo3DGenerator;
import com.chua.deeplearning.support.core.api.Model3DConfig;
import com.chua.deeplearning.support.core.api.Model3DGenerator;
import com.chua.deeplearning.support.core.api.Model3DStylizer;
import com.chua.deeplearning.support.core.api.SketchTo3DGenerator;
import com.chua.deeplearning.support.core.api.TextTo3DGenerator;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * 3D 生成自动配置装配门（main 方法直跑，不依赖测试框架）。
 *
 * <p>校验三段链路：登记文件可被 Spring Boot 读到、条件装配按开关生效、
 * 配置前缀下的属性真实绑定到 {@link Model3DConfig}。任一段缺失都会让
 * {@code chua.deeplearning.core.*} 整条配置面静默失效，故逐段观测。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.deeplearning.support.spring.config.Model3DAutoConfigurationSmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Model3DAutoConfigurationSmokeTest {

    /**
     * 自动配置登记文件路径
     */
    private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * 自动配置类全限定名
     */
    private static final String AUTO_CONFIG = GenerationApiModel3DGenerator.class.getName();

    /**
     * 配置前缀
     */
    private static final String PREFIX = "chua.deeplearning.core";

    /**
     * 探针用的端点覆盖值
     */
    private static final String PROBE_ENDPOINT = "https://probe.invalid/v9";

    /**
     * 探针用的密钥覆盖值
     */
    private static final String PROBE_API_KEY = "probe-key-from-environment";

    /**
     * 自动配置产出的 Bean 名（与 @Bean 方法名一致）到能力接口的映射
     */
    private static final Map<String, Class<?>> BEAN_TYPES = beanTypes();

    /**
     * 构造有序的 Bean 名到能力接口映射。
     *
     * @return 不可变映射
     */
    private static Map<String, Class<?>> beanTypes() {
        var map = new LinkedHashMap<String, Class<?>>();
        map.put("textTo3DGenerator", TextTo3DGenerator.class);
        map.put("imageTo3DGenerator", ImageTo3DGenerator.class);
        map.put("sketchTo3DGenerator", SketchTo3DGenerator.class);
        map.put("model3DGenerator", Model3DGenerator.class);
        map.put("model3DStylizer", Model3DStylizer.class);
        return Collections.unmodifiableMap(map);
    }

    private static int pass;

    private static int fail;

    private Model3DAutoConfigurationSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args args[0] 为可选的 classes 目录，用于核对旧登记面是否残留
     */
    public static void main(String[] args) {
        String classesDir = args.length > 0 ? args[0] : null;
        checkImportsFile(classesDir);
        checkFullAssembly();
        checkMasterSwitchOff();
        checkSingleSwitchOff();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 校验登记文件包含自动配置类，并观测本模块是否仍用失效键登记。
     *
     * @param classesDir 本模块 classes 目录，可为 空（跳过残留核对）
     */
    private static void checkImportsFile(String classesDir) {
        List<String> lines = readClasspathLines(IMPORTS);
        observe("imports 行数=" + lines.size() + " 内容=" + lines);
        check("AutoConfiguration.imports 登记了自动配置类", lines.contains(AUTO_CONFIG));
        if (classesDir == null || classesDir.isBlank()) {
            observe("未提供 classes 目录，跳过 spring.factories 残留核对");
            return;
        }
        List<String> factories = readIfPresent(Path.of(classesDir, "META-INF", "spring.factories"));
        boolean deadKey = factories.stream().anyMatch(l -> l.contains("EnableAutoConfiguration"));
        observe("本模块 spring.factories 行数=" + factories.size()
                + " 含失效键 EnableAutoConfiguration=" + deadKey);
        check("不再用 spring.factories 登记自动配置", !deadKey);
    }

    /**
     * 读取本地文本文件的全部有效行，文件不存在时返回空列表。
     *
     * @param file 文件路径
     * @return 有效行列表（去空白、去注释）
     */
    private static List<String> readIfPresent(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file).stream()
                    .map(l -> l.replace("\uFEFF", "").trim())
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (Exception e) {
            observe("读取 " + file + " 失败: " + e);
            return List.of();
        }
    }

    /**
     * 校验默认配置下 5 个能力 Bean 全部装配，且属性完成绑定。
     */
    private static void checkFullAssembly() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PREFIX + ".endpoint", PROBE_ENDPOINT);
        props.put(PREFIX + ".api-key", PROBE_API_KEY);
        props.put(PREFIX + ".max-concurrent", "7");
        try (var ctx = context(props)) {
            for (var entry : BEAN_TYPES.entrySet()) {
                boolean present = ctx.containsBeanDefinition(entry.getKey());
                boolean assignable = present && entry.getValue().isInstance(ctx.getBean(entry.getKey()));
                observe("装配 " + entry.getKey() + " present=" + present
                        + (present ? " class=" + ctx.getBean(entry.getKey()).getClass().getSimpleName() : ""));
                check(entry.getKey() + " 已装配且实现 " + entry.getValue().getSimpleName(), assignable);
            }
            var config = ctx.getBean(Model3DConfig.class);
            observe("绑定 endpoint=" + config.getEndpoint() + " apiKey=" + masked(config.getApiKey())
                    + " maxConcurrent=" + config.getMaxConcurrent());
            check("endpoint 绑定生效", PROBE_ENDPOINT.equals(config.getEndpoint()));
            check("api-key 绑定生效", PROBE_API_KEY.equals(config.getApiKey()));
            check("max-concurrent 绑定生效", config.getMaxConcurrent() == 7);
        } catch (Throwable t) {
            check("默认装配抛出异常: " + rootMessage(t), false);
        }
    }

    /**
     * 校验总开关关闭时能力 Bean 全部不装配。
     */
    private static void checkMasterSwitchOff() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PREFIX + ".enable", "false");
        try (var ctx = context(props)) {
            int total = countBeans(ctx);
            observe("enable=false 时能力 Bean 总数=" + total);
            check("总开关关闭后不装配", total == 0);
        } catch (Throwable t) {
            check("总开关场景抛出异常: " + rootMessage(t), false);
        }
    }

    /**
     * 校验单项开关可单独关闭文生 3D 而不影响其余能力。
     */
    private static void checkSingleSwitchOff() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PREFIX + ".text-to-3d-enable", "false");
        try (var ctx = context(props)) {
            boolean text = ctx.containsBeanDefinition("textTo3DGenerator");
            int others = countBeans(ctx) - (text ? 1 : 0);
            observe("text-to-3d-enable=false 时 textTo3DGenerator=" + text + " 其余=" + others);
            check("单项开关关闭文生 3D", !text);
            check("单项开关不影响其余能力", others == BEAN_TYPES.size() - 1);
        } catch (Throwable t) {
            check("单项开关场景抛出异常: " + rootMessage(t), false);
        }
    }

    /**
     * 统计已装配的能力 Bean 数量。
     *
     * @param ctx 上下文
     * @return 能力 Bean 数量
     */
    private static int countBeans(AnnotationConfigApplicationContext ctx) {
        int total = 0;
        for (String bean : BEAN_TYPES.keySet()) {
            if (ctx.containsBeanDefinition(bean)) {
                total++;
            }
        }
        return total;
    }

    /**
     * 构造仅启用自动配置的最小上下文。
     *
     * @param props 属性覆盖
     * @return 已刷新的上下文
     */
    private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe", props));
        ctx.register(ProbeApplication.class);
        ctx.refresh();
        return ctx;
    }

    /**
     * 读取类路径文本资源的全部有效行。
     *
     * @param resource 资源路径
     * @return 有效行列表（去空白、去注释）
     */
    private static List<String> readClasspathLines(String resource) {
        var urls = new ArrayList<URL>();
        try {
            urls.addAll(Collections.list(
                    Model3DAutoConfigurationSmokeTest.class.getClassLoader().getResources(resource)));
        } catch (Exception e) {
            observe("读取资源 " + resource + " 失败: " + e);
        }
        var lines = new ArrayList<String>();
        for (var url : urls) {
            try (InputStream in = url.openStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String trimmed = line.replace("\uFEFF", "").trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        lines.add(trimmed);
                    }
                }
            } catch (Exception e) {
                observe("读取 " + url + " 失败: " + e);
            }
        }
        return lines;
    }

    /**
     * 脱敏展示密钥。
     *
     * @param value 原值
     * @return 脱敏串
     */
    private static String masked(String value) {
        if (value == null || value.isEmpty()) {
            return String.valueOf(value);
        }
        return value.length() <= 4 ? "****" : value.substring(0, 2) + "***(len=" + value.length() + ")";
    }

    /**
     * 取根因消息。
     *
     * @param t 异常
     * @return 根因描述
     */
    private static String rootMessage(Throwable t) {
        var cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /**
     * 输出观察值。
     *
     * @param text 观察内容
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录单项校验结果。
     *
     * @param name 校验名
     * @param ok 是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }

    /**
     * 仅触发自动配置导入的探针入口。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class ProbeApplication {
    }
}
