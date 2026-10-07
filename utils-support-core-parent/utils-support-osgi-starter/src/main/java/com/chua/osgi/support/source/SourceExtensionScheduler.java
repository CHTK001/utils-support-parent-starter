package com.chua.osgi.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 源码扩展点调度器：把「源码目录」变成「可随时改代码即生效的 SPI 扩展点」。
 *
 * <p><b>单轮流程</b>：</p>
 * <ol>
 *   <li>计算源码目录指纹（各文件「相对路径 + 大小 + 修改时间」的摘要）</li>
 *   <li>指纹未变直接返回，避免每次轮询都触发编译</li>
 *   <li>编译 Java 源码（{@link SourceJavaCompiler}）与 Groovy 脚本
 *       （{@link SourceGroovyLoader}），合并成扩展点声明</li>
 *   <li>把声明交给 {@link SourceExtensionResolver}，并对上一轮的每个 SPI 类型执行
 *       {@code unregister(扩展名, SourceExtensionResolver.class)}</li>
 *   <li>逐个 SPI 类型重新 {@code register(resolver)}</li>
 * </ol>
 *
 * <p><b>为什么注销要逐类型按扩展名做</b>：SPI 表没有「按名批量清空」的公开入口，
 * {@code unregister} 的第一个参数是扩展名、第二个是解析器类型；
 * 带上 {@link SourceExtensionResolver} 这个 {@code resolverType} 才能只摘掉本调度器
 * 注册的定义，不误伤其他来源（如 classpath 扫描、bundle 桥接）的同类型扩展点。</p>
 *
 * <p><b>编译失败的处理</b>：任一语言编译失败即<b>跳过整轮发布</b>，
 * 保留上一轮注册状态。否则源码写错会导致线上扩展点全部消失，
 * 而这恰恰是最需要「保持可用」的时机。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SourceExtensionScheduler {

    /**
     * 默认轮询间隔（毫秒）。
     */
    private static final long DEFAULT_POLLING_INTERVAL = 5000L;

    /**
     * Java 编译器。
     */
    private final SourceJavaCompiler javaCompiler;

    /**
     * Groovy 加载器，为 {@code null} 表示不启用 Groovy。
     */
    private final SourceGroovyLoader groovyLoader;

    /**
     * 扩展点解析器。
     */
    private final SourceExtensionResolver resolver = new SourceExtensionResolver();

    /**
     * 上一轮发布的声明，按 SPI 类型分桶，注销时按桶处理。
     */
    private final Map<Class<?>, List<String>> published = Wraps.linkedMap();

    /**
     * 上一轮的目录指纹。
     */
    private volatile String lastFingerprint;

    /**
     * 轮询线程池。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 轮询间隔（毫秒）。
     */
    private final long pollingInterval;

    /**
     * 创建源码扩展点调度器。
     *
     * @param javaCompiler    Java 编译器
     * @param groovyLoader    Groovy 加载器，可为 {@code null}
     * @param pollingInterval 轮询间隔（毫秒），非正数时取默认值
     */
    public SourceExtensionScheduler(SourceJavaCompiler javaCompiler, SourceGroovyLoader groovyLoader,
                                    long pollingInterval) {
        this.javaCompiler = javaCompiler;
        this.groovyLoader = groovyLoader;
        this.pollingInterval = pollingInterval > 0 ? pollingInterval : DEFAULT_POLLING_INTERVAL;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "osgi-source-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动轮询，并立即执行一轮。
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::reloadSafely, 0L, pollingInterval, TimeUnit.MILLISECONDS);
        log.info("[osgi-source] 源码扩展点热重载已启动（轮询间隔 {}ms）", pollingInterval);
    }

    /**
     * 停止轮询并注销全部扩展点。
     */
    public void stop() {
        scheduler.shutdownNow();
        unpublishAll();
        if (groovyLoader != null) {
            groovyLoader.unloadAll();
        }
        javaCompiler.releaseLoader();
        // 必须最后清空：源码 ClassLoader 可能仍被静态字段引用着容器 Bean，
        // 不清则停机后 Bean 无法回收，容器关闭时也会拖住销毁流程
        SourceContextHolder.clear();
        log.info("[osgi-source] 源码扩展点热重载已停止");
    }

    /**
     * 轮询异常兜底，避免定时任务因异常被取消。
     */
    private void reloadSafely() {
        try {
            reloadIfChanged();
        } catch (Exception e) {
            log.warn("[osgi-source] 热重载异常（保留上一轮状态）: {}", e.getMessage());
        }
    }

    /**
     * 源码有变化时执行一轮重编译与重新发布。
     *
     * <p>对外暴露以便测试与「立刻生效」场景直接触发。</p>
     *
     * @return 发生了重编译返回 {@code true}
     */
    public synchronized boolean reloadIfChanged() {
        String fingerprint = fingerprint();
        if (fingerprint.equals(lastFingerprint)) {
            return false;
        }
        log.info("[osgi-source] 检测到源码变更，开始重编译");
        var outcome = javaCompiler.compileWithOutcome();
        if (!outcome.success()) {
            // 编译失败：保留上一轮注册状态。源码写错是最常见操作失误，
            // 此时线上扩展点必须继续可用，不能因为这一处语法错误全部消失。
            log.warn("[osgi-source] Java 源码编译失败，跳过本轮发布（保留 {} 个扩展点）",
                    published.values().stream().mapToInt(List::size).sum());
            lastFingerprint = fingerprint;
            return false;
        }
        List<SourceExtensionFactory> groovyBatch =
                groovyLoader == null ? List.of() : groovyLoader.load();

        List<SourceExtensionFactory> merged = Wraps.list(outcome.declarations());
        merged.addAll(groovyBatch);
        publish(merged);
        javaCompiler.markPublished(merged);
        lastFingerprint = fingerprint;
        log.info("[osgi-source] 已发布 {} 个扩展点", merged.size());
        return true;
    }

    /**
     * 发布一批扩展点声明：先注销上一轮，再逐类型注册本轮。
     *
     * @param batch 本轮声明
     */
    private void publish(List<SourceExtensionFactory> batch) {
        unpublishAll();
        resolver.replaceAll(batch);
        Map<Class<?>, List<SourceExtensionFactory>> grouped = Wraps.linkedMap();
        for (SourceExtensionFactory factory : batch) {
            grouped.computeIfAbsent(factory.spiType(), k -> Wraps.list()).add(factory);
        }
        for (Map.Entry<Class<?>, List<SourceExtensionFactory>> entry : grouped.entrySet()) {
            ServiceProvider.of(entry.getKey()).register(resolver);
            List<String> names = Wraps.list();
            for (SourceExtensionFactory factory : entry.getValue()) {
                names.add(factory.extensionName());
            }
            published.put(entry.getKey(), names);
            log.info("[osgi-source] 已注册 SPI 类型 {} 的 {} 个扩展点: {}",
                    entry.getKey().getSimpleName(), names.size(), names);
        }
    }

    /**
     * 注销上一轮发布的全部扩展点。
     */
    private void unpublishAll() {
        for (Map.Entry<Class<?>, List<String>> entry : published.entrySet()) {
            var provider = ServiceProvider.of(entry.getKey());
            for (String name : entry.getValue()) {
                try {
                    provider.unregister(name, SourceExtensionResolver.class);
                } catch (Exception e) {
                    log.debug("[osgi-source] 注销扩展点 {} 失败: {}", name, e.getMessage());
                }
            }
        }
        published.clear();
    }

    /**
     * 计算源码目录指纹。
     *
     * <p>只取「相对路径 + 大小 + 修改时间」，不做内容哈希：目录里的源码通常只有个位数，
     * 而内容哈希在每次轮询都要全量读盘；这三元组足以覆盖「保存文件」这个动作
     * （编辑器保存必然更新修改时间）。</p>
     *
     * @return 指纹串
     */
    private String fingerprint() {
        Path dir = javaCompiler.getSourceDirectory();
        if (!Files.isDirectory(dir)) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (!isSourceFile(path)) {
                    continue;
                }
                builder.append(dir.relativize(path))
                        .append(':').append(Files.size(path))
                        .append(':').append(Files.getLastModifiedTime(path).toMillis())
                        .append('\n');
            }
        } catch (IOException e) {
            log.debug("[osgi-source] 计算目录指纹失败: {}", e.getMessage());
            return "error:" + e.getMessage();
        }
        return builder.toString();
    }

    /**
     * 判断是否为本调度器关注的源码文件。
     *
     * @param path 文件路径
     * @return 是源码文件返回 {@code true}
     */
    private boolean isSourceFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".groovy");
    }
}
