package com.chua.common.support.rule.file;

import com.chua.common.support.rule.Fact;
import com.chua.common.support.rule.RuleBase;
import com.chua.common.support.rule.RuleBreaker;
import com.chua.common.support.rule.RuleException;
import com.chua.common.support.rule.RuleSession;
import com.chua.common.support.rule.RuleSessionConfig;
import com.chua.common.support.rule.decision.DecisionTableSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则仓库。
 *
 * <p>持有「当前生效的」{@link RuleBase}，并提供<b>原子换入</b>能力：
 * 重新装载时先在旁路完成解析与装配，全部成功后才一次性替换引用；
 * 任何一步失败都保留上一份可用规则集，并向监听器报告错误。</p>
 *
 * <h3>为什么换入是原子的</h3>
 * <p>{@link RuleBase} 不可变，{@link RuleSession} 在创建时捕获当时的 RuleBase 引用。
 * 因此替换引用后：</p>
 * <ul>
 *   <li>已在执行中的会话继续用旧规则集跑完，不会看到「半新半旧」的规则</li>
 *   <li>新会话使用新规则集</li>
 * </ul>
 * <p>这让热更新无需加锁、无需停机，也不需要让业务方感知到更新动作。</p>
 *
 * <h3>失败策略</h3>
 * <p>解析或装配抛异常时，<b>不</b>替换规则集，仓库状态保持为
 * {@link State#READY} 且版本号不变，同时记录 {@link #lastError()}。
 * 这一点对断路器类规则尤其重要：一份写错的规则文件不应让线上门禁整体失效。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleRepository repository = RuleRepository.builder()
 *         .assembler(new RuleAssembler(types, RuleActionRegistry.createDefault()))
 *         .build();
 *
 * repository.loadFrom(Paths.get("rules/orders.json"));
 * long version = repository.version();
 *
 * // 热更新：成功后版本 +1
 * repository.reload();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleRepository implements com.chua.common.support.rule.RuleEngine {

    /**
     * 仓库状态
     */
    public enum State {

        /**
         * 尚未装载任何规则
         */
        EMPTY,

        /**
         * 已装载可用规则
         */
        READY,

        /**
         * 上一次装载失败，仍在使用上一份可用规则
         */
        FAILED
    }

    /**
     * 仓库变更监听器
     */
    @FunctionalInterface
    public interface RepositoryListener {

        /**
         * 装载结果回调。
         *
         * @param event 事件
         */
        void onEvent(RepositoryEvent event);
    }

    /**
     * 仓库事件
     *
     * @param type     事件类型
     * @param version  变更后的版本号，失败时为原版本
     * @param origin   来源标识
     * @param ruleCount 生效规则数
     * @param error    错误，失败时非 null
     */
    public record RepositoryEvent(String type, long version, String origin, int ruleCount, Throwable error) {
    }

    /**
     * 当前生效的规则库
     */
    private final AtomicReference<RuleBase> current = new AtomicReference<>();

    /**
     * 当前规则集定义
     */
    private final AtomicReference<RuleSetSpec> currentSpec = new AtomicReference<>();

    /**
     * 装载来源
     */
    private final AtomicReference<Path> source = new AtomicReference<>();

    /**
     * 会话配置
     */
    private final RuleSessionConfig sessionConfig;

    /**
     * 装配器
     */
    private final RuleAssembler assembler;

    /**
     * 版本号
     */
    private final AtomicLong version = new AtomicLong();

    /**
     * 最近一次错误
     */
    private final AtomicReference<Throwable> lastError = new AtomicReference<>();

    /**
     * 监听器
     */
    private final List<RepositoryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建仓库。
     *
     * @param sessionConfig 会话配置
     * @param assembler     装配器
     */
    private RuleRepository(RuleSessionConfig sessionConfig, RuleAssembler assembler) {
        this.sessionConfig = sessionConfig == null ? RuleSessionConfig.defaultConfig() : sessionConfig;
        this.assembler = assembler == null
                ? new RuleAssembler(RuleTypeRegistry.create(), RuleActionRegistry.createDefault())
                : assembler;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建使用默认配置的仓库。
     *
     * @return 仓库
     */
    public static RuleRepository create() {
        return builder().build();
    }

    /**
     * 从内存文本装载规则（不落文件）。
     *
     * @param text   规则文本
     * @param origin 来源标识
     * @return 变更后的版本号
     */
    public long loadFrom(String text, String origin) {
        try {
            RuleSetSpec spec = RuleFileParser.parseText(text, origin);
            RuleBase ruleBase = assembler.assemble(spec,
                    RuleFileParser.parseDecisions(text, origin));
            return applySwap(spec, ruleBase, origin);
        } catch (RuleException e) {
            return failSwap(e, origin);
        }
    }

    /**
     * 从文件装载规则。
     *
     * @param path 规则文件路径
     * @return 变更后的版本号
     */
    public long loadFrom(Path path) {
        if (path == null) {
            throw new RuleException("规则文件路径不能为 null");
        }
        String origin = path.toAbsolutePath().toString();
        try {
            String text = readText(path);
            RuleSetSpec spec = RuleFileParser.parseText(text, origin);
            RuleBase ruleBase = assembler.assemble(spec,
                    RuleFileParser.parseDecisions(text, origin));
            source.set(path.toAbsolutePath());
            return applySwap(spec, ruleBase, origin);
        } catch (IOException e) {
            return failSwap(new UncheckedIOException("读取规则文件失败：" + origin, e), origin);
        } catch (RuleException e) {
            return failSwap(e, origin);
        }
    }

    /**
     * 从目录装载规则：读取目录下全部 {@code .json} 与 {@code .jsonl} 并合并。
     *
     * @param dir 规则目录
     * @return 变更后的版本号
     */
    public long loadFromDirectory(Path dir) {
        if (dir == null) {
            throw new RuleException("规则目录不能为 null");
        }
        String origin = dir.toAbsolutePath().toString();
        try {
            List<Path> files = listRuleFiles(dir);
            if (files.isEmpty()) {
                return failSwap(new RuleException("规则目录下没有 .json/.jsonl 文件：" + origin), origin);
            }
            List<RuleSpec> merged = new ArrayList<>();
            Map<String, Object> globals = new LinkedHashMap<>();
            Map<String, DecisionTableSpec> decisions = new LinkedHashMap<>();
            for (Path file : files) {
                String text = readText(file);
                String fileOrigin = file.toAbsolutePath().toString();
                RuleSetSpec spec = RuleFileParser.parseText(text, fileOrigin);
                merged.addAll(spec.rules());
                globals.putAll(spec.globals());
                decisions.putAll(RuleFileParser.parseDecisions(text, fileOrigin));
            }
            RuleSetSpec combined = new RuleSetSpec(null, globals, merged, origin);
            RuleBase ruleBase = assembler.assemble(combined, decisions);
            source.set(dir.toAbsolutePath());
            return applySwap(combined, ruleBase, origin);
        } catch (IOException e) {
            return failSwap(new UncheckedIOException("读取规则目录失败：" + origin, e), origin);
        } catch (RuleException e) {
            return failSwap(e, origin);
        }
    }

    /**
     * 重新装载当前来源。
     *
     * @return 变更后的版本号
     * @throws RuleException 没有已装载的来源时抛出
     */
    public long reload() {
        Path path = source.get();
        if (path == null) {
            throw new RuleException("尚未装载任何规则来源，无法 reload");
        }
        if (Files.isDirectory(path)) {
            return loadFromDirectory(path);
        }
        return loadFrom(path);
    }

    /**
     * 获取当前生效的规则库。
     *
     * @return 规则库，尚未装载返回 null
     */
    public RuleBase current() {
        return current.get();
    }

    /**
     * 获取当前生效的规则库（{@link RuleEngine} 契约方法）。
     *
     * @return 规则库，尚未装载返回 null
     */
    @Override
    public RuleBase ruleBase() {
        return current.get();
    }

    /**
     * 获取当前规则集定义。
     *
     * @return 规则集定义，尚未装载返回 null
     */
    public RuleSetSpec currentSpec() {
        return currentSpec.get();
    }

    /**
     * 获取全局变量。
     *
     * @return 全局变量，只读
     */
    public Map<String, Object> globals() {
        RuleSetSpec spec = currentSpec.get();
        return spec == null ? Map.of() : spec.globals();
    }

    /**
     * 获取版本号，每次成功换入递增。
     *
     * @return 版本号
     */
    public long version() {
        return version.get();
    }

    /**
     * 获取仓库状态。
     *
     * @return 仓库状态
     */
    public State state() {
        if (current.get() == null) {
            return State.EMPTY;
        }
        return lastError.get() == null ? State.READY : State.FAILED;
    }

    /**
     * 获取最近一次装载错误。
     *
     * @return 错误，无错误返回 null
     */
    public Throwable lastError() {
        return lastError.get();
    }

    /**
     * 获取装载来源。
     *
     * @return 来源路径，尚未装载返回 null
     */
    public Path source() {
        return source.get();
    }

    /**
     * 添加监听器。
     *
     * @param listener 监听器
     */
    public void addListener(RepositoryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 创建基于当前规则集的断路器（<b>快照语义</b>）。
     *
     * <p>{@link RuleBreaker} 在创建时固定当时的规则集，
     * 规则热更新后<b>已持有的断路器不会自动跟随</b>。
     * 若希望长期持有的断路器自动跟随热更新，请改用
     * {@link #newDynamicBreaker()}。</p>
     *
     * @return 规则断路器
     */
    public RuleBreaker newBreaker() {
        RuleBase ruleBase = current.get();
        if (ruleBase == null) {
            throw new RuleException("尚未装载规则，无法创建断路器");
        }
        return RuleBreaker.builder(ruleBase)
                .globals(globals())
                .sessionConfig(sessionConfig)
                .build();
    }

    /**
     * 创建随仓库热更新自动跟随的断路器（<b>动态语义</b>）。
     *
     * <p>与 {@link #newBreaker()} 的区别：</p>
     * <ul>
     *   <li>{@link #newBreaker()} 是<b>快照</b>——固定在创建那一刻的规则集；</li>
     *   <li>本方法返回的断路器在<b>每次求值开始时</b>重新读取当前规则集，
     *       因此长期持有也能立即用上新规则。</li>
     * </ul>
     *
     * <p><b>一致性保证</b>：规则快照在「一次求值内只解析一次」，
     * 同一次 {@code verdict()} 的所有条件都基于同一份规则集，
     * 不会前半段用旧规则、后半段用新规则。
     * 规则集与全局变量也打包成对取，避免阈值已更新而
     * {@code g.max} 仍是旧值这类前后矛盾。</p>
     *
     * <p>要调整断路/放行策略时用 {@link #newDynamicBreaker(boolean)}。</p>
     *
     * @return 动态规则断路器
     */
    public RuleBreaker newDynamicBreaker() {
        return newDynamicBreaker(true);
    }

    /**
     * 创建随热更新自动跟随的断路器。
     *
     * @param denyWhenNoConclusion 无结论时是否断路
     * @return 动态规则断路器
     */
    public RuleBreaker newDynamicBreaker(boolean denyWhenNoConclusion) {
        return RuleBreaker.dynamicBuilder(() -> {
            RuleBase ruleBase = current.get();
            if (ruleBase == null) {
                throw new RuleException("尚未装载规则，无法求值");
            }
            return new RuleBreaker.RuleSnapshot(ruleBase, globals());
        })
                .sessionConfig(sessionConfig)
                .denyWhenNoConclusion(denyWhenNoConclusion)
                .build();
    }

    /**
     * 创建一次性的规则会话。
     *
     * @return 规则会话，尚未装载规则时抛出
     */
    public RuleSession newSession() {
        RuleBase ruleBase = current.get();
        if (ruleBase == null) {
            throw new RuleException("尚未装载规则，无法创建会话");
        }
        return ruleBase.newSession(sessionConfig, globals(), Collections.emptyList());
    }

    @Override
    public boolean evaluate(Fact... facts) {
        return newBreaker().evaluate(facts);
    }

    @Override
    public boolean evaluate(java.util.Collection<? extends Fact> facts) {
        return newBreaker().evaluate(facts);
    }

    @Override
    public RuleBreaker.Verdict verdict(Fact... facts) {
        return newBreaker().verdict(facts);
    }

    /**
     * 执行原子换入。
     *
     * @param spec      规则集定义
     * @param ruleBase  规则库
     * @param origin    来源标识
     * @return 变更后的版本号
     */
    private long applySwap(RuleSetSpec spec, RuleBase ruleBase, String origin) {
        currentSpec.set(spec);
        current.set(ruleBase);
        lastError.set(null);
        long next = version.incrementAndGet();
        publish(new RepositoryEvent("LOADED", next, origin, ruleBase.size(), null));
        return next;
    }

    /**
     * 处理装载失败：保留上一份规则集，仅记录错误。
     *
     * @param error  错误
     * @param origin 来源标识
     * @return 未变更的版本号
     */
    private long failSwap(Throwable error, String origin) {
        lastError.set(error);
        long currentVersion = version.get();
        int count = current.get() == null ? 0 : current.get().size();
        publish(new RepositoryEvent("FAILED", currentVersion, origin, count, error));
        if (current.get() == null) {
            throw error instanceof RuleException re ? re : new RuleException(error.getMessage(), error);
        }
        return currentVersion;
    }

    /**
     * 发布事件，监听器异常不影响仓库。
     *
     * @param event 事件
     */
    private void publish(RepositoryEvent event) {
        for (RepositoryListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // 监听器异常不影响规则集生效
            }
        }
    }

    /**
     * 读取文件文本。
     *
     * @param path 文件路径
     * @return 文本内容
     * @throws IOException 读取失败时抛出
     */
    private static String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 列出规则文件。
     *
     * @param dir 目录
     * @return 按文件名排序的规则文件列表
     * @throws IOException 遍历失败时抛出
     */
    private static List<Path> listRuleFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new RuleException("规则目录不存在：" + dir);
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".json") || name.endsWith(".jsonl");
                    })
                    .forEach(files::add);
        }
        files.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    /**
     * 仓库构建器。
     */
    public static final class Builder {

        /**
         * 会话配置
         */
        private RuleSessionConfig sessionConfig = RuleSessionConfig.defaultConfig();

        /**
         * 装配器
         */
        private RuleAssembler assembler;

        /**
         * 创建构建器。
         */
        private Builder() {
        }

        /**
         * 设置会话配置。
         *
         * @param sessionConfig 会话配置
         * @return 当前构建器
         */
        public Builder sessionConfig(RuleSessionConfig sessionConfig) {
            this.sessionConfig = sessionConfig;
            return this;
        }

        /**
         * 设置装配器。
         *
         * @param assembler 装配器
         * @return 当前构建器
         */
        public Builder assembler(RuleAssembler assembler) {
            this.assembler = assembler;
            return this;
        }

        /**
         * 设置类型注册表，等价于新建默认装配器并注入。
         *
         * @param typeRegistry 类型注册表
         * @return 当前构建器
         */
        public Builder types(RuleTypeRegistry typeRegistry) {
            this.assembler = new RuleAssembler(typeRegistry, RuleActionRegistry.createDefault());
            return this;
        }

        /**
         * 构建仓库。
         *
         * @return 仓库
         */
        public RuleRepository build() {
            return new RuleRepository(sessionConfig, assembler);
        }
    }
}
